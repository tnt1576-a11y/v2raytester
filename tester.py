"""Latency test engine: spin up a core per node, measure HTTP delay via curl.

No GUI here. ``run_tests`` streams each result back through a callback so the UI
can update live. Each result also carries a direct TCP ping, optional exit IP /
country, and a full ``detail`` string (core/curl stderr) for diagnostics.
"""

import asyncio
import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import cores

_NO_WINDOW = 0x08000000 if os.name == "nt" else 0
_CURL = shutil.which("curl") or "curl"
_GEO_URL = "http://ip-api.com/json/?fields=query,countryCode"
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

# Real-destination reachability: hit lightweight endpoints on popular sites
# *through* a working proxy to see what it can actually reach (not just Google).
# [short code, url]. Editable in-app / via config.
DEFAULT_REACH_TARGETS = [
    ["YT", "https://www.youtube.com/generate_204"],
    ["IG", "https://www.instagram.com/favicon.ico"],
    ["TG", "https://web.telegram.org/"],
    ["AI", "https://api.openai.com/v1/models"],
]

# status values
OK = "ok"
TIMEOUT = "timeout"
FAILED = "failed"
UNSUPPORTED = "unsupported"
ERROR = "error"

# --- DNS cache (resolve each host once, big win for CDN-heavy lists) -------- #
_dns_cache = {}
_dns_lock = threading.Lock()


def resolve(host):
    """Resolve a hostname to an IPv4 string, cached. Returns None on failure.
    IP literals pass straight through getaddrinfo."""
    if not host:
        return None
    with _dns_lock:
        if host in _dns_cache:
            return _dns_cache[host]
    ip = None
    try:
        infos = socket.getaddrinfo(host, None, socket.AF_INET, socket.SOCK_STREAM)
        if infos:
            ip = infos[0][4][0]
    except OSError:
        ip = None
    with _dns_lock:
        _dns_cache[host] = ip
    return ip


# --- port allocation (race-safe across worker threads) --------------------- #
_used_ports = set()
_port_lock = threading.Lock()


def free_port():
    """Reserve an ephemeral loopback port. Reserved until ``release_port`` so two
    workers never hand the same port to two cores."""
    for _ in range(100):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        finally:
            s.close()
        with _port_lock:
            if port in _used_ports:
                continue
            _used_ports.add(port)
        return port
    raise RuntimeError("could not allocate a free port")


def release_port(port):
    with _port_lock:
        _used_ports.discard(port)


def wait_port(port, timeout, stop_event=None):
    """Block until 127.0.0.1:port accepts a connection or timeout elapses."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if stop_event is not None and stop_event.is_set():
            return False
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.4):
                return True
        except OSError:
            time.sleep(0.1)
    return False


def wait_ready(port, proc, timeout, stop_event=None):
    """Like ``wait_port`` but also fails fast if the core process exits early
    (a bad config crashes the core, so there's no point waiting the full window)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if stop_event is not None and stop_event.is_set():
            return False
        if proc.poll() is not None:      # core died -> config is bad, bail now
            return False
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.3):
                return True
        except OSError:
            time.sleep(0.04)
    return False


def tcp_ping(host, port, timeout=3):
    """Direct TCP connect time to host:port in ms (not through the proxy), or
    None if unreachable. Uses the DNS cache so a host is resolved only once."""
    ip = resolve(host)
    if not ip or not port:
        return None
    try:
        t0 = time.perf_counter()
        with socket.create_connection((ip, int(port)), timeout=timeout):
            return int(round((time.perf_counter() - t0) * 1000))
    except Exception:
        return None


def _read_file(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except Exception:
        return ""


def _short(text, n=160):
    return text[-n:].strip().replace("\n", " ") if text else ""


def _geo_lookup(port, max_time):
    try:
        out = subprocess.run(
            [_CURL, "--socks5-hostname", "127.0.0.1:" + str(port), "-s",
             "--max-time", str(max_time), _GEO_URL],
            capture_output=True, text=True, timeout=max_time + 4,
            creationflags=_NO_WINDOW,
        )
        data = json.loads(out.stdout)
        return data.get("query", ""), data.get("countryCode", "")
    except Exception:
        return "", ""


# ip-api.com is ~45 req/min per source IP. Geo runs only in the (small) refine pass,
# but several working configs can share an exit IP, so space geo calls out globally.
_GEO_SPACING = 1.0
_geo_lock = threading.Lock()
_geo_next = [0.0]


def _geo_throttle():
    with _geo_lock:
        now = time.monotonic()
        slot = max(now, _geo_next[0])
        _geo_next[0] = slot + _GEO_SPACING
    wait = slot - now
    if wait > 0:
        time.sleep(wait)


def _median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) // 2


def _curl_sample(port, url, max_time):
    """One latency sample through the local SOCKS proxy. Returns (code, ms):
    code is the HTTP status string ('204', '000', …) or 'TIMEOUT'; ms is None on no response."""
    try:
        out = subprocess.run(
            [_CURL, "--socks5-hostname", "127.0.0.1:" + str(port),
             "-o", os.devnull, "-w", "%{http_code} %{time_total}",
             "--max-time", str(max_time), url],
            capture_output=True, text=True, timeout=max_time + 4,
            creationflags=_NO_WINDOW,
        )
        parts = (out.stdout or "").strip().split()
        if len(parts) >= 2:
            try:
                ms = int(round(float(parts[1]) * 1000))
            except ValueError:
                ms = None
            return parts[0], ms
        return "", None
    except subprocess.TimeoutExpired:
        return "TIMEOUT", None


def _reachable(code):
    """A target counts as reachable if the proxy got a usable HTTP response.
    2xx/3xx = yes; 401/405 = server answered (auth/method) so the path works;
    000 (no response, censored) and 403/451/429 (geo-blocked) = no."""
    if not code or not code.isdigit():
        return False
    n = int(code)
    if n in (401, 405):
        return True
    return 200 <= n < 400


def _reach_check(port, url, max_time):
    """True if ``url`` responds with a usable code through the local SOCKS proxy."""
    try:
        out = subprocess.run(
            [_CURL, "--socks5-hostname", "127.0.0.1:" + str(port), "-s",
             "-o", os.devnull, "-w", "%{http_code}", "-A", _UA,
             "--max-time", str(max_time), url],
            capture_output=True, text=True, timeout=max_time + 4,
            creationflags=_NO_WINDOW,
        )
        return _reachable((out.stdout or "").strip()[-3:])
    except Exception:
        return False


def _reach_all(port, settings, stop_event=None):
    """Run every configured reachability target through an already-running proxy.
    Returns {code: bool}. Sequential + short timeout to stay bounded; only ever
    runs for configs that already passed the base test."""
    targets = settings.get("reach_targets") or []
    if not targets:
        return {}
    rt = min(int(settings.get("timeout", 8)), 7)
    out = {}
    for code, url in targets:
        if stop_event is not None and stop_event.is_set():
            break
        out[code] = _reach_check(port, url, rt)
    return out


def _result(node, status, latency=None, message="", detail="",
            exit_ip="", country="", tcp=None, reach=None):
    return {
        "node": node,
        "type": node.get("type", ""),
        "tag": node.get("tag", ""),
        "server": node.get("server", ""),
        "port": node.get("port", 0),
        "status": status,
        "latency": latency,
        "message": message,
        "detail": detail or message,
        "exit_ip": exit_ip,
        "country": country,
        "tcp_ping": tcp,
        "reach": reach or {},
    }


def _proxy_attempt(node, path, settings, stop_event, refine=False):
    """One core spawn + latency curl. Returns (result, retryable_bool).

    In ``refine`` mode it takes several latency samples (median) and runs geo + Sites;
    otherwise it's a single fast 204 connectivity check (no geo/Sites)."""
    port = free_port()
    _, cfg = cores.build_config(node, port)
    tmpdir = tempfile.mkdtemp(prefix="v2t_")
    cfg_file = os.path.join(tmpdir, "config.json")
    err_file = os.path.join(tmpdir, "core.err")
    proc = None
    try:
        with open(cfg_file, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
        with open(err_file, "w", encoding="utf-8") as errf:
            proc = subprocess.Popen(
                [path, "run", "-c", cfg_file],
                stdout=subprocess.DEVNULL, stderr=errf,
                creationflags=_NO_WINDOW, cwd=os.path.dirname(path),
            )

        if not wait_ready(port, proc, settings.get("start_timeout", 5), stop_event):
            if stop_event is not None and stop_event.is_set():
                return _result(node, ERROR, message="stopped"), False
            detail = _read_file(err_file)
            msg = "core did not start" + ((": " + _short(detail)) if detail else "")
            # only worth a retry if the core was still alive (transient), not if it crashed
            return _result(node, FAILED, message=msg, detail=detail), (proc.poll() is None)

        url = settings.get("url", "http://www.gstatic.com/generate_204")
        max_time = settings.get("timeout", 8)
        code, latency = _curl_sample(port, url, max_time)
        cdetail = _read_file(err_file)
        if code == "TIMEOUT":
            return _result(node, TIMEOUT, message="curl timed out", detail=cdetail), False
        if code == "204":
            if refine and int(settings.get("latency_samples", 3)) > 1:
                samples = [latency] if latency is not None else []
                for _ in range(int(settings.get("latency_samples", 3)) - 1):
                    if stop_event is not None and stop_event.is_set():
                        break
                    c, m = _curl_sample(port, url, max_time)
                    if c == "204" and m is not None:
                        samples.append(m)
                if samples:
                    latency = _median(samples)
            if not refine:
                return _result(node, OK, latency=latency), False   # Pass A: no geo/Sites
            ip, cc = ("", "")
            if settings.get("geo"):
                _geo_throttle()
                ip, cc = _geo_lookup(port, max_time)
            reach = _reach_all(port, settings, stop_event)  # core still alive
            return _result(node, OK, latency=latency, exit_ip=ip, country=cc, reach=reach), False
        if code in ("000", ""):
            return _result(node, TIMEOUT, message="no response (000)", detail=cdetail), False
        return _result(node, FAILED, latency=latency, message="HTTP " + code, detail=cdetail), False
    except Exception as e:  # noqa: BLE001
        return _result(node, ERROR, message=str(e)), False
    finally:
        if proc is not None:
            try:
                proc.terminate()
                try:
                    proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    proc.kill()
            except Exception:
                pass
        release_port(port)
        shutil.rmtree(tmpdir, ignore_errors=True)


def _prep(node, settings, stop_event):
    """Shared setup: returns (path, ping, early_result). early_result is non-None when
    the node is stopped/unsupported/missing-core and should be returned as-is."""
    if stop_event is not None and stop_event.is_set():
        return None, None, _result(node, ERROR, message="stopped")
    try:
        core_name, _ = cores.build_config(node, 1080)
    except ValueError as e:
        res = _result(node, UNSUPPORTED, message=str(e))
        res["tcp_ping"] = tcp_ping(node.get("server", ""), node.get("port", 0),
                                   settings.get("ping_timeout", 3))
        return None, None, res
    path = cores.core_path(core_name)
    if not path:
        return None, None, _result(node, ERROR, message=core_name + " binary not found")
    # reuse the pre-filter's ping if we have it, else measure now (direct, not via proxy)
    ping = node["_ping"] if "_ping" in node else \
        tcp_ping(node["server"], node["port"], settings.get("ping_timeout", 3))
    return path, ping, None


def test_connect(node, settings, stop_event=None):
    """Pass A: fast connectivity check (single 204, no geo/Sites). Never raises."""
    path, ping, early = _prep(node, settings, stop_event)
    if early is not None:
        return early
    res, retry = _proxy_attempt(node, path, settings, stop_event, refine=False)
    if retry and not (stop_event is not None and stop_event.is_set()):
        res, _ = _proxy_attempt(node, path, settings, stop_event, refine=False)
    res["tcp_ping"] = ping
    return res


def refine_node(node, settings, stop_event=None):
    """Pass B: accurate re-measure of a working node — median latency + geo + Sites."""
    path, ping, early = _prep(node, settings, stop_event)
    if early is not None:
        return early
    res, _ = _proxy_attempt(node, path, settings, stop_event, refine=True)
    res["tcp_ping"] = ping
    return res


def test_node(node, settings, stop_event=None):
    """Full single test (connectivity + refine) — used for manual retest. Never raises."""
    path, ping, early = _prep(node, settings, stop_event)
    if early is not None:
        return early
    res, retry = _proxy_attempt(node, path, settings, stop_event, refine=True)
    if retry and not (stop_event is not None and stop_event.is_set()):
        res, _ = _proxy_attempt(node, path, settings, stop_event, refine=True)
    res["tcp_ping"] = ping
    return res


async def _aping_all(order, groups, timeout, conc, on_ping, reachable, stop_event):
    """Round-robin the unique endpoints across ``conc`` async workers. DNS is
    resolved **lazily inside each probe** (via a sized executor), so progress
    starts flowing immediately instead of stalling on a "resolve everything
    first" phase that looks frozen on huge lists. Connect + DNS share the timeout
    budget, so a host whose DNS can't answer in time is simply treated as dead."""
    loop = asyncio.get_running_loop()
    loop.set_default_executor(ThreadPoolExecutor(max_workers=min(256, max(32, conc // 4))))

    async def probe(ep):
        ms = None
        host, port = ep
        if host and port and not (stop_event is not None and stop_event.is_set()):
            # resolve first (cached; IP literals return instantly) with its own,
            # generous budget so a busy resolver doesn't make live hosts look dead
            ip = _dns_cache.get(host)
            if ip is None and host not in _dns_cache:
                try:
                    ip = await asyncio.wait_for(loop.run_in_executor(None, resolve, host), 6)
                except Exception:  # noqa: BLE001
                    ip = None
            if ip:
                writer = None
                try:
                    t0 = time.perf_counter()
                    _, writer = await asyncio.wait_for(
                        asyncio.open_connection(ip, int(port)), timeout)
                    ms = int(round((time.perf_counter() - t0) * 1000))
                except Exception:  # noqa: BLE001
                    ms = None
                finally:
                    if writer is not None:
                        try:
                            writer.close()
                        except Exception:
                            pass
        for i in groups[ep]:
            if ms is not None:
                reachable.append(i)
            on_ping(i, ms)

    async def worker(chunk):
        for ep in chunk:
            await probe(ep)

    lanes = [order[k::conc] for k in range(min(conc, len(order)))]
    await asyncio.gather(*(worker(c) for c in lanes))


def ping_filter(nodes, settings, on_ping, stop_event=None):
    """TCP-ping nodes (cheap, no core spawn) to weed out unreachable servers
    before the expensive full test. ``on_ping(idx, ms_or_None)`` is called for
    every node. Returns the sorted list of reachable indices.

    Two optimizations make this fast on huge lists:
    * **Endpoint dedup** — many configs share a ``server:port`` (CDN fronting),
      so each unique endpoint is probed once and fanned out to all its nodes.
    * **Async probing** — non-blocking connects let hundreds/thousands run
      concurrently in a single thread instead of one OS thread per connect."""
    conc = max(64, int(settings.get("ping_concurrency", 800)))
    timeout = float(settings.get("ping_timeout", 2))

    groups = {}          # (server, port) -> [node indices]
    order = []
    for i, n in enumerate(nodes):
        ep = (n.get("server", ""), n.get("port", 0))
        if ep not in groups:
            groups[ep] = []
            order.append(ep)
        groups[ep].append(i)

    reachable = []
    try:
        asyncio.run(_aping_all(order, groups, timeout, conc, on_ping, reachable, stop_event))
    except Exception:  # noqa: BLE001 - fall back to threaded probing
        ex = ThreadPoolExecutor(max_workers=min(conc, 256))
        try:
            futures = {ex.submit(tcp_ping, ep[0], ep[1], timeout): ep for ep in order}
            for fut in as_completed(futures):
                if stop_event is not None and stop_event.is_set():
                    break
                ep = futures[fut]
                try:
                    ms = fut.result()
                except Exception:  # noqa: BLE001
                    ms = None
                for i in groups[ep]:
                    if ms is not None:
                        reachable.append(i)
                    on_ping(i, ms)
        finally:
            ex.shutdown(wait=False, cancel_futures=True)
    reachable.sort()
    return reachable


def run_tests(nodes, settings, on_result, stop_event=None, on_done=None, indices=None,
              on_refine_start=None):
    """Two-pass test. ``on_result(index, result, refined)`` is called as each finishes.

    * Pass A — connectivity (``concurrency`` workers): fast 204 check, no geo/Sites;
      ``refined=False``. The working set is collected.
    * Pass B — refine (``refine_concurrency`` workers): re-measure the working subset for
      accurate latency + geo + Sites; ``refined=True``. ``on_refine_start(count)`` fires
      between the passes. ``indices`` limits which nodes to test. Blocks; run on a worker."""
    if stop_event is None:
        stop_event = threading.Event()
    concurrency = max(1, int(settings.get("concurrency", 8)))
    refine_conc = max(1, int(settings.get("refine_concurrency", 4)))
    idxs = list(indices) if indices is not None else list(range(len(nodes)))

    # Pass A — connectivity
    working = []
    ex = ThreadPoolExecutor(max_workers=concurrency)
    try:
        futures = {ex.submit(test_connect, nodes[i], settings, stop_event): i for i in idxs}
        for fut in as_completed(futures):
            if stop_event.is_set():
                break
            idx = futures[fut]
            try:
                res = fut.result()
            except Exception as e:  # noqa: BLE001
                res = _result(nodes[idx], ERROR, message=str(e))
            if res.get("status") == OK:
                working.append(idx)
            on_result(idx, res, False)
    finally:
        ex.shutdown(wait=False, cancel_futures=True)

    # Pass B — refine the working subset at low concurrency for accurate latency + geo + Sites
    if not stop_event.is_set():
        working.sort()
        if on_refine_start is not None:
            on_refine_start(len(working))
        if working:
            ex2 = ThreadPoolExecutor(max_workers=refine_conc)
            try:
                futures = {ex2.submit(refine_node, nodes[i], settings, stop_event): i for i in working}
                for fut in as_completed(futures):
                    if stop_event.is_set():
                        break
                    idx = futures[fut]
                    try:
                        res = fut.result()
                    except Exception as e:  # noqa: BLE001
                        res = _result(nodes[idx], ERROR, message=str(e))
                    on_result(idx, res, True)
            finally:
                ex2.shutdown(wait=False, cancel_futures=True)
    elif on_refine_start is not None:
        on_refine_start(0)

    if on_done is not None:
        on_done()
