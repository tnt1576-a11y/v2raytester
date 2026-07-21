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

    /** Fetch + decode a single subscription URL ('' on failure).
     *
     *  The body is read with a hard byte cap. Some aggregators are 50 MB+, and
     *  `body.string()` would materialise the whole thing as a Java String (UTF-16, so
     *  ~2x the byte size) — with several of those fetched in parallel that alone can
     *  OOM a phone. A few MB is already tens of thousands of configs. */
    fun fetchOne(url: String): String = try {
        http.newCall(
            Request.Builder().url(url).header("User-Agent", "v2raytester").build()
        ).execute().use { resp ->
            val body = resp.body
            if (body == null) "" else {
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(32 * 1024)
                body.byteStream().use { ins ->
                    while (out.size() < MAX_SUB_BYTES) {
                        val r = ins.read(chunk)
                        if (r <= 0) break
                        out.write(chunk, 0, minOf(r, MAX_SUB_BYTES - out.size()))
                    }
                }
                var text = out.toString("UTF-8")
                // A clipped base64 blob only decodes on a 4-char boundary; plain-text
                // subs just lose a partial trailing line, which fails to parse anyway.
                if (!text.contains("://")) {
                    val t = text.filterNot { it.isWhitespace() }
                    text = t.substring(0, t.length - t.length % 4)
                }
                ShareLinks.decodeSubscription(text).trim()
            }
        }
    } catch (e: Exception) { "" }

    companion object {
        /** Max bytes read from one subscription (see [fetchOne]). */
        const val MAX_SUB_BYTES = 6 * 1024 * 1024
    }

    private fun seed() {
        val text = try {
            context.assets.open("subs.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { "" }
        try { file.writeText(text) } catch (e: Exception) { /* ignore */ }
    }
}
