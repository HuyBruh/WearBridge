package com.wearos.ancsbridge.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today's Move / Exercise / Stand rings, Apple Watch style. */
data class Rings(
    val moveKcal: Int = 0,
    val moveGoal: Int = ActivityTracker.DEFAULT_MOVE_GOAL,
    val exerciseMin: Int = 0,
    val exerciseGoal: Int = ActivityTracker.DEFAULT_EXERCISE_GOAL,
    val standHours: Int = 0,
    val standGoal: Int = ActivityTracker.DEFAULT_STAND_GOAL,
    val steps: Int = 0,
    /** False until passive monitoring has delivered data today. */
    val hasData: Boolean = false,
) {
    val move: Float get() = moveKcal / moveGoal.toFloat()
    val exercise: Float get() = exerciseMin / exerciseGoal.toFloat()
    val stand: Float get() = standHours / standGoal.toFloat()
}

/**
 * One day of activity, computed only from what Health Services exposes:
 * - Move: active kcal = CALORIES_DAILY (which includes resting burn) minus a resting rate
 *   learned from quiet periods (few steps), so it self-calibrates to the wearer.
 * - Exercise: workout minutes, plus brisk walking (≥100 steps/min) outside workouts.
 * - Stand: hours with at least [STAND_STEPS] steps (moving around in that hour).
 */
data class ActivityDay(
    val date: String,
    val totalKcal: Double = 0.0,
    val lastCalEndMs: Long = 0,
    val exerciseMin: Double = 0.0,
    val hourSteps: List<Int> = List(24) { 0 },
    val workouts: List<Pair<Long, Long>> = emptyList(),
) {
    fun withSteps(deltas: List<Triple<Long, Long, Double>>, zone: ZoneId): ActivityDay {
        val hours = hourSteps.toMutableList()
        var exercise = exerciseMin
        for ((start, end, count) in deltas) {
            val hour = Instant.ofEpochMilli(end).atZone(zone)
            if (hour.toLocalDate().toString() != date) continue
            hours[hour.hour] += count.toInt()
            val minutes = (end - start) / 60_000.0
            val brisk = minutes > 0 && minutes <= 30 && count / minutes >= ActivityTracker.BRISK_CADENCE
            val inWorkout = workouts.any { (ws, we) -> start < we && end > ws }
            if (brisk && !inWorkout) exercise += minutes
        }
        return copy(hourSteps = hours, exerciseMin = exercise)
    }

    fun withWorkout(startMs: Long, endMs: Long, activeMs: Long) =
        copy(exerciseMin = exerciseMin + activeMs / 60_000.0, workouts = workouts + (startMs to endMs))

    fun activeKcal(restingPerMin: Double, zone: ZoneId): Double {
        if (lastCalEndMs == 0L) return 0.0
        val midnight = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
        val minutes = (lastCalEndMs - midnight) / 60_000.0
        return (totalKcal - restingPerMin * minutes).coerceAtLeast(0.0)
    }

    val standHours: Int get() = hourSteps.count { it >= ActivityTracker.STAND_STEPS }
    val steps: Int get() = hourSteps.sum()

    fun toJson(): JSONObject = JSONObject()
        .put("date", date).put("kcal", totalKcal).put("calEnd", lastCalEndMs).put("exercise", exerciseMin)
        .put("hours", JSONArray(hourSteps))
        .put("workouts", JSONArray().apply { workouts.forEach { put(JSONArray().put(it.first).put(it.second)) } })

    companion object {
        fun fromJson(json: JSONObject) = ActivityDay(
            date = json.getString("date"),
            totalKcal = json.optDouble("kcal", 0.0),
            lastCalEndMs = json.optLong("calEnd", 0),
            exerciseMin = json.optDouble("exercise", 0.0),
            hourSteps = json.optJSONArray("hours")?.let { a -> List(24) { a.optInt(it, 0) } } ?: List(24) { 0 },
            workouts = json.optJSONArray("workouts")?.let { a ->
                (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
            } ?: emptyList(),
        )
    }
}

object ActivityTracker {
    const val DEFAULT_MOVE_GOAL = 400
    const val DEFAULT_EXERCISE_GOAL = 30
    const val DEFAULT_STAND_GOAL = 12
    const val STAND_STEPS = 60
    const val BRISK_CADENCE = 100.0
    /** Resting burn before calibration: ~1,440 kcal/day. */
    private const val DEFAULT_RESTING_PER_MIN = 1.0

    private const val PREFS = "activity"
    private val _rings = MutableStateFlow(Rings())
    val rings: StateFlow<Rings> = _rings.asStateFlow()

    /** Called after every change so the tile and complication refresh. */
    var onChanged: ((Context) -> Unit)? = null

    private fun zone() = ZoneId.systemDefault()

    @Synchronized
    fun today(context: Context): ActivityDay {
        val todayKey = LocalDate.now(zone()).toString()
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("day", null)
            ?.let { runCatching { ActivityDay.fromJson(JSONObject(it)) }.getOrNull() }
        return if (stored?.date == todayKey) stored else ActivityDay(todayKey)
    }

    @Synchronized
    fun ringsNow(context: Context): Rings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today(context)
        val resting = prefs.getFloat("resting", DEFAULT_RESTING_PER_MIN.toFloat()).toDouble()
        return Rings(
            moveKcal = day.activeKcal(resting, zone()).toInt(),
            moveGoal = prefs.getInt("moveGoal", DEFAULT_MOVE_GOAL),
            exerciseMin = day.exerciseMin.toInt(),
            exerciseGoal = prefs.getInt("exerciseGoal", DEFAULT_EXERCISE_GOAL),
            standHours = day.standHours,
            standGoal = prefs.getInt("standGoal", DEFAULT_STAND_GOAL),
            steps = day.steps,
            hasData = day.lastCalEndMs > 0 || day.steps > 0 || day.exerciseMin > 0,
        ).also { _rings.value = it }
    }

    /** Learned resting burn, kcal per minute. */
    fun restingPerMin(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat("resting", DEFAULT_RESTING_PER_MIN.toFloat()).toDouble()

    @Synchronized
    fun onPassiveData(
        context: Context,
        stepDeltas: List<Triple<Long, Long, Double>>,
        calories: List<Triple<Long, Long, Double>>,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var day = today(context).withSteps(stepDeltas, zone())
        var resting = prefs.getFloat("resting", DEFAULT_RESTING_PER_MIN.toFloat()).toDouble()
        for ((dayStart, end, total) in calories.sortedBy { it.second }) {
            val pointDay = Instant.ofEpochMilli(dayStart).atZone(zone()).toLocalDate().toString()
            if (pointDay != day.date || end <= day.lastCalEndMs) continue
            if (day.lastCalEndMs > 0) {
                resting = learnResting(resting, day.lastCalEndMs, end, total - day.totalKcal, stepDeltas)
            }
            day = day.copy(totalKcal = total, lastCalEndMs = end)
        }
        prefs.edit().putString("day", day.toJson().toString()).putFloat("resting", resting.toFloat()).apply()
        ringsNow(context)
        onChanged?.invoke(context)
    }

    /**
     * Quiet stretches (≥ 20 min, under 1 step/min) reveal the wearer's resting burn.
     * Lower readings pull the estimate down quickly, higher ones only slowly.
     */
    internal fun learnResting(
        current: Double, fromMs: Long, toMs: Long, kcal: Double, steps: List<Triple<Long, Long, Double>>
    ): Double {
        val minutes = (toMs - fromMs) / 60_000.0
        if (minutes < 20 || kcal <= 0) return current
        val stepsInside = steps.filter { it.second > fromMs && it.first < toMs }.sumOf { it.third }
        if (stepsInside > minutes) return current
        val candidate = (kcal / minutes).coerceIn(0.6, 2.0)
        val weight = if (candidate < current) 0.3 else 0.05
        return current * (1 - weight) + candidate * weight
    }

    @Synchronized
    fun onWorkout(context: Context, startMs: Long, endMs: Long, activeMs: Long) {
        val day = today(context)
        val startDay = Instant.ofEpochMilli(startMs).atZone(zone()).toLocalDate().toString()
        if (startDay != day.date) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("day", day.withWorkout(startMs, endMs, activeMs).toJson().toString()).apply()
        ringsNow(context)
        onChanged?.invoke(context)
    }

    fun setGoals(context: Context, move: Int, exercise: Int, stand: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("moveGoal", move.coerceIn(50, 2000))
            .putInt("exerciseGoal", exercise.coerceIn(5, 180))
            .putInt("standGoal", stand.coerceIn(1, 24)).apply()
        ringsNow(context)
        onChanged?.invoke(context)
    }
}
