package com.wearos.ancsbridge.health

import android.content.Context
import org.json.JSONArray

/**
 * Workouts already write their own steps, distance, calories and heart rate to Apple Health.
 * All-day (passive) samples are trimmed to the time outside workouts so Health doesn't count
 * the same steps or calories twice.
 */
object WorkoutPeriods {
    private const val PREFS = "workout_periods"
    private const val KEEP_MS = 3L * 24 * 60 * 60 * 1000

    fun begin(context: Context, startMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("active", startMs).apply()
    }

    /** [synced] false = the workout was too short to send, so passive data keeps that time. */
    @Synchronized
    fun end(context: Context, startMs: Long, endMs: Long, synced: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = periods(context, includeActive = false).toMutableList()
        if (synced) list += startMs to endMs
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val json = JSONArray().apply {
            list.filter { it.second > cutoff }.forEach { put(JSONArray().put(it.first).put(it.second)) }
        }
        prefs.edit().putString("list", json.toString()).remove("active").apply()
    }

    fun periods(context: Context, includeActive: Boolean = true): List<Pair<Long, Long>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = runCatching {
            val array = JSONArray(prefs.getString("list", "[]"))
            (0 until array.length()).map { array.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
        }.getOrDefault(emptyList())
        val active = prefs.getLong("active", 0L)
        return if (includeActive && active > 0) list + (active to Long.MAX_VALUE) else list
    }

    /** Scales each interval by the share of its time outside [periods]. */
    fun outside(
        intervals: List<Triple<Long, Long, Double>>,
        periods: List<Pair<Long, Long>>,
    ): List<Triple<Long, Long, Double>> = intervals.mapNotNull { (start, end, amount) ->
        if (periods.isEmpty() || end <= start) return@mapNotNull Triple(start, end, amount)
        val covered = periods.sumOf { (ps, pe) -> (minOf(end, pe) - maxOf(start, ps)).coerceAtLeast(0) }
            .coerceAtMost(end - start)
        val share = 1.0 - covered.toDouble() / (end - start)
        if (share <= 0.01) null else Triple(start, end, amount * share)
    }

    fun pointsOutside(points: List<Pair<Long, Int>>, periods: List<Pair<Long, Long>>) =
        points.filter { (t, _) -> periods.none { (ps, pe) -> t in ps..pe } }

    /**
     * Splits calorie deltas (Health Services counts resting burn too) into active and resting
     * energy, which is what Apple Health and the Fitness app expect.
     */
    fun splitEnergy(
        calories: List<Triple<Long, Long, Double>>,
        restingPerMin: Double,
    ): Pair<List<Triple<Long, Long, Double>>, List<Triple<Long, Long, Double>>> {
        val active = mutableListOf<Triple<Long, Long, Double>>()
        val basal = mutableListOf<Triple<Long, Long, Double>>()
        for ((start, end, kcal) in calories) {
            val minutes = (end - start) / 60_000.0
            if (minutes <= 0 || kcal <= 0) continue
            val resting = (restingPerMin * minutes).coerceAtMost(kcal)
            if (resting > 0) basal += Triple(start, end, resting)
            if (kcal - resting > 0.05) active += Triple(start, end, kcal - resting)
        }
        return active to basal
    }
}
