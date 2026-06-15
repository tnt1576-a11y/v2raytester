"""Core discovery + config generation for the V2Ray config tester.

The app is meant to live *inside* a v2rayN folder and reuse its bundled
``xray.exe`` / ``sing-box.exe``. Discovery is tolerant of layout/version
differences across v2rayN releases.

Core routing (which binary tests which protocol):
  * xray     -> vmess, vless, trojan, shadowsocks, socks, http, wireguard
  * sing-box -> hysteria2, tuic, anytls
  * naive    -> no bundled core implements it -> reported UNSUPPORTED
"""

import os
import re
import subprocess
import sys
from pathlib import Path

_EXE = ".exe" if os.name == "nt" else ""
_NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW

CORE_FOR_TYPE = {
    "vmess": "xray", "vless": "xray", "trojan": "xray",
    "shadowsocks": "xray", "socks": "xray", "http": "xray",
    "wireguard": "xray",
    "hysteria2": "singbox", "tuic": "singbox", "anytls": "singbox",
    "naive": None,
}


def app_dir():
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


# --------------------------------------------------------------------------- #
# binary discovery (cross-version)
# --------------------------------------------------------------------------- #
_CORE_CANDIDATES = {
    "xray": ["bin/xray/xray" + _EXE, "bin/xray" + _EXE, "xray" + _EXE],
    "singbox": [
        "bin/sing_box/sing-box" + _EXE, "bin/sing-box/sing-box" + _EXE,
        "bin/sing-box" + _EXE, "sing-box" + _EXE,
    ],
}
_CORE_GLOB = {"xray": "xray" + _EXE, "singbox": "sing-box" + _EXE}

_path_cache = {}


def core_path(core):
    """Locate a core binary under the app folder. Returns a str path or None."""
    if core in _path_cache:
        return _path_cache[core]
    base = app_dir()
    found = None
    for rel in _CORE_CANDIDATES.get(core, []):
        p = base / rel
        if p.is_file():
            found = p
            break
    if found is None:  # last resort: recursive search
        for p in base.rglob(_CORE_GLOB.get(core, "")):
            if p.is_file():
                found = p
                break
    result = str(found) if found else None
    _path_cache[core] = result
    return result


_version_cache = {}


def core_version(core):
    """Return a printable version string, or '' if the core is missing."""
    if core in _version_cache:
        return _version_cache[core]
    path = core_path(core)
    ver = ""
    if path:
        try:
            out = subprocess.run(
                [path, "version"], capture_output=True, text=True,
                timeout=10, creationflags=_NO_WINDOW,
            )
            text = (out.stdout or "") + (out.stderr or "")
            m = re.search(r"(\d+\.\d+\.\d+)", text)
            ver = m.group(1) if m else text.strip().splitlines()[0] if text.strip() else ""
        except Exception:
            ver = ""
    _version_cache[core] = ver
    return ver


def cores_status():
    """(human string, ok_bool) describing which cores were found."""
    xv, sv = core_version("xray"), core_version("singbox")
    parts = [
        "xray " + (xv or "NOT FOUND"),
        "sing-box " + (sv or "NOT FOUND"),
    ]
    return " | ".join(parts), bool(xv or sv)


# --------------------------------------------------------------------------- #
# xray config
# --------------------------------------------------------------------------- #
def _xray_stream(node):
    net = node.get("net", "tcp")
    host = node.get("host", "")
    path = node.get("path", "") or "/"
    ss = {"network": net}

    if net == "ws":
        ws = {"path": path}
        if host:
            ws["headers"] = {"Host": host}
        ss["wsSettings"] = ws
    elif net == "grpc":
        ss["grpcSettings"] = {"serviceName": node.get("service_name") or node.get("path", "")}
    elif net == "httpupgrade":
        hu = {"path": path}
        if host:
            hu["host"] = host
        ss["httpupgradeSettings"] = hu
    elif net == "xhttp":
        xh = {"path": path}
        if host:
            xh["host"] = host
        ss["xhttpSettings"] = xh
    elif net == "kcp":
        ss["kcpSettings"] = {"header": {"type": node.get("header_type") or "none"}}
    elif net == "http":  # h2
        h2 = {"path": path}
        if host:
            h2["host"] = [h for h in host.split(",") if h]
        ss["httpSettings"] = h2
    elif net == "tcp" and node.get("header_type") == "http":
        req = {"headers": {}}
        if host:
            req["headers"]["Host"] = [h for h in host.split(",") if h]
        ss["tcpSettings"] = {"header": {"type": "http", "request": req}}

    sec = node.get("tls", "")
    sni = node.get("sni") or host or node.get("server")
    if sec == "tls":
        tls = {"serverName": sni, "allowInsecure": node.get("allow_insecure", False)}
        if node.get("alpn"):
            tls["alpn"] = node["alpn"]
        if node.get("fp"):
            tls["fingerprint"] = node["fp"]
        ss["security"] = "tls"
        ss["tlsSettings"] = tls
    elif sec == "reality":
        ss["security"] = "reality"
        ss["realitySettings"] = {
            "serverName": sni,
            "fingerprint": node.get("fp") or "chrome",
            "publicKey": node.get("pbk", ""),
            "shortId": node.get("sid", ""),
            "spiderX": node.get("spx", ""),
        }
    return ss


def _xray_outbound(node):
    t = node["type"]
    if t == "vmess":
        return {
            "protocol": "vmess",
            "settings": {"vnext": [{
                "address": node["server"], "port": node["port"],
                "users": [{"id": node["uuid"], "alterId": node.get("alter_id", 0),
                           "security": node.get("encryption", "auto")}],
            }]},
            "streamSettings": _xray_stream(node), "tag": "proxy",
        }
    if t == "vless":
        user = {"id": node["uuid"], "encryption": node.get("encryption", "none")}
        if node.get("flow"):
            user["flow"] = node["flow"]
        return {
            "protocol": "vless",
            "settings": {"vnext": [{"address": node["server"], "port": node["port"],
                                    "users": [user]}]},
            "streamSettings": _xray_stream(node), "tag": "proxy",
        }
    if t == "trojan":
        return {
            "protocol": "trojan",
            "settings": {"servers": [{"address": node["server"], "port": node["port"],
                                      "password": node.get("password", "")}]},
            "streamSettings": _xray_stream(node), "tag": "proxy",
        }
    if t == "shadowsocks":
        return {
            "protocol": "shadowsocks",
            "settings": {"servers": [{"address": node["server"], "port": node["port"],
                                      "method": node.get("method", ""),
                                      "password": node.get("password", "")}]},
            "streamSettings": _xray_stream(node), "tag": "proxy",
        }
    if t in ("socks", "http"):
        server = {"address": node["server"], "port": node["port"]}
        if node.get("username") or node.get("password"):
            server["users"] = [{"user": node.get("username", ""),
                                "pass": node.get("password", "")}]
        ob = {"protocol": t, "settings": {"servers": [server]}, "tag": "proxy"}
        if node.get("tls") == "tls":
            ob["streamSettings"] = _xray_stream(node)
        return ob
    if t == "wireguard":
        peer = {"publicKey": node.get("public_key", ""),
                "endpoint": node["server"] + ":" + str(node["port"])}
        if node.get("pre_shared_key"):
            peer["preSharedKey"] = node["pre_shared_key"]
        s = {
            "secretKey": node.get("private_key", ""),
            "address": node.get("local_address") or ["172.16.0.2/32"],
            "peers": [peer],
        }
        if node.get("reserved"):
            s["reserved"] = node["reserved"]
        if node.get("mtu"):
            s["mtu"] = node["mtu"]
        return {"protocol": "wireguard", "settings": s, "tag": "proxy"}
    raise ValueError("xray cannot handle type: " + t)


def build_xray_config(node, socks_port):
    return {
        "log": {"loglevel": "none"},
        "inbounds": [{
            "listen": "127.0.0.1", "port": socks_port, "protocol": "socks",
            "settings": {"udp": True, "auth": "noauth"},
        }],
        "outbounds": [_xray_outbound(node), {"protocol": "freedom", "tag": "direct"}],
    }


# --------------------------------------------------------------------------- #
# sing-box config
# --------------------------------------------------------------------------- #
def _sb_tls(node, default_alpn=None):
    tls = {
        "enabled": True,
        "server_name": node.get("sni") or node.get("host") or node["server"],
        "insecure": node.get("allow_insecure", False),
    }
    alpn = node.get("alpn") or default_alpn
    if alpn:
        tls["alpn"] = alpn
    return tls


def _singbox_outbound(node):
    t = node["type"]
    if t == "hysteria2":
        ob = {
            "type": "hysteria2", "tag": "proxy",
            "server": node["server"], "server_port": node["port"],
            "password": node.get("password", ""),
            "tls": _sb_tls(node, ["h3"]),
        }
        if node.get("obfs"):
            ob["obfs"] = {"type": node["obfs"], "password": node.get("obfs_password", "")}
        return ob
    if t == "tuic":
        return {
            "type": "tuic", "tag": "proxy",
            "server": node["server"], "server_port": node["port"],
            "uuid": node.get("uuid", ""), "password": node.get("password", ""),
            "congestion_control": node.get("congestion_control", "bbr"),
            "udp_relay_mode": node.get("udp_relay_mode", "native"),
            "tls": _sb_tls(node, ["h3"]),
        }
    if t == "anytls":
        return {
            "type": "anytls", "tag": "proxy",
            "server": node["server"], "server_port": node["port"],
            "password": node.get("password", ""),
            "tls": _sb_tls(node),
        }
    raise ValueError("sing-box cannot handle type: " + t)


def build_singbox_config(node, socks_port):
    return {
        "log": {"level": "fatal"},
        "inbounds": [{
            "type": "mixed", "tag": "in",
            "listen": "127.0.0.1", "listen_port": socks_port,
        }],
        "outbounds": [_singbox_outbound(node), {"type": "direct", "tag": "direct"}],
        "route": {"final": "proxy"},
    }


def build_config(node, socks_port):
    """Return (core_name, config_dict) for a node, or raise ValueError if no
    bundled core supports it."""
    core = CORE_FOR_TYPE.get(node["type"])
    if core == "xray":
        return "xray", build_xray_config(node, socks_port)
    if core == "singbox":
        return "singbox", build_singbox_config(node, socks_port)
    raise ValueError("no bundled core supports '" + node["type"] + "'")
