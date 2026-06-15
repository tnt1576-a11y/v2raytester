"""Share-link parsers for the V2Ray config tester.

Every parser turns one share link (vmess://, vless://, ...) into a normalized
``node`` dict that ``cores.py`` knows how to turn into an xray/sing-box config.

Normalized node schema (only the relevant keys are set per protocol)::

    {
      "type":   one of TYPES below,
      "tag":    alias / remark (str),
      "raw":    the original share link (kept for export),
      "server": host (str),
      "port":   int,

      # credentials
      "uuid", "password", "method", "alter_id", "username",

      # vless / xtls
      "flow", "encryption",

      # transport
      "net":    tcp|ws|grpc|kcp|httpupgrade|xhttp,
      "host", "path", "service_name", "header_type",

      # security / tls
      "tls":    ""|tls|reality,
      "sni", "alpn" (list), "fp", "allow_insecure" (bool),
      "pbk", "sid", "spx",          # reality

      # hysteria2
      "obfs", "obfs_password",
      # tuic
      "congestion_control", "udp_relay_mode",
      # wireguard
      "private_key", "public_key", "pre_shared_key",
      "local_address" (list), "reserved", "mtu",
    }
"""

import base64
import json
from urllib.parse import urlsplit, parse_qs, unquote

TYPES = (
    "vmess", "vless", "trojan", "shadowsocks", "socks", "http",
    "hysteria2", "tuic", "anytls", "naive", "wireguard",
)


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def _b64decode(s):
    """Decode standard or url-safe base64, tolerant of missing padding."""
    if s is None:
        return b""
    s = s.strip().replace("\n", "").replace("\r", "")
    s = s.replace("-", "+").replace("_", "/")
    s += "=" * (-len(s) % 4)
    return base64.b64decode(s)


def _b64decode_text(s):
    try:
        return _b64decode(s).decode("utf-8", "replace")
    except Exception:
        return ""


def _q1(qs, *keys, default=""):
    """First value for the first present key in a parse_qs dict."""
    for k in keys:
        if k in qs and qs[k]:
            return qs[k][0]
    return default


def _alpn(value):
    if not value:
        return []
    if isinstance(value, list):
        value = ",".join(value)
    return [a.strip() for a in value.split(",") if a.strip()]


def _truthy(value):
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def _frag(split):
    return unquote(split.fragment) if split.fragment else ""


def _norm_net(net):
    net = (net or "tcp").lower()
    if net in ("raw", ""):
        return "tcp"
    if net == "h2":
        return "http"
    return net


# --------------------------------------------------------------------------- #
# per-protocol parsers
# --------------------------------------------------------------------------- #
def parse_vmess(link):
    body = link[len("vmess://"):]
    data = json.loads(_b64decode_text(body))
    # vmess JSON uses string-ish values; coerce carefully.
    net = _norm_net(data.get("net", "tcp"))
    tls_raw = (data.get("tls") or "").lower()
    node = {
        "type": "vmess",
        "tag": str(data.get("ps") or data.get("add") or "vmess"),
        "raw": link,
        "server": str(data.get("add", "")),
        "port": int(data.get("port") or 0),
        "uuid": str(data.get("id", "")),
        "alter_id": int(data.get("aid") or 0),
        "encryption": str(data.get("scy") or data.get("security") or "auto"),
        "net": net,
        "host": str(data.get("host", "")),
        "path": str(data.get("path", "")),
        "service_name": str(data.get("path", "")) if net == "grpc" else "",
        "header_type": str(data.get("type", "none")),
        "tls": "tls" if tls_raw in ("tls", "reality", "xtls") else "",
        "sni": str(data.get("sni") or data.get("peer") or ""),
        "alpn": _alpn(data.get("alpn")),
        "fp": str(data.get("fp", "")),
        "allow_insecure": _truthy(data.get("allowInsecure")),
    }
    return node


def _parse_userinfo_uri(link, ptype):
    """Shared parser for vless/trojan/anytls/hysteria2/tuic style URIs:
    scheme://<userinfo>@host:port?query#alias  -> partially filled node."""
    sp = urlsplit(link)
    qs = parse_qs(sp.query)
    net = _norm_net(_q1(qs, "type", "net", default="tcp"))
    security = _q1(qs, "security").lower()
    node = {
        "type": ptype,
        "tag": _frag(sp) or sp.hostname or ptype,
        "raw": link,
        "server": sp.hostname or "",
        "port": int(sp.port or 0),
        "net": net,
        "host": _q1(qs, "host"),
        "path": unquote(_q1(qs, "path")),
        "service_name": _q1(qs, "serviceName", "servicename"),
        "header_type": _q1(qs, "headerType", default="none"),
        "tls": "reality" if security == "reality" else ("tls" if security in ("tls", "xtls") else ""),
        "sni": _q1(qs, "sni", "peer"),
        "alpn": _alpn(_q1(qs, "alpn")),
        "fp": _q1(qs, "fp"),
        "flow": _q1(qs, "flow"),
        "pbk": _q1(qs, "pbk", "publicKey"),
        "sid": _q1(qs, "sid", "shortId"),
        "spx": unquote(_q1(qs, "spx", "spiderX")),
        "allow_insecure": _truthy(_q1(qs, "allowInsecure", "insecure", "allow_insecure")),
        "_qs": qs,
        "_split": sp,
    }
    return node


def parse_vless(link):
    node = _parse_userinfo_uri(link, "vless")
    node["uuid"] = unquote(node["_split"].username or "")
    node["encryption"] = _q1(node["_qs"], "encryption", default="none")
    return node


def parse_trojan(link):
    node = _parse_userinfo_uri(link, "trojan")
    node["password"] = unquote(node["_split"].username or "")
    if not node["tls"]:
        node["tls"] = "tls"  # trojan implies TLS
    return node


def parse_anytls(link):
    node = _parse_userinfo_uri(link, "anytls")
    node["password"] = unquote(node["_split"].username or "")
    if not node["tls"]:
        node["tls"] = "tls"
    return node


def parse_hysteria2(link):
    node = _parse_userinfo_uri(link, "hysteria2")
    node["password"] = unquote(node["_split"].username or "")
    node["obfs"] = _q1(node["_qs"], "obfs")
    node["obfs_password"] = _q1(node["_qs"], "obfs-password", "obfs_password")
    if not node["tls"]:
        node["tls"] = "tls"  # hysteria2 is always TLS
    return node


def parse_tuic(link):
    node = _parse_userinfo_uri(link, "tuic")
    sp = node["_split"]
    node["uuid"] = unquote(sp.username or "")
    node["password"] = unquote(sp.password or "")
    node["congestion_control"] = _q1(node["_qs"], "congestion_control", "congestion", default="bbr")
    node["udp_relay_mode"] = _q1(node["_qs"], "udp_relay_mode", default="native")
    if not node["tls"]:
        node["tls"] = "tls"
    return node


def parse_shadowsocks(link):
    sp = urlsplit(link)
    tag = _frag(sp) or "ss"
    method = password = ""
    host = sp.hostname
    port = sp.port

    if sp.username and host:
        # SIP002: ss://base64(method:pass)@host:port  (userinfo may be plain or b64)
        userinfo = unquote(sp.username)
        if ":" in userinfo:
            method, password = userinfo.split(":", 1)
        else:
            dec = _b64decode_text(userinfo)
            if ":" in dec:
                method, password = dec.split(":", 1)
    else:
        # Legacy: ss://base64(method:pass@host:port)
        body = link[len("ss://"):].split("#", 1)[0].split("?", 1)[0]
        dec = _b64decode_text(body)
        if "@" in dec and ":" in dec:
            creds, server = dec.rsplit("@", 1)
            method, password = creds.split(":", 1)
            host, _, p = server.rpartition(":")
            host = host or server
            port = int(p) if p.isdigit() else None
    return {
        "type": "shadowsocks",
        "tag": tag,
        "raw": link,
        "server": host or "",
        "port": int(port or 0),
        "method": method,
        "password": password,
        "net": "tcp",
        "tls": "",
    }


def _parse_proxy(link, ptype, scheme_len):
    """socks:// and http:// proxies. Userinfo may be plain or base64(user:pass)."""
    sp = urlsplit(link)
    tag = _frag(sp) or ptype
    host, port = sp.hostname, sp.port
    user = pwd = ""
    if sp.username is not None:
        ui = unquote(sp.username or "")
        if sp.password:
            user, pwd = ui, unquote(sp.password)
        elif ":" in ui:
            user, pwd = ui.split(":", 1)
        else:
            dec = _b64decode_text(ui)
            if ":" in dec:
                user, pwd = dec.split(":", 1)
            else:
                user = ui
    elif host is None:
        # whole body may be base64(user:pass@host:port)
        body = link[scheme_len:].split("#", 1)[0]
        dec = _b64decode_text(body)
        if "@" in dec:
            creds, server = dec.rsplit("@", 1)
            if ":" in creds:
                user, pwd = creds.split(":", 1)
            host, _, p = server.rpartition(":")
            port = int(p) if p.isdigit() else None
    tls = "tls" if ptype == "http" and link.startswith("https://") else ""
    return {
        "type": ptype,
        "tag": tag,
        "raw": link,
        "server": host or "",
        "port": int(port or 0),
        "username": user,
        "password": pwd,
        "net": "tcp",
        "tls": tls,
    }


def parse_naive(link):
    # naive+https://user:pass@host:port#alias  (or naive+quic://)
    inner = link[len("naive+"):]
    sp = urlsplit(inner)
    return {
        "type": "naive",
        "tag": _frag(sp) or "naive",
        "raw": link,
        "server": sp.hostname or "",
        "port": int(sp.port or 443),
        "username": unquote(sp.username or ""),
        "password": unquote(sp.password or ""),
        "tls": "tls" if inner.startswith("https://") else "",
        "net": "tcp",
    }


def parse_wireguard(link):
    sp = urlsplit(link)
    qs = parse_qs(sp.query)
    addr = _q1(qs, "address", "ip")
    reserved = _q1(qs, "reserved")
    reserved_list = []
    if reserved:
        reserved_list = [int(x) for x in reserved.split(",") if x.strip().isdigit()]
    return {
        "type": "wireguard",
        "tag": _frag(sp) or "wireguard",
        "raw": link,
        "server": sp.hostname or "",
        "port": int(sp.port or 0),
        "private_key": unquote(sp.username or _q1(qs, "privateKey", "secretKey")),
        "public_key": _q1(qs, "publickey", "publicKey", "peerPublicKey"),
        "pre_shared_key": _q1(qs, "presharedkey", "presharedKey"),
        "local_address": [a.strip() for a in addr.split(",") if a.strip()],
        "reserved": reserved_list,
        "mtu": int(_q1(qs, "mtu", default="0") or 0),
        "net": "tcp",
        "tls": "",
    }


# --------------------------------------------------------------------------- #
# dispatch
# --------------------------------------------------------------------------- #
def parse_link(link):
    """Parse one share link into a normalized node, or raise ValueError."""
    link = link.strip()
    low = link.lower()
    if low.startswith("vmess://"):
        node = parse_vmess(link)
    elif low.startswith("vless://"):
        node = parse_vless(link)
    elif low.startswith("trojan://"):
        node = parse_trojan(link)
    elif low.startswith(("ss://",)):
        node = parse_shadowsocks(link)
    elif low.startswith(("hysteria2://", "hy2://")):
        node = parse_hysteria2(link)
    elif low.startswith("tuic://"):
        node = parse_tuic(link)
    elif low.startswith("anytls://"):
        node = parse_anytls(link)
    elif low.startswith("naive+"):
        node = parse_naive(link)
    elif low.startswith(("wireguard://", "wg://")):
        node = parse_wireguard(link)
    elif low.startswith("socks://"):
        node = _parse_proxy(link, "socks", len("socks://"))
    elif low.startswith(("http://", "https://")):
        node = _parse_proxy(link, "http", link.index("//") + 2)
    else:
        scheme = link.split("://", 1)[0] if "://" in link else link[:12]
        raise ValueError("unsupported scheme: " + scheme)

    # strip internal helper keys
    node.pop("_qs", None)
    node.pop("_split", None)
    if not node.get("server") or not node.get("port"):
        raise ValueError("missing server/port")
    return node


def decode_subscription(text):
    """A subscription body is usually one big base64 blob whose decoded form is
    newline-separated links. If it already looks like plain links, return as-is."""
    text = (text or "").strip()
    if not text:
        return ""
    if "://" in text:
        return text  # already plain links
    decoded = _b64decode_text(text)
    return decoded if "://" in decoded else text


def dedupe(nodes):
    """Drop duplicate nodes, keeping first occurrence. Identity is the endpoint +
    credential, so the same server re-shared under a different alias collapses.
    Returns (unique_nodes, removed_count)."""
    seen = set()
    unique = []
    for n in nodes:
        cred = n.get("uuid") or n.get("password") or n.get("method") or n.get("private_key") or ""
        key = (n.get("type"), (n.get("server") or "").lower(), n.get("port"), cred)
        if key in seen:
            continue
        seen.add(key)
        unique.append(n)
    return unique, len(nodes) - len(unique)


def parse_links(text):
    """Parse many lines. Returns (nodes, errors) where errors is a list of
    (line_number, line, message). Blank lines and ``#`` comments are skipped."""
    nodes, errors = [], []
    for i, raw in enumerate((text or "").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        try:
            nodes.append(parse_link(line))
        except Exception as e:  # noqa: BLE001 - report, don't crash
            errors.append((i, line, str(e)))
    return nodes, errors


if __name__ == "__main__":
    import sys
    data = sys.stdin.read()
    ns, errs = parse_links(decode_subscription(data))
    for n in ns:
        print(n["type"], n["server"] + ":" + str(n["port"]), "-", n["tag"])
    for ln, _, msg in errs:
        print("ERR line", ln, msg)
