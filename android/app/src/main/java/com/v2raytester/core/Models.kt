package com.v2raytester.core

/** Default latency-test target (HTTP 204 = success). */
const val DEFAULT_URL = "http://www.gstatic.com/generate_204"

/** A real-destination reachability probe: a short code + a lightweight URL. */
data class ReachTarget(val code: String, val url: String)

/** Sites checked through a working proxy (editable in the UI). Mirrors
 *  tester.DEFAULT_REACH_TARGETS on desktop. */
val DEFAULT_REACH_TARGETS = listOf(
    ReachTarget("YT", "https://www.youtube.com/generate_204"),
    ReachTarget("IG", "https://www.instagram.com/favicon.ico"),
    ReachTarget("TG", "https://web.telegram.org/"),
    ReachTarget("AI", "https://api.openai.com/v1/models"),
)

enum class Status { OK, TIMEOUT, FAILED, UNSUPPORTED, ERROR, PENDING }

/**
 * Normalized proxy node — the Kotlin twin of the Python `node` dict produced by
 * parsers.py and consumed by XrayConfig. Only the fields relevant to a protocol
 * are populated; the rest keep their defaults.
 */
data class Node(
    val type: String,
    val tag: String = "",
    val raw: String = "",
    val server: String = "",
    val port: Int = 0,
    // credentials
    val uuid: String = "",
    val password: String = "",
    val method: String = "",
    val alterId: Int = 0,
    val username: String = "",
    // vless / xtls
    val flow: String = "",
    val encryption: String = "",
    // transport
    val net: String = "tcp",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val headerType: String = "none",
    // security / tls
    val tls: String = "",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    val fp: String = "",
    val allowInsecure: Boolean = false,
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    // wireguard
    val privateKey: String = "",
    val publicKey: String = "",
    val preSharedKey: String = "",
    val localAddress: List<String> = emptyList(),
    val reserved: List<Int> = emptyList(),
    val mtu: Int = 0,
) {
    /** Dedup identity: same endpoint + credential collapses across aliases. */
    val dedupeKey: String
        get() {
            val cred = listOf(uuid, password, method, privateKey).firstOrNull { it.isNotEmpty() } ?: ""
            return "$type|${server.lowercase()}|$port|$cred"
        }
}

/** Result of testing one node. Twin of tester._result(). */
data class TestResult(
    val node: Node,
    val status: Status,
    val latency: Int? = null,
    val message: String = "",
    val detail: String = "",
    val exitIp: String = "",
    val country: String = "",
    val tcpPing: Int? = null,
    val reach: Map<String, Boolean> = emptyMap(),
)

/** Tuning + toggles for a test run. Twin of the desktop _settings() dict. */
data class Settings(
    val testUrl: String = DEFAULT_URL,
    val timeoutSec: Int = 8,
    val concurrency: Int = 16,
    val geo: Boolean = true,
    val prefilter: Boolean = true,
    val reachEnabled: Boolean = true,
    val reachTargets: List<ReachTarget> = DEFAULT_REACH_TARGETS,
    val pingConcurrency: Int = 1000,   // NIO non-blocking connects: ~all in flight on one thread
    val pingTimeoutSec: Int = 2,
    val startTimeoutSec: Int = 5,      // higher: many cores boot at once under load
    // Pass B (refine): re-measure the working subset at LOW concurrency with several
    // samples for accurate, contention-free latency, then geo + Sites.
    val latencySamples: Int = 3,
    val refineConcurrency: Int = 4,
)
