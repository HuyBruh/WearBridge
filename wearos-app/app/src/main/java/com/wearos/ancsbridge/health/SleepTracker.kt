package com.wearos.ancsbridge.health

import android.content.Context
import org.json.JSONArray

/**
 * Sleep and resting heart rate from Health Services' passive user-activity state
 * (ASLEEP / PASSIVE / EXERCISE) and the passive heart-rate stream.
 */
object SleepTracker {
    private const val PREFS = "sleep_tracker"
    private const val MIN_SLEEP_MS = 20 * 60_000L
    private const val BUFFER_MS = 36 * 3_600_000L

    /** Keeps ~36 h of heart rate (≥ 1 min apart) to compute resting heart rate after sleep. */
    @Synchronized
    fun addHeartRate(context: Context, samples: List<Pair<Long, Int>>) {
        if (samples.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val buffer = decode(prefs.getString("hr", null)).toMutableList()
        for (sample in samples.sortedBy { it.first }) {
            if (buffer.isEmpty() || sample.first - buffer.last().first >= 60_000) buffer += sample
        }
        val cutoff = System.currentTimeMillis() - BUFFER_MS
        prefs.edit().putString("hr", encode(buffer.filter { it.first >= cutoff })).apply()
    }

    /**
     * Called on every activity-state change. Returns a finished sleep interval and the
     * resting heart rate measured during it, or null while still asleep / too short.
     */
    @Synchronized
    fun onState(context: Context, asleep: Boolean, atMs: Long): Pair<Pair<Long, Long>, Int?>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong("asleep_since", 0L)
        if (asleep) {
            if (start == 0L) prefs.edit().putLong("asleep_since", atMs).apply()
            return null
        }
        if (start == 0L) return null
        prefs.edit().remove("asleep_since").apply()
        if (atMs - start < MIN_SLEEP_MS) return null
        val during = decode(prefs.getString("hr", null)).filter { it.first in start..atMs }.map { it.second }
        return (start to atMs) to restingHeartRate(during)
    }

    /** Mean of the lowest 30% of readings (at least 5), like a resting estimate. */
    fun restingHeartRate(bpm: List<Int>): Int? {
        if (bpm.size < 5) return null
        val lowest = bpm.sorted().take((bpm.size * 0.3).toInt().coerceAtLeast(5))
        return lowest.average().toInt()
    }

    private fun encode(list: List<Pair<Long, Int>>) =
        JSONArray().apply { list.forEach { put(JSONArray().put(it.first).put(it.second)) } }.toString()

    private fun decode(json: String?): List<Pair<Long, Int>> = runCatching {
        val array = JSONArray(json ?: return emptyList())
        (0 until array.length()).map { array.getJSONArray(it).let { p -> p.getLong(0) to p.getInt(1) } }
    }.getOrDefault(emptyList())
}
