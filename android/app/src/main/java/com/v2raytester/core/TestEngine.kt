package com.v2raytester.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Test engine — Kotlin port of tester.py for Android.
 *
 * @param xrayPath absolute path to the bundled xray binary
 *   (`applicationInfo.nativeLibraryDir + "/libxray.so"`).
 * @param workDir a writable dir for per-test config/log files (`cacheDir`).
 *
 * Pure java.*/OkHttp/coroutines — no android.* imports.
 */
class TestEngine(
    private val xrayPath: String,
    private val workDir: File,
) {
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val geoUrl = "http://ip-api.com/json/?fields=query,countryCode"

    private val dnsCache = ConcurrentHashMap<String, String>()
    private val usedPorts = Collections.synchronizedSet(HashSet<Int>())

    // ----------------------------------------------------------- reachability
    /** Usable HTTP response? 2xx/3xx, or 401/405 (server answered). 000/403/etc = no. */
    fun reachable(code: Int): Boolean {
        if (code == 401 || code == 405) return true
        return code in 200..399
    }

    // -------------------------------------------------------------- DNS + ping
    fun resolve(host: String): String? {
        if (host.isEmpty()) return null
        dnsCache[host]?.let { return it.ifEmpty { null } }
        val ip = try { InetAddress.getByName(host).hostAddress ?: "" } catch (e: Exception) { "" }
        dnsCache[host] = ip
        return ip.ifEmpty { null }
    }

    fun tcpPing(host: String, port: Int, timeoutMs: Int): Int? {
        val ip = resolve(host) ?: return null
        if (port <= 0) return null
        return try {
            val t0 = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (e: Exception) { null }
    }

    private fun freePort(): Int {
        repeat(50) {
            val p = ServerSocket(0).use { it.localPort }
            if (usedPorts.add(p)) return p
        }
        return ServerSocket(0).use { it.localPort }
    }

    /** Endpoint-deduped async TCP ping prefilter. Calls [onPing] for every node;
     *  returns the sorted reachable indices. */
    suspend fun pingFilter(
        nodes: List<Node>,
        settings: Settings,
        onPing: (Int, Int?) -> Unit,
        stop: () -> Boolean,
    ): List<Int> = coroutineScope {
        val groups = LinkedHashMap<Pair<String, Int>, MutableList<Int>>()
        nodes.forEachIndexed { i, n -> groups.getOrPut(n.server to n.port) { mutableListOf() }.add(i) }
        val reachable = Collections.synchronizedList(ArrayList<Int>())
        val sem = Semaphore(settings.pingConcurrency.coerceAtLeast(1))
        groups.entries.map { (ep, idxs) ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ms = if (stop()) null else tcpPing(ep.first, ep.second, settings.pingTimeoutSec * 1000)
                    for (i in idxs) {
                        if (ms != null) reachable.add(i)
                        onPing(i, ms)
                    }
                }
            }
        }.awaitAll()
        reachable.sorted()
    }

    // --------------------------------------------------------------- full test
    private fun socksClient(port: Int, timeoutSec: Int): OkHttpClient =
        OkHttpClient.Builder()
            // OkHttp does remote DNS for SOCKS proxies (resolves at the exit, not locally)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout((timeoutSec + 3).toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun waitReady(port: Int, proc: Process, timeoutMs: Int, stop: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stop()) return false
            if (!proc.isAlive) return false           // core crashed -> fail fast
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return true
            } catch (e: Exception) { Thread.sleep(40) }
        }
        return false
    }

    private fun reachCheck(client: OkHttpClient, url: String): Boolean = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", ua).build())
            .execute().use { reachable(it.code) }
    } catch (e: Exception) { false }

    private fun reachAll(port: Int, settings: Settings, stop: () -> Boolean): Map<String, Boolean> {
        if (settings.reachTargets.isEmpty()) return emptyMap()
        val rt = minOf(settings.timeoutSec, 7)
        val client = socksClient(port, rt)
        val out = LinkedHashMap<String, Boolean>()
        for (t in settings.reachTargets) {
            if (stop()) break
            out[t.code] = reachCheck(client, t.url)
        }
        return out
    }

    private fun geoLookup(port: Int, timeoutSec: Int): Pair<String, String> = try {
        socksClient(port, timeoutSec).newCall(Request.Builder().url(geoUrl).build())
            .execute().use { resp ->
                val data = JSONObject(resp.body?.string() ?: "{}")
                data.optString("query", "") to data.optString("countryCode", "")
            }
    } catch (e: Exception) { "" to "" }

    private fun killQuietly(proc: Process?) {
        if (proc == null) return
        try {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        } catch (e: Exception) { try { proc.destroyForcibly() } catch (_: Exception) {} }
    }

    /** One core spawn + latency request. Returns (result, retryable). */
    private fun proxyAttempt(node: Node, settings: Settings, stop: () -> Boolean): Pair<TestResult, Boolean> {
        val port = freePort()
        val cfgFile = File(workDir, "cfg_$port.json")
        val errFile = File(workDir, "err_$port.log")
        var proc: Process? = null
        try {
            cfgFile.writeText(XrayConfig.buildXrayConfig(node, port).toString())
            proc = ProcessBuilder(xrayPath, "run", "-c", cfgFile.absolutePath)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(errFile)
                .start()

            if (!waitReady(port, proc, settings.startTimeoutSec * 1000, stop)) {
                if (stop()) return TestResult(node, Status.ERROR, message = "stopped") to false
                val detail = errFile.readText().takeLast(300)
                return TestResult(
                    node, Status.FAILED,
                    message = "core did not start", detail = detail
                ) to proc.isAlive   // retry only if it was still alive (transient)
            }

            val client = socksClient(port, settings.timeoutSec)
            val t0 = System.nanoTime()
            try {
                client.newCall(Request.Builder().url(settings.testUrl).build()).execute().use { resp ->
                    val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                    if (resp.code == 204) {
                        val (ip, cc) = if (settings.geo) geoLookup(port, settings.timeoutSec) else "" to ""
                        val reach = if (settings.reachEnabled) reachAll(port, settings, stop) else emptyMap()
                        return TestResult(
                            node, Status.OK, latency = ms,
                            exitIp = ip, country = cc, reach = reach
                        ) to false
                    }
                    return TestResult(node, Status.FAILED, latency = ms, message = "HTTP ${resp.code}") to false
                }
            } catch (e: java.net.SocketTimeoutException) {
                return TestResult(node, Status.TIMEOUT, message = "timed out") to false
            } catch (e: IOException) {
                return TestResult(node, Status.TIMEOUT, message = "no response", detail = e.message ?: "") to false
            }
        } catch (e: Exception) {
            return TestResult(node, Status.ERROR, message = e.message ?: "error") to false
        } finally {
            killQuietly(proc)
            usedPorts.remove(port)
            cfgFile.delete()
            errFile.delete()
        }
    }

    /** Test a single node (never throws). [knownPing] reuses the prefilter ping. */
    fun testNode(node: Node, settings: Settings, stop: () -> Boolean, knownPing: Int? = null): TestResult {
        if (stop()) return TestResult(node, Status.ERROR, message = "stopped")
        val ping = knownPing ?: tcpPing(node.server, node.port, settings.pingTimeoutSec * 1000)
        if (!XrayConfig.isSupported(node.type)) {
            return TestResult(node, Status.UNSUPPORTED, message = "unsupported", tcpPing = ping)
        }
        var (res, retry) = proxyAttempt(node, settings, stop)
        if (retry && !stop()) res = proxyAttempt(node, settings, stop).first
        return res.copy(tcpPing = ping)
    }

    /** Test [indices] with bounded concurrency, streaming each result via [onResult]. */
    suspend fun runTests(
        nodes: List<Node>,
        settings: Settings,
        indices: List<Int>,
        pings: Map<Int, Int?>,
        onResult: (Int, TestResult) -> Unit,
        stop: () -> Boolean,
    ) = coroutineScope {
        val sem = Semaphore(settings.concurrency.coerceAtLeast(1))
        indices.map { idx ->
            async(Dispatchers.IO) {
                if (stop()) return@async
                sem.withPermit {
                    if (stop()) return@withPermit
                    onResult(idx, testNode(nodes[idx], settings, stop, pings[idx]))
                }
            }
        }.awaitAll()
    }
}
