package com.v2raytester.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.v2raytester.core.DEFAULT_REACH_TARGETS
import com.v2raytester.core.DEFAULT_URL
import com.v2raytester.core.ReachTarget
import com.v2raytester.core.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** Persists user [Settings] (test URL, timeouts, toggles, reachability targets). */
class Prefs(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("url")
        val TIMEOUT = intPreferencesKey("timeout")
        val CONC = intPreferencesKey("concurrency")
        val GEO = booleanPreferencesKey("geo")
        val PREFILTER = booleanPreferencesKey("prefilter")
        val REACH = booleanPreferencesKey("reach")
        val REACH_TARGETS = stringPreferencesKey("reach_targets")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            testUrl = p[Keys.URL] ?: DEFAULT_URL,
            timeoutSec = p[Keys.TIMEOUT] ?: 8,
            concurrency = p[Keys.CONC] ?: 6,
            geo = p[Keys.GEO] ?: true,
            prefilter = p[Keys.PREFILTER] ?: true,
            reachEnabled = p[Keys.REACH] ?: true,
            reachTargets = decodeTargets(p[Keys.REACH_TARGETS]),
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[Keys.URL] = s.testUrl
            p[Keys.TIMEOUT] = s.timeoutSec
            p[Keys.CONC] = s.concurrency
            p[Keys.GEO] = s.geo
            p[Keys.PREFILTER] = s.prefilter
            p[Keys.REACH] = s.reachEnabled
            p[Keys.REACH_TARGETS] = encodeTargets(s.reachTargets)
        }
    }

    private fun encodeTargets(targets: List<ReachTarget>): String {
        val arr = JSONArray()
        targets.forEach { arr.put(JSONArray().put(it.code).put(it.url)) }
        return arr.toString()
    }

    private fun decodeTargets(json: String?): List<ReachTarget> {
        if (json.isNullOrBlank()) return DEFAULT_REACH_TARGETS
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val t = arr.getJSONArray(it)
                ReachTarget(t.getString(0), t.getString(1))
            }.ifEmpty { DEFAULT_REACH_TARGETS }
        } catch (e: Exception) { DEFAULT_REACH_TARGETS }
    }
}
