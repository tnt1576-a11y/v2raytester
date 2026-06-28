package com.v2raytester.data

import android.content.Context
import com.v2raytester.core.ShareLinks
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Subscription URL storage + fetch. The URL list lives in filesDir/subs.txt,
 * seeded from assets/subs.txt on first run. Iteration/progress/single-flight is
 * handled by the ViewModel; this just stores URLs and fetches one at a time.
 */
class Subscriptions(private val context: Context, private val http: OkHttpClient) {

    private val file = File(context.filesDir, "subs.txt")

    fun urlsText(): String {
        if (!file.exists()) seed()
        return try { file.readText() } catch (e: Exception) { "" }
    }

    fun saveUrlsText(text: String) {
        try { file.writeText(text) } catch (e: Exception) { /* ignore */ }
    }

    fun urls(): List<String> = urlsText().lineSequence()
        .map { it.trim() }
        // match the editor's display filter (TesterViewModel) + stripComments: skip both # and //
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        .toList()

    /** Fetch + decode a single subscription URL ('' on failure). */
    fun fetchOne(url: String): String = try {
        http.newCall(
            Request.Builder().url(url).header("User-Agent", "v2raytester").build()
        ).execute().use { resp ->
            ShareLinks.decodeSubscription(resp.body?.string() ?: "").trim()
        }
    } catch (e: Exception) { "" }

    private fun seed() {
        val text = try {
            context.assets.open("subs.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { "" }
        try { file.writeText(text) } catch (e: Exception) { /* ignore */ }
    }
}
