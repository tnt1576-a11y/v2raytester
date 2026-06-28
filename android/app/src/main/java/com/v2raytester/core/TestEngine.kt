package com.v2raytester.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test engine — Kotlin port of tester.py for Android.
 *
 * @param xrayPath absolute path to the bundled xray binary
 *   (`applicationInfo.nativeLibraryDir + "/libxray.so"`).
 * @param workDir a writable dir for per-test config/log files (`cacheDir`).
 *
 * Pure java / OkHttp / coroutines (no android.* imports), so the logic stays
 * unit-testable on the JVM.
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

    /** Endpoint-deduped TCP-connect prefilter. Calls [onPing] for every node;
     *  returns the sorted reachable indices.
     *
     *  Non-blocking NIO: up to pingConcurrency connects are in flight at once on a
     *  single selector thread. The old version used blocking connects, so it needed a
     *  thread per connect — Dispatchers.IO's ~64-thread ceiling silently capped real
     *  concurrency at 64, and a run of dead/timing-out endpoints (each holding its
     *  thread for the full timeout) stalled the whole sweep ("fast at first, then slow"). */
    suspend fun pingFilter(
        nodes: List<Node>,
        settings: Settings,
        onPing: (Int, Int?) -> Unit,
        stop: () -> Boolean,
    ): List<Int> = coroutineScope {
        val groups = LinkedHashMap<Pair<String, Int>, MutableList<Int>>()
        nodes.forEachIndexed { i, n -> groups.getOrPut(n.server to n.port) { mutableListOf() }.add(i) }
        val groupList = groups.entries.toList()
        val reachable = Collections.synchronizedList(ArrayList<Int>())

        // finish() runs only on the single selector thread below, so the caller's
        // per-index bookkeeping (onPing) is never invoked concurrently.
        fun finish(gi: Int, ms: Int?) {
            for (i in groupList[gi].value) {
                if (ms != null) reachable.add(i)
                onPing(i, ms)
            }
        }

        // DNS (blocking getByName) runs CONCURRENTLY with the connect scan, feeding
        // resolved endpoints into a queue, so connects start as soon as the first names
        // resolve instead of waiting for the whole list — DNS and connects overlap.
        val addrs = arrayOfNulls<InetSocketAddress>(groupList.size)
        val resolvedQ = ConcurrentLinkedQueue<Int>()        // group indices ready to connect
        val produced = AtomicInteger(0)
        val dnsDisp = Dispatchers.IO.limitedParallelism(128)
        val resolverJob = launch {
            groupList.mapIndexed { gi, e ->
                async(dnsDisp) {
                    if (!stop()) {
                        val ip = resolve(e.key.first)
                        if (ip != null && e.key.second in 1..65535) {
                            try { addrs[gi] = InetSocketAddress(InetAddress.getByName(ip), e.key.second) } catch (_: Exception) {}
                        }
                    }
                    resolvedQ.add(gi); produced.incrementAndGet()
                }
            }.awaitAll()
        }

        // Single-thread NIO selector loop: up to pingConcurrency connects in flight.
        withContext(Dispatchers.IO) {
            val timeoutNs = settings.pingTimeoutSec.coerceAtLeast(1) * 1_000_000_000L
            val maxInFlight = settings.pingConcurrency.coerceAtLeast(1)
            val selector = Selector.open()
            val inflight = HashMap<SelectionKey, Triple<Int, Long, SocketChannel>>()
            try {
                while (true) {
                    if (stop()) break
                    // start connects for any resolved endpoints, up to the in-flight cap
                    while (inflight.size < maxInFlight) {
                        val gi = resolvedQ.poll() ?: break
                        val addr = addrs[gi]
                        if (addr == null) { finish(gi, null); continue }
                        try {
                            val ch = SocketChannel.open()
                            ch.configureBlocking(false)
                            val key = ch.register(selector, SelectionKey.OP_CONNECT)
                            if (ch.connect(addr)) {          // connected immediately (e.g. loopback)
                                key.cancel(); try { ch.close() } catch (_: Exception) {}
                                finish(gi, 0)
                            } else {
                                inflight[key] = Triple(gi, System.nanoTime(), ch)
                            }
                        } catch (e: Exception) { finish(gi, null) }
                    }
                    if (inflight.isEmpty() && resolvedQ.isEmpty()) {
                        if (produced.get() >= groupList.size) break   // all resolved + all connected
                        Thread.sleep(15); continue                    // briefly wait for DNS to produce
                    }
                    if (inflight.isEmpty()) continue                  // queue has items: loop to connect them

                    selector.select(200)
                    val sel = selector.selectedKeys().iterator()
                    while (sel.hasNext()) {
                        val key = sel.next(); sel.remove()
                        val tri = inflight.remove(key) ?: continue
                        var ms: Int? = null
                        try { if (tri.third.finishConnect()) ms = ((System.nanoTime() - tri.second) / 1_000_000).toInt() }
                        catch (e: Exception) {}
                        try { tri.third.close() } catch (_: Exception) {}
                        key.cancel()
                        finish(tri.first, ms)
                    }
                    if (inflight.isNotEmpty()) {
                        val now = System.nanoTime()
                        val expired = inflight.entries.filter { now - it.value.second >= timeoutNs }.map { it.key }
                        for (k in expired) {
                            val tri = inflight.remove(k)!!
                            try { tri.third.close() } catch (_: Exception) {}
                            k.cancel()
                            finish(tri.first, null)
                        }
                    }
                }
                // stopped mid-scan: mark whatever was still in flight as unreachable
                for ((k, tri) in inflight) {
                    try { tri.third.close() } catch (_: Exception) {}
                    k.cancel(); finish(tri.first, null)
                }
            } finally {
                try { selector.close() } catch (_: Exception) {}
            }
        }
        resolverJob.cancel()
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

    /** One latency sample through the proxy. Returns (httpCode, ms); code 0 = timeout/IO. */
    private fun httpSample(client: OkHttpClient, url: String): Pair<Int, Int?> = try {
        val t0 = System.nanoTime()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            resp.code to ((System.nanoTime() - t0) / 1_000_000).toInt()
        }
    } catch (e: java.net.SocketTimeoutException) { 0 to null
    } catch (e: IOException) { 0 to null }

    internal fun median(xs: List<Int>): Int {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** One core spawn + latency request. In [refine] mode it takes several latency
     *  samples (median) and runs geo + Sites; otherwise it's a single fast 204 check
     *  (no geo/Sites). Returns (result, retryable). */
    private fun proxyAttempt(node: Node, settings: Settings, stop: () -> Boolean, refine: Boolean): Pair<TestResult, Boolean> {
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

            // Pass A uses the shorter connectivity timeout; the refine pass uses the full one.
            val client = socksClient(port, if (refine) settings.timeoutSec else settings.connectTimeoutSec)
            val (code, ms0) = httpSample(client, settings.testUrl)
            when {
                code == 204 -> {
                    var latency = ms0
                    if (refine && settings.latencySamples > 1) {
                        val samples = ArrayList<Int>()
                        ms0?.let { samples.add(it) }
                        repeat(settings.latencySamples - 1) {
                            if (!stop()) {
                                val (c, m) = httpSample(client, settings.testUrl)
                                if (c == 204 && m != null) samples.add(m)
                            }
                        }
                        if (samples.isNotEmpty()) latency = median(samples)
                    }
                    if (!refine) return TestResult(node, Status.OK, latency = latency) to false
                    // geo is best-effort, bounded by the low refine concurrency (no global
                    // throttle — exit IPs vary, so ip-api's per-IP limit is rarely an issue).
                    val (ip, cc) = if (settings.geo) geoLookup(port, settings.timeoutSec) else "" to ""
                    val reach = if (settings.reachEnabled) reachAll(port, settings, stop) else emptyMap()
                    return TestResult(node, Status.OK, latency = latency, exitIp = ip, country = cc, reach = reach) to false
                }
                code == 0 -> return TestResult(node, Status.TIMEOUT, message = "no response") to false
                else -> return TestResult(node, Status.FAILED, latency = ms0, message = "HTTP $code") to false
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

    /** Pass A: fast connectivity check (single 204, no geo/Sites). [knownPing] reuses the prefilter ping. */
    fun testConnect(node: Node, settings: Settings, stop: () -> Boolean, knownPing: Int? = null): TestResult {
        if (stop()) return TestResult(node, Status.ERROR, message = "stopped")
        val ping = knownPing ?: tcpPing(node.server, node.port, settings.pingTimeoutSec * 1000)
        if (!XrayConfig.isSupported(node.type)) {
            return TestResult(node, Status.UNSUPPORTED, message = "unsupported", tcpPing = ping)
        }
        var (res, retry) = proxyAttempt(node, settings, stop, refine = false)
        if (retry && !stop()) res = proxyAttempt(node, settings, stop, refine = false).first
        return res.copy(tcpPing = ping)
    }

    /** Pass B: accurate re-measure of a working node — median latency + geo + Sites. */
    fun refineNode(node: Node, settings: Settings, stop: () -> Boolean, knownPing: Int? = null): TestResult =
        proxyAttempt(node, settings, stop, refine = true).first.copy(tcpPing = knownPing)

    /** Full single test (connectivity + refine in one) — used for manual retest. */
    fun testNode(node: Node, settings: Settings, stop: () -> Boolean, knownPing: Int? = null): TestResult {
        if (stop()) return TestResult(node, Status.ERROR, message = "stopped")
        val ping = knownPing ?: tcpPing(node.server, node.port, settings.pingTimeoutSec * 1000)
        if (!XrayConfig.isSupported(node.type)) {
            return TestResult(node, Status.UNSUPPORTED, message = "unsupported", tcpPing = ping)
        }
        var (res, retry) = proxyAttempt(node, settings, stop, refine = true)
        if (retry && !stop()) res = proxyAttempt(node, settings, stop, refine = true).first
        return res.copy(tcpPing = ping)
    }

    /**
     * Two-pass test of [indices]:
     *  - Pass A (concurrency): fast connectivity check, streamed via [onResult] (refined=false).
     *    The working set is collected.
     *  - Pass B (refineConcurrency): re-measure the working subset for accurate latency + geo +
     *    Sites, streamed via [onResult] (refined=true). [onRefineStart] fires with the working
     *    count between the passes.
     */
    suspend fun runTests(
        nodes: List<Node>,
        settings: Settings,
        indices: List<Int>,
        pings: Map<Int, Int?>,
        onResult: (Int, TestResult, Boolean) -> Unit,
        onRefineStart: (Int) -> Unit,
        stop: () -> Boolean,
    ) = coroutineScope {
        // Pass A — connectivity at the chosen (high) concurrency. limitedParallelism is
        // required because each test blocks a thread (xray spawn) and Dispatchers.IO caps at ~64.
        val working = Collections.synchronizedList(ArrayList<Int>())
        val dispA = Dispatchers.IO.limitedParallelism(settings.concurrency.coerceAtLeast(1))
        indices.map { idx ->
            async(dispA) {
                if (stop()) return@async
                val r = testConnect(nodes[idx], settings, stop, pings[idx])
                if (r.status == Status.OK) working.add(idx)
                onResult(idx, r, false)
            }
        }.awaitAll()

        // Pass B — refine the working subset at LOW concurrency for clean, accurate latency.
        if (stop()) return@coroutineScope
        val workIdx = working.sorted()
        onRefineStart(workIdx.size)
        if (workIdx.isEmpty()) return@coroutineScope
        val dispB = Dispatchers.IO.limitedParallelism(settings.refineConcurrency.coerceAtLeast(1))
        workIdx.map { idx ->
            async(dispB) {
                if (stop()) return@async
                onResult(idx, refineNode(nodes[idx], settings, stop, pings[idx]), true)
            }
        }.awaitAll()
    }
}
