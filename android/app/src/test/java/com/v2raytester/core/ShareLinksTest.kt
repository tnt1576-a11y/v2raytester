package com.v2raytester.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareLinksTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun vmess_ws_tls() {
        val json = JSONObject()
            .put("ps", "MyVM").put("add", "1.2.3.4").put("port", "443")
            .put("id", "11111111-1111-1111-1111-111111111111").put("aid", "0")
            .put("net", "ws").put("host", "h.com").put("path", "/p")
            .put("type", "none").put("tls", "tls").put("sni", "s.com").toString()
        val n = ShareLinks.parseLink("vmess://" + b64(json))
        assertEquals("vmess", n.type)
        assertEquals("MyVM", n.tag)
        assertEquals("1.2.3.4", n.server)
        assertEquals(443, n.port)
        assertEquals("11111111-1111-1111-1111-111111111111", n.uuid)
        assertEquals("ws", n.net)
        assertEquals("h.com", n.host)
        assertEquals("/p", n.path)
        assertEquals("tls", n.tls)
        assertEquals("s.com", n.sni)
    }

    @Test fun vless_reality() {
        val n = ShareLinks.parseLink(
            "vless://aaaa1111-2222-3333-4444-555566667777@9.9.9.9:443" +
                "?security=reality&pbk=PUBKEY&sid=ab12&sni=www.example.com&fp=chrome" +
                "&flow=xtls-rprx-vision&type=tcp#Node%20A"
        )
        assertEquals("vless", n.type)
        assertEquals("Node A", n.tag)               // fragment unquoted
        assertEquals("9.9.9.9", n.server)
        assertEquals(443, n.port)
        assertEquals("aaaa1111-2222-3333-4444-555566667777", n.uuid)
        assertEquals("reality", n.tls)
        assertEquals("PUBKEY", n.pbk)
        assertEquals("ab12", n.sid)
        assertEquals("www.example.com", n.sni)
        assertEquals("chrome", n.fp)
        assertEquals("xtls-rprx-vision", n.flow)
        assertEquals("tcp", n.net)
    }

    @Test fun trojan_ws() {
        val n = ShareLinks.parseLink(
            "trojan://secret@cdn.host:443?security=tls&type=ws&host=front.com&path=%2Fws&sni=s.com#T"
        )
        assertEquals("trojan", n.type)
        assertEquals("secret", n.password)
        assertEquals("tls", n.tls)
        assertEquals("ws", n.net)
        assertEquals("front.com", n.host)
        assertEquals("/ws", n.path)
        assertEquals("s.com", n.sni)
    }

    @Test fun trojan_defaults_tls() {
        val n = ShareLinks.parseLink("trojan://pw@h.com:443#x")
        assertEquals("tls", n.tls)                  // trojan implies TLS even without security=
    }

    @Test fun shadowsocks_sip002() {
        val n = ShareLinks.parseLink("ss://" + b64("aes-128-gcm:shadowsocks") + "@1.2.3.4:8388#SS")
        assertEquals("shadowsocks", n.type)
        assertEquals("aes-128-gcm", n.method)
        assertEquals("shadowsocks", n.password)
        assertEquals("1.2.3.4", n.server)
        assertEquals(8388, n.port)
    }

    @Test fun shadowsocks_legacy() {
        val n = ShareLinks.parseLink("ss://" + b64("aes-256-gcm:pw@5.6.7.8:8388") + "#L")
        assertEquals("aes-256-gcm", n.method)
        assertEquals("pw", n.password)
        assertEquals("5.6.7.8", n.server)
        assertEquals(8388, n.port)
    }

    @Test fun socks_userpass() {
        val n = ShareLinks.parseLink("socks://user:pass@1.2.3.4:1080#S")
        assertEquals("socks", n.type)
        assertEquals("user", n.username)
        assertEquals("pass", n.password)
        assertEquals(1080, n.port)
    }

    @Test fun tuic_uuid_password() {
        val n = ShareLinks.parseLink("tuic://uuid-x:secret@1.2.3.4:443?congestion_control=bbr#TU")
        assertEquals("tuic", n.type)
        assertEquals("uuid-x", n.uuid)
        assertEquals("secret", n.password)
    }

    @Test fun unsupported_scheme_errors() {
        val (nodes, errors) = ShareLinks.parseLinks("ssr://abcdef\nvless://u@h.com:443#ok")
        assertEquals(1, nodes.size)
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("unsupported"))
    }

    @Test fun missing_server_port_errors() {
        val (_, errors) = ShareLinks.parseLinks("vless://uuid@:0#bad")
        assertEquals(1, errors.size)
    }

    @Test fun decode_base64_subscription() {
        val plain = "vless://u@a.com:443#1\ntrojan://p@b.com:443#2"
        val decoded = ShareLinks.decodeSubscription(b64(plain))
        assertTrue(decoded.contains("://"))
        assertEquals(2, ShareLinks.parseLinks(decoded).first.size)
    }

    @Test fun plain_subscription_passthrough() {
        val plain = "vless://u@a.com:443#1"
        assertEquals(plain, ShareLinks.decodeSubscription(plain))
    }

    @Test fun strip_comments() {
        val raw = "#profile-title: x\n//note\n\nvless://u@a.com:443#1\nss://" +
            b64("aes-128-gcm:pw") + "@1.2.3.4:8388#2"
        val cleaned = ShareLinks.stripComments(raw)
        assertEquals(2, cleaned.split("\n").size)
        assertFalse(cleaned.contains("#profile-title"))
    }

    @Test fun dedupe_same_endpoint() {
        val (nodes, _) = ShareLinks.parseLinks(
            "vless://u@a.com:443#alias1\nvless://u@a.com:443#alias2\ntrojan://p@b.com:443#x"
        )
        val (unique, removed) = ShareLinks.dedupe(nodes)
        assertEquals(2, unique.size)
        assertEquals(1, removed)
    }
}
