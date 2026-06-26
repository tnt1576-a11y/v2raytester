package com.v2raytester.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Share-link parsers — Kotlin port of parsers.py. Pure JVM (no android.*), so the
 * unit tests run without a device. Each parser turns one share link into a [Node].
 */
object ShareLinks {

    // ----------------------------------------------------------------- helpers
    private fun b64decode(s: String?): ByteArray {
        if (s.isNullOrEmpty()) return ByteArray(0)
        var t = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        t = t.replace('-', '+').replace('_', '/')
        t += "=".repeat((4 - t.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(t)
        } catch (e: Exception) {
            try {
                Base64.getMimeDecoder().decode(t)
            } catch (e2: Exception) {
                ByteArray(0)
            }
        }
    }

    private fun b64decodeText(s: String?): String =
        try { String(b64decode(s), Charsets.UTF_8) } catch (e: Exception) { "" }

    /** Percent-decode only (leaves '+' as-is), like Python urllib.parse.unquote. */
    private fun unquote(s: String?): String {
        if (s.isNullOrEmpty() || !s.contains('%')) return s ?: ""
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { out.write(b); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** unquote_plus: '+' becomes space, then percent-decode. */
    private fun unquotePlus(s: String?): String = unquote((s ?: "").replace('+', ' '))

    /** Like urllib.parse.parse_qs (default: blank values dropped). */
    private fun parseQs(query: String?): Map<String, List<String>> {
        val m = LinkedHashMap<String, MutableList<String>>()
        if (query.isNullOrEmpty()) return m
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            val vd = unquotePlus(v)
            if (vd.isEmpty()) continue          // parse_qs drops blank values
            m.getOrPut(unquotePlus(k)) { mutableListOf() }.add(vd)
        }
        return m
    }

    private fun q1(qs: Map<String, List<String>>, vararg keys: String, default: String = ""): String {
        for (k in keys) qs[k]?.firstOrNull()?.let { return it }
        return default
    }

    private fun alpnList(value: Any?): List<String> {
        if (value == null) return emptyList()
        val s = when (value) {
            is JSONArray -> (0 until value.length()).joinToString(",") { value.optString(it) }
            else -> value.toString()
        }
        return s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun truthy(value: Any?): Boolean =
        value?.toString()?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

    private fun normNet(net: String?): String {
        val n = (net ?: "tcp").lowercase()
        return when (n) {
            "raw", "" -> "tcp"
            "h2" -> "http"
            else -> n
        }
    }

    private fun toIntLoose(v: Any?): Int = when (v) {
        null -> 0
        is Int -> v
        is Number -> v.toInt()
        else -> v.toString().trim().toDoubleOrNull()?.toInt() ?: 0
    }

    private fun firstNonEmpty(vararg s: String): String = s.firstOrNull { it.isNotEmpty() } ?: ""

    // --- lenient URI split (urlsplit-ish, tolerant of emoji/odd chars) -------
    private data class UriParts(
        val userInfo: String?,
        val user: String?,
        val pass: String?,
        val host: String,
        val port: Int?,
        val query: String,
        val fragment: String,
    )

    private fun splitUri(link: String): UriParts {
        var rest = link.substringAfter("://", link)
        var fragment = ""
        val h = rest.indexOf('#')
        if (h >= 0) { fragment = rest.substring(h + 1); rest = rest.substring(0, h) }
        var query = ""
        val q = rest.indexOf('?')
        if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q) }
        val slash = rest.indexOf('/')
        if (slash >= 0) rest = rest.substring(0, slash)   // drop path

        var authority = rest
        var userInfo: String? = null
        val at = authority.lastIndexOf('@')
        if (at >= 0) { userInfo = authority.substring(0, at); authority = authority.substring(at + 1) }

        var host = authority
        var port: Int? = null
        if (authority.startsWith("[")) {                  // IPv6 literal
            val close = authority.indexOf(']')
            if (close >= 0) {
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                if (after.startsWith(":")) port = after.substring(1).toIntOrNull()
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull()
            }
        }
        var user: String? = null
        var pass: String? = null
        if (userInfo != null) {
            val c = userInfo.indexOf(':')
            if (c >= 0) { user = userInfo.substring(0, c); pass = userInfo.substring(c + 1) }
            else user = userInfo
        }
        return UriParts(userInfo, user, pass, host.lowercase(), port, query, fragment)
    }

    private fun frag(p: UriParts): String = if (p.fragment.isNotEmpty()) unquote(p.fragment) else ""

    // ------------------------------------------------------- userinfo URIs
    private class Ctx(val p: UriParts, val qs: Map<String, List<String>>)

    private fun userinfoNode(link: String, ptype: String): Pair<Node, Ctx> {
        val p = splitUri(link)
        val qs = parseQs(p.query)
        val net = normNet(q1(qs, "type", "net", default = "tcp"))
        val security = q1(qs, "security").lowercase()
        val node = Node(
            type = ptype,
            tag = frag(p).ifEmpty { p.host.ifEmpty { ptype } },
            raw = link,
            server = p.host,
            port = p.port ?: 0,
            net = net,
            host = q1(qs, "host"),
            path = unquote(q1(qs, "path")),
            serviceName = q1(qs, "serviceName", "servicename"),
            headerType = q1(qs, "headerType", default = "none"),
            tls = when (security) {
                "reality" -> "reality"
                "tls", "xtls" -> "tls"
                else -> ""
            },
            sni = q1(qs, "sni", "peer"),
            alpn = alpnList(q1(qs, "alpn")),
            fp = q1(qs, "fp"),
            flow = q1(qs, "flow"),
            pbk = q1(qs, "pbk", "publicKey"),
            sid = q1(qs, "sid", "shortId"),
            spx = unquote(q1(qs, "spx", "spiderX")),
            allowInsecure = truthy(q1(qs, "allowInsecure", "insecure", "allow_insecure")),
        )
        return node to Ctx(p, qs)
    }

    private fun parseVless(link: String): Node {
        val (n, ctx) = userinfoNode(link, "vless")
        return n.copy(
            uuid = unquote(ctx.p.user ?: ""),
            encryption = q1(ctx.qs, "encryption", default = "none"),
        )
    }

    private fun parseTrojan(link: String): Node {
        val (n, ctx) = userinfoNode(link, "trojan")
        return n.copy(
            password = unquote(ctx.p.user ?: ""),
            tls = n.tls.ifEmpty { "tls" },          // trojan implies TLS
        )
    }

    private fun parseAnytls(link: String): Node {
        val (n, ctx) = userinfoNode(link, "anytls")
        return n.copy(password = unquote(ctx.p.user ?: ""), tls = n.tls.ifEmpty { "tls" })
    }

    private fun parseHysteria2(link: String): Node {
        val (n, ctx) = userinfoNode(link, "hysteria2")
        return n.copy(password = unquote(ctx.p.user ?: ""), tls = n.tls.ifEmpty { "tls" })
    }

    private fun parseTuic(link: String): Node {
        val (n, ctx) = userinfoNode(link, "tuic")
        return n.copy(
            uuid = unquote(ctx.p.user ?: ""),
            password = unquote(ctx.p.pass ?: ""),
            tls = n.tls.ifEmpty { "tls" },
        )
    }

    private fun parseShadowsocks(link: String): Node {
        val p = splitUri(link)
        var method = ""
        var password = ""
        var host = p.host
        var port = p.port
        if (p.user != null && p.host.isNotEmpty()) {
            // SIP002: ss://base64(method:pass)@host:port  (userinfo plain or b64)
            val userinfo = unquote(p.userInfo)
            if (userinfo.contains(':')) {
                method = userinfo.substringBefore(':'); password = userinfo.substringAfter(':')
            } else {
                val dec = b64decodeText(userinfo)
                if (dec.contains(':')) { method = dec.substringBefore(':'); password = dec.substringAfter(':') }
            }
        } else {
            // Legacy: ss://base64(method:pass@host:port)
            val body = link.substring("ss://".length).substringBefore('#').substringBefore('?')
            val dec = b64decodeText(body)
            if (dec.contains('@') && dec.contains(':')) {
                val creds = dec.substringBeforeLast('@')
                val server = dec.substringAfterLast('@')
                method = creds.substringBefore(':'); password = creds.substringAfter(':')
                host = server.substringBeforeLast(':', server)
                val pp = server.substringAfterLast(':', "")
                port = pp.toIntOrNull()
            }
        }
        return Node(
            type = "shadowsocks",
            tag = frag(p).ifEmpty { "ss" },
            raw = link,
            server = host,
            port = port ?: 0,
            method = method,
            password = password,
            net = "tcp",
            tls = "",
        )
    }

    private fun parseProxy(link: String, ptype: String): Node {
        val p = splitUri(link)
        var user = ""
        var pwd = ""
        var host = p.host
        var port = p.port
        if (p.userInfo != null) {
            val ui = unquote(p.user)
            when {
                !p.pass.isNullOrEmpty() -> { user = ui; pwd = unquote(p.pass) }
                ui.contains(':') -> { user = ui.substringBefore(':'); pwd = ui.substringAfter(':') }
                else -> {
                    val dec = b64decodeText(ui)
                    if (dec.contains(':')) { user = dec.substringBefore(':'); pwd = dec.substringAfter(':') }
                    else user = ui
                }
            }
        } else if (p.host.isEmpty()) {
            val body = link.substringAfter("://", "").substringBefore('#')
            val dec = b64decodeText(body)
            if (dec.contains('@')) {
                val creds = dec.substringBeforeLast('@')
                val server = dec.substringAfterLast('@')
                if (creds.contains(':')) { user = creds.substringBefore(':'); pwd = creds.substringAfter(':') }
                host = server.substringBeforeLast(':', server)
                port = server.substringAfterLast(':', "").toIntOrNull()
            }
        }
        val tls = if (ptype == "http" && link.startsWith("https://")) "tls" else ""
        return Node(
            type = ptype, tag = frag(p).ifEmpty { ptype }, raw = link,
            server = host, port = port ?: 0, username = user, password = pwd, net = "tcp", tls = tls,
        )
    }

    private fun parseNaive(link: String): Node {
        val inner = link.substring("naive+".length)
        val p = splitUri(inner)
        return Node(
            type = "naive",
            tag = frag(p).ifEmpty { "naive" },
            raw = link,
            server = p.host,
            port = p.port ?: 443,
            username = unquote(p.user ?: ""),
            password = unquote(p.pass ?: ""),
            tls = if (inner.startsWith("https://")) "tls" else "",
            net = "tcp",
        )
    }

    private fun parseWireguard(link: String): Node {
        val p = splitUri(link)
        val qs = parseQs(p.query)
        val addr = q1(qs, "address", "ip")
        val reserved = q1(qs, "reserved").split(",").mapNotNull { it.trim().toIntOrNull() }
        val priv = (p.user?.let { unquote(it) }?.takeIf { it.isNotEmpty() })
            ?: q1(qs, "privateKey", "secretKey")
        return Node(
            type = "wireguard",
            tag = frag(p).ifEmpty { "wireguard" },
            raw = link,
            server = p.host,
            port = p.port ?: 0,
            privateKey = priv,
            publicKey = q1(qs, "publickey", "publicKey", "peerPublicKey"),
            preSharedKey = q1(qs, "presharedkey", "presharedKey"),
            localAddress = addr.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            reserved = reserved,
            mtu = q1(qs, "mtu", default = "0").toIntOrNull() ?: 0,
            net = "tcp",
            tls = "",
        )
    }

    private fun parseVmess(link: String): Node {
        val body = link.substring("vmess://".length)
        val data = JSONObject(b64decodeText(body))
        val net = normNet(data.optString("net", "tcp"))
        val tlsRaw = data.optString("tls", "").lowercase()
        return Node(
            type = "vmess",
            tag = firstNonEmpty(data.optString("ps", ""), data.optString("add", ""), "vmess"),
            raw = link,
            server = data.optString("add", ""),
            port = toIntLoose(data.opt("port")),
            uuid = data.optString("id", ""),
            alterId = toIntLoose(data.opt("aid")),
            encryption = firstNonEmpty(data.optString("scy", ""), data.optString("security", ""), "auto"),
            net = net,
            host = data.optString("host", ""),
            path = data.optString("path", ""),
            serviceName = if (net == "grpc") data.optString("path", "") else "",
            headerType = data.optString("type", "none"),
            tls = if (tlsRaw in setOf("tls", "reality", "xtls")) "tls" else "",
            sni = firstNonEmpty(data.optString("sni", ""), data.optString("peer", "")),
            alpn = alpnList(data.opt("alpn")),
            fp = data.optString("fp", ""),
            allowInsecure = truthy(data.opt("allowInsecure")),
        )
    }

    // --------------------------------------------------------------- dispatch
    /** Parse one share link into a [Node], or throw IllegalArgumentException. */
    fun parseLink(raw: String): Node {
        val link = raw.trim()
        val low = link.lowercase()
        val node = when {
            low.startsWith("vmess://") -> parseVmess(link)
            low.startsWith("vless://") -> parseVless(link)
            low.startsWith("trojan://") -> parseTrojan(link)
            low.startsWith("ss://") -> parseShadowsocks(link)
            low.startsWith("hysteria2://") || low.startsWith("hy2://") -> parseHysteria2(link)
            low.startsWith("tuic://") -> parseTuic(link)
            low.startsWith("anytls://") -> parseAnytls(link)
            low.startsWith("naive+") -> parseNaive(link)
            low.startsWith("wireguard://") || low.startsWith("wg://") -> parseWireguard(link)
            low.startsWith("socks://") -> parseProxy(link, "socks")
            low.startsWith("http://") || low.startsWith("https://") -> parseProxy(link, "http")
            else -> {
                val scheme = if (link.contains("://")) link.substringBefore("://") else link.take(12)
                throw IllegalArgumentException("unsupported scheme: $scheme")
            }
        }
        if (node.server.isEmpty() || node.port == 0) {
            throw IllegalArgumentException("missing server/port")
        }
        return node
    }

    /** A subscription body is usually one big base64 blob of newline-separated
     *  links. If it already looks like plain links, return as-is. */
    fun decodeSubscription(text: String?): String {
        val t = (text ?: "").trim()
        if (t.isEmpty()) return ""
        if (t.contains("://")) return t
        val decoded = b64decodeText(t)
        return if (decoded.contains("://")) decoded else t
    }

    /** Drop blank lines and metadata/comment lines (leading # or //). */
    fun stripComments(text: String?): String =
        (text ?: "").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .joinToString("\n")

    data class ParseError(val line: Int, val text: String, val message: String)

    /** Parse many lines. Returns nodes + per-line errors (blank/# lines skipped). */
    fun parseLinks(text: String?): Pair<List<Node>, List<ParseError>> {
        val nodes = ArrayList<Node>()
        val errors = ArrayList<ParseError>()
        (text ?: "").split("\n").forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            try {
                nodes.add(parseLink(line))
            } catch (e: Exception) {
                errors.add(ParseError(idx + 1, line, e.message ?: "parse error"))
            }
        }
        return nodes to errors
    }

    /** Drop duplicates by endpoint + credential, keeping first. Returns (unique, removed). */
    fun dedupe(nodes: List<Node>): Pair<List<Node>, Int> {
        val seen = HashSet<String>()
        val unique = ArrayList<Node>()
        for (n in nodes) if (seen.add(n.dedupeKey)) unique.add(n)
        return unique to (nodes.size - unique.size)
    }
}
