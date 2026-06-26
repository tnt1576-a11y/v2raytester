package com.v2raytester.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class XrayConfigTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun out0(cfg: JSONObject) = cfg.getJSONArray("outbounds").getJSONObject(0)

    @Test fun vmess_ws_tls_config() {
        val json = JSONObject().put("ps", "v").put("add", "1.2.3.4").put("port", "443")
            .put("id", "uid").put("net", "ws").put("host", "h.com").put("path", "/p")
            .put("tls", "tls").put("sni", "s.com").toString()
        val cfg = XrayConfig.buildXrayConfig(ShareLinks.parseLink("vmess://" + b64(json)), 10800)

        val inbound = cfg.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(10800, inbound.getInt("port"))
        assertEquals("socks", inbound.getString("protocol"))

        val o = out0(cfg)
        assertEquals("vmess", o.getString("protocol"))
        val vnext = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("1.2.3.4", vnext.getString("address"))
        assertEquals(443, vnext.getInt("port"))
        val ss = o.getJSONObject("streamSettings")
        assertEquals("ws", ss.getString("network"))
        assertEquals("/p", ss.getJSONObject("wsSettings").getString("path"))
        assertEquals("tls", ss.getString("security"))
        assertEquals("s.com", ss.getJSONObject("tlsSettings").getString("serverName"))

        assertEquals("freedom", cfg.getJSONArray("outbounds").getJSONObject(1).getString("protocol"))
    }

    @Test fun vless_reality_config() {
        val n = ShareLinks.parseLink(
            "vless://uid@9.9.9.9:443?security=reality&pbk=PUBKEY&sid=ab12&sni=www.x.com" +
                "&fp=chrome&flow=xtls-rprx-vision&type=tcp#r"
        )
        val o = out0(XrayConfig.buildXrayConfig(n, 1080))
        assertEquals("vless", o.getString("protocol"))
        val ss = o.getJSONObject("streamSettings")
        assertEquals("reality", ss.getString("security"))
        assertEquals("PUBKEY", ss.getJSONObject("realitySettings").getString("publicKey"))
        assertEquals("ab12", ss.getJSONObject("realitySettings").getString("shortId"))
        val user = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", user.getString("flow"))
        assertEquals("none", user.getString("encryption"))
    }

    @Test fun vless_grpc_config() {
        val n = ShareLinks.parseLink("vless://uid@h.com:443?type=grpc&serviceName=gsvc&security=tls#g")
        val ss = out0(XrayConfig.buildXrayConfig(n, 1080)).getJSONObject("streamSettings")
        assertEquals("grpc", ss.getString("network"))
        assertEquals("gsvc", ss.getJSONObject("grpcSettings").getString("serviceName"))
    }

    @Test fun trojan_config() {
        val o = out0(XrayConfig.buildXrayConfig(ShareLinks.parseLink("trojan://secret@h.com:443#t"), 1080))
        assertEquals("trojan", o.getString("protocol"))
        assertEquals(
            "secret",
            o.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password")
        )
    }

    @Test fun shadowsocks_config() {
        val n = ShareLinks.parseLink("ss://" + b64("aes-128-gcm:pw") + "@1.2.3.4:8388#s")
        val srv = out0(XrayConfig.buildXrayConfig(n, 1080))
            .getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("aes-128-gcm", srv.getString("method"))
        assertEquals("pw", srv.getString("password"))
    }

    @Test fun supported_flags_and_unsupported_throws() {
        assertTrue(XrayConfig.isSupported("vmess"))
        assertFalse(XrayConfig.isSupported("hysteria2"))
        try {
            XrayConfig.buildXrayConfig(Node(type = "hysteria2", server = "h", port = 443), 1)
            fail("expected unsupported to throw")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
