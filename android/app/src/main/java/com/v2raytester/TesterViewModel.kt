package com.v2raytester

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2raytester.core.Node
import com.v2raytester.core.Settings
import com.v2raytester.core.ShareLinks
import com.v2raytester.core.Status
import com.v2raytester.core.TestEngine
import com.v2raytester.core.TestResult
import com.v2raytester.data.Prefs
import com.v2raytester.data.Subscriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TesterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val subHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val subs = Subscriptions(app, subHttp)
    private val engine = TestEngine(
        xrayPath = app.applicationInfo.nativeLibraryDir + "/libxray.so",
        workDir = app.cacheDir,
    )
    private val workFile = File(app.filesDir, "working.txt")

    // ---- UI state ----
    val configText = mutableStateOf("")
    val settings = mutableStateOf(Settings())
    val nodes = mutableStateListOf<Node>()
    val results = mutableStateMapOf<Int, TestResult>()
    val allOrder: SnapshotStateList<Int> = mutableStateListOf()
    val workingOrder: SnapshotStateList<Int> = mutableStateListOf()

    val status = mutableStateOf("")
    val phase = mutableStateOf("idle")          // idle | ping | test
    val progress = mutableStateOf(0f)
    val testing = mutableStateOf(false)

    val subUrlsText = mutableStateOf("")
    val subStatus = mutableStateOf("")
    val subProgress = mutableStateOf(0f)
    val fetching = mutableStateOf(false)

    private var stopFlag = AtomicBoolean(false)
    private var runJob: Job? = null
    private var fetchGen = 0

    // counters (mutated only on the single Main consumer)
    private var pingDone = 0; private var pingReach = 0; private var pingTotal = 0
    private var testDone = 0; private var testTotal = 0; private var okCount = 0; private var skipped = 0

    private sealed class Ev {
        data class Ping(val idx: Int, val ms: Int?) : Ev()
        data class Result(val idx: Int, val r: TestResult) : Ev()
    }
    private val events = Channel<Ev>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.Main) {
            settings.value = prefs.settings.first()
            subUrlsText.value = subs.urlsText()
        }
        // single consumer applies engine events to Compose state safely
        viewModelScope.launch(Dispatchers.Main) {
            for (e in events) applyEvent(e)
        }
    }

    // ------------------------------------------------------------- input ops
    fun setConfigText(t: String) { configText.value = t }

    private fun setLinks(text: String, append: Boolean) {
        val cleaned = ShareLinks.stripComments(text)
        configText.value = if (append && configText.value.isNotBlank())
            configText.value.trimEnd('\n') + "\n" + cleaned else cleaned
    }

    /** Append configs (e.g. from a picked file), stripping comment lines. */
    fun appendConfigs(text: String) { setLinks(text, append = true); parse() }

    /** Parse the box; updates the counts line. Returns parsed (post-dedupe) nodes. */
    fun parse(): List<Node> {
        val (parsed, errors) = ShareLinks.parseLinks(configText.value)
        val (unique, removed) = ShareLinks.dedupe(parsed)
        nodes.clear(); nodes.addAll(unique)
        var t = "${unique.size} configs · $removed dupes"
        if (errors.isNotEmpty()) t += " · ${errors.size} incompatible"
        status.value = t
        return unique
    }

    fun removeDuplicates() {
        val (parsed, errors) = ShareLinks.parseLinks(configText.value)
        val (unique, removed) = ShareLinks.dedupe(parsed)
        configText.value = unique.joinToString("\n") { it.raw }
        var t = "removed $removed duplicate" + (if (removed == 1) "" else "s")
        if (errors.isNotEmpty()) t += " · dropped ${errors.size} incompatible"
        status.value = "$t · ${unique.size} configs"
    }

    fun clear() {
        configText.value = ""; nodes.clear(); results.clear()
        allOrder.clear(); workingOrder.clear(); status.value = ""
    }

    fun updateSettings(s: Settings) {
        settings.value = s
        viewModelScope.launch(Dispatchers.IO) { prefs.save(s) }
    }

    // ------------------------------------------------------------- testing
    fun startTests() {
        if (testing.value) return
        val ns = parse()
        if (ns.isEmpty()) { status.value = "no configs to test"; return }
        results.clear(); allOrder.clear(); workingOrder.clear()
        pingDone = 0; pingReach = 0; testDone = 0; okCount = 0; skipped = 0
        stopFlag = AtomicBoolean(false)
        testing.value = true
        openWorkFile()

        runJob = viewModelScope.launch(Dispatchers.Default) {
            val s = settings.value
            val stop = { stopFlag.get() }
            val pings = HashMap<Int, Int?>()
            val indices: List<Int>

            if (s.prefilter) {
                phase.value = "ping"; pingTotal = ns.size; progress.value = 0f
                val reachable = engine.pingFilter(
                    ns, s,
                    onPing = { i, ms -> pings[i] = ms; events.trySend(Ev.Ping(i, ms)) },
                    stop = stop,
                )
                indices = reachable
                skipped = ns.size - reachable.size
            } else {
                indices = ns.indices.toList()
                indices.forEach { pings[it] = null }
            }
            // seed tested rows as pending
            testTotal = indices.size; testDone = 0
            phase.value = "test"
            indices.forEach { i ->
                results[i] = TestResult(ns[i], Status.PENDING, tcpPing = pings[i])
                if (!allOrder.contains(i)) allOrder.add(i)
            }
            engine.runTests(
                ns, s, indices, pings,
                onResult = { i, r -> events.trySend(Ev.Result(i, r)) },
                stop = stop,
            )
            finishRun()
        }
    }

    fun stop() {
        stopFlag.set(true)
        runJob?.cancel()
        status.value = "stopping…"
    }

    fun retest(idx: Int) {
        if (testing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val r = engine.testNode(nodes[idx], settings.value, { false }, knownPing = null)
            events.trySend(Ev.Result(idx, r))
        }
    }

    fun deleteRow(idx: Int) {
        results.remove(idx); allOrder.remove(idx); workingOrder.remove(idx)
    }

    private fun finishRun() {
        testing.value = false
        phase.value = "idle"
        rewriteWorkingSorted()
        updateCounts()
    }

    // ------------------------------------------------------- event handling
    private fun applyEvent(e: Ev) {
        when (e) {
            is Ev.Ping -> {
                pingDone++; if (e.ms != null) pingReach++
                results[e.idx]?.let { results[e.idx] = it.copy(tcpPing = e.ms) }
                if (phase.value == "ping") {
                    progress.value = pingDone.toFloat() / pingTotal.coerceAtLeast(1)
                    status.value = "pinging $pingDone/$pingTotal · $pingReach reachable"
                }
            }
            is Ev.Result -> {
                results[e.idx] = e.r
                if (!allOrder.contains(e.idx)) allOrder.add(e.idx)
                testDone++
                if (e.r.status == Status.OK) {
                    okCount++
                    if (!workingOrder.contains(e.idx)) {
                        workingOrder.add(e.idx)
                        appendWorkFile(e.r.node.raw)
                    }
                } else if (workingOrder.contains(e.idx)) {
                    workingOrder.remove(e.idx)   // retest flipped to failure
                }
                if (phase.value == "test") progress.value = testDone.toFloat() / testTotal.coerceAtLeast(1)
                updateCounts()
            }
        }
    }

    private fun updateCounts() {
        var t = "✓ $okCount online · $testDone/$testTotal tested"
        if (skipped > 0) t += " · $skipped skipped"
        status.value = t
    }

    // --------------------------------------------------------------- sorting
    fun sort(order: SnapshotStateList<Int>, key: String) {
        val cmp = Comparator<Int> { a, b ->
            val ra = results[a]; val rb = results[b]
            when (key) {
                "latency" -> (ra?.latency ?: Int.MAX_VALUE).compareTo(rb?.latency ?: Int.MAX_VALUE)
                "ping" -> (ra?.tcpPing ?: Int.MAX_VALUE).compareTo(rb?.tcpPing ?: Int.MAX_VALUE)
                "type" -> (ra?.node?.type ?: "").compareTo(rb?.node?.type ?: "")
                "tag" -> (ra?.node?.tag ?: "").compareTo(rb?.node?.tag ?: "")
                else -> a.compareTo(b)
            }
        }
        val sorted = order.sortedWith(cmp)
        order.clear(); order.addAll(sorted)
    }

    // ------------------------------------------------------- working output
    fun workingLinks(): String =
        workingOrder.sortedBy { results[it]?.latency ?: Int.MAX_VALUE }
            .mapNotNull { results[it]?.node?.raw }
            .joinToString("\n")

    private fun openWorkFile() = try { workFile.writeText("") } catch (e: Exception) {}
    private fun appendWorkFile(raw: String) = try {
        if (raw.isNotEmpty()) workFile.appendText(raw + "\n")
    } catch (e: Exception) {}

    private fun rewriteWorkingSorted() = try {
        workFile.writeText(workingLinks().let { if (it.isEmpty()) "" else it + "\n" })
    } catch (e: Exception) {}

    // ------------------------------------------------------- subscriptions
    fun saveSubUrls(text: String) { subUrlsText.value = text; subs.saveUrlsText(text) }

    fun fetchSubscriptions() {
        val urls = subs.urls()
        if (urls.isEmpty()) { subStatus.value = "no URLs"; return }
        subs.saveUrlsText(subUrlsText.value)
        fetchGen++; val gen = fetchGen
        fetching.value = true; subProgress.value = 0f
        viewModelScope.launch(Dispatchers.IO) {
            val gathered = StringBuilder(); var ok = 0; var configs = 0
            for ((i, u) in urls.withIndex()) {
                if (gen != fetchGen) return@launch
                val links = subs.fetchOne(u)
                if (links.isNotEmpty()) {
                    gathered.append(links).append("\n"); ok++
                    configs += links.count { it == '\n' } + 1
                }
                val done = i + 1
                viewModelScope.launch(Dispatchers.Main) {
                    if (gen == fetchGen) {
                        subProgress.value = done.toFloat() / urls.size
                        subStatus.value = "$done/${urls.size} fetched · $ok ok · $configs configs"
                    }
                }
            }
            if (gen == fetchGen) viewModelScope.launch(Dispatchers.Main) {
                setLinks(gathered.toString(), append = false)
                parse()
                fetching.value = false
            }
        }
    }

    fun abortFetch() { fetchGen++; fetching.value = false; subStatus.value = "aborted" }
}
