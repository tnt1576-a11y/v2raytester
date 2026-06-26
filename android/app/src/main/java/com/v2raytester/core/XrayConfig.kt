package com.v2raytester.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Xray config generation — Kotlin port of cores.py (xray side only). Produces the
 * same tiny config the desktop app feeds to xray: a local SOCKS inbound + the
 * proxy as the only outbound + a freedom "direct". No routing, so no geo assets.
 */
object XrayConfig {

    /** Protocols this app can test (Xray only — sing-box types are unsupported). */
    val XRAY_TYPES = setOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http", "wireguard")

    fun isSupported(type: String): Boolean = type in XRAY_TYPES

    private fun jarr(items: List<Any>): JSONArray = JSONArray().apply { items.forEach { put(it) } }

    private fun hostList(host: String): JSONArray =
        jarr(host.split(",").map { it.trim() }.filter { it.isNotEmpty() })

    private fun stream(node: Node): JSONObject {
        val net = node.net
        val host = node.host
        val path = node.path.ifEmpty { "/" }
        val ss = JSONObject().put("network", net)

        when (net) {
            "ws" -> {
                val ws = JSONObject().put("path", path)
                if (host.isNotEmpty()) ws.put("headers", JSONObject().put("Host", host))
                ss.put("wsSettings", ws)
            }
            "grpc" -> ss.put(
                "grpcSettings",
                JSONObject().put("serviceName", node.serviceName.ifEmpty { node.path })
            )
            "httpupgrade" -> {
                val hu = JSONObject().put("path", path)
                if (host.isNotEmpty()) hu.put("host", host)
                ss.put("httpupgradeSettings", hu)
            }
            "xhttp" -> {
                val xh = JSONObject().put("path", path)
                if (host.isNotEmpty()) xh.put("host", host)
                ss.put("xhttpSettings", xh)
            }
            "kcp" -> ss.put(
                "kcpSettings",
                JSONObject().put("header", JSONObject().put("type", node.headerType.ifEmpty { "none" }))
            )
            "http" -> {
                val h2 = JSONObject().put("path", path)
                if (host.isNotEmpty()) h2.put("host", hostList(host))
                ss.put("httpSettings", h2)
            }
            "tcp" -> if (node.headerType == "http") {
                val req = JSONObject().put("headers", JSONObject())
                if (host.isNotEmpty()) req.getJSONObject("headers").put("Host", hostList(host))
                ss.put(
                    "tcpSettings",
                    JSONObject().put("header", JSONObject().put("type", "http").put("request", req))
                )
            }
        }

        val sni = node.sni.ifEmpty { host.ifEmpty { node.server } }
        when (node.tls) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", sni)
                    .put("allowInsecure", node.allowInsecure)
                if (node.alpn.isNotEmpty()) tls.put("alpn", jarr(node.alpn))
                if (node.fp.isNotEmpty()) tls.put("fingerprint", node.fp)
                ss.put("security", "tls").put("tlsSettings", tls)
            }
            "reality" -> {
                ss.put("security", "reality").put(
                    "realitySettings",
                    JSONObject()
                        .put("serverName", sni)
                        .put("fingerprint", node.fp.ifEmpty { "chrome" })
                        .put("publicKey", node.pbk)
                        .put("shortId", node.sid)
                        .put("spiderX", node.spx)
                )
            }
        }
        return ss
    }

    private fun outbound(node: Node): JSONObject = when (node.type) {
        "vmess" -> JSONObject()
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    jarr(listOf(
                        JSONObject()
                            .put("address", node.server)
                            .put("port", node.port)
                            .put(
                                "users",
                                jarr(listOf(
                                    JSONObject()
                                        .put("id", node.uuid)
                                        .put("alterId", node.alterId)
                                        .put("security", node.encryption.ifEmpty { "auto" })
                                ))
                            )
                    ))
                )
            )
            .put("streamSettings", stream(node))
            .put("tag", "proxy")

        "vless" -> {
            val user = JSONObject()
                .put("id", node.uuid)
                .put("encryption", node.encryption.ifEmpty { "none" })
            if (node.flow.isNotEmpty()) user.put("flow", node.flow)
            JSONObject()
                .put("protocol", "vless")
                .put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        jarr(listOf(
                            JSONObject().put("address", node.server).put("port", node.port)
                                .put("users", jarr(listOf(user)))
                        ))
                    )
                )
                .put("streamSettings", stream(node))
                .put("tag", "proxy")
        }

        "trojan" -> JSONObject()
            .put("protocol", "trojan")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    jarr(listOf(
                        JSONObject().put("address", node.server).put("port", node.port)
                            .put("password", node.password)
                    ))
                )
            )
            .put("streamSettings", stream(node))
            .put("tag", "proxy")

        "shadowsocks" -> JSONObject()
            .put("protocol", "shadowsocks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    jarr(listOf(
                        JSONObject().put("address", node.server).put("port", node.port)
                            .put("method", node.method).put("password", node.password)
                    ))
                )
            )
            .put("streamSettings", stream(node))
            .put("tag", "proxy")

        "socks", "http" -> {
            val server = JSONObject().put("address", node.server).put("port", node.port)
            if (node.username.isNotEmpty() || node.password.isNotEmpty()) {
                server.put("users", jarr(listOf(
                    JSONObject().put("user", node.username).put("pass", node.password)
                )))
            }
            val ob = JSONObject()
                .put("protocol", node.type)
                .put("settings", JSONObject().put("servers", jarr(listOf(server))))
                .put("tag", "proxy")
            if (node.tls == "tls") ob.put("streamSettings", stream(node))
            ob
        }

        "wireguard" -> {
            val peer = JSONObject()
                .put("publicKey", node.publicKey)
                .put("endpoint", node.server + ":" + node.port)
            if (node.preSharedKey.isNotEmpty()) peer.put("preSharedKey", node.preSharedKey)
            val s = JSONObject()
                .put("secretKey", node.privateKey)
                .put("address", jarr(node.localAddress.ifEmpty { listOf("172.16.0.2/32") }))
                .put("peers", jarr(listOf(peer)))
            if (node.reserved.isNotEmpty()) s.put("reserved", jarr(node.reserved))
            if (node.mtu != 0) s.put("mtu", node.mtu)
            JSONObject().put("protocol", "wireguard").put("settings", s).put("tag", "proxy")
        }

        else -> throw IllegalArgumentException("xray cannot handle type: ${node.type}")
    }

    /** Full xray config: SOCKS inbound on 127.0.0.1:[socksPort] + node outbound. */
    fun buildXrayConfig(node: Node, socksPort: Int): JSONObject {
        if (!isSupported(node.type)) {
            throw IllegalArgumentException("no bundled core supports '${node.type}'")
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put(
                "inbounds",
                jarr(listOf(
                    JSONObject()
                        .put("listen", "127.0.0.1")
                        .put("port", socksPort)
                        .put("protocol", "socks")
                        .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
                ))
            )
            .put(
                "outbounds",
                jarr(listOf(
                    outbound(node),
                    JSONObject().put("protocol", "freedom").put("tag", "direct")
                ))
            )
    }
}
