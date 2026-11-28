package com.wearos.ancsbridge.health

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A finished workout, in the JSON shape the iPhone app imports into HealthKit. */
data class WorkoutRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: WorkoutKind,
    val startMs: Long,
    val endMs: Long,
    val activeMs: Long,
    val kcal: Double?,
    val distanceM: Double?,
    val steps: Long?,
    /** (epoch ms, bpm) */
    val heartRate: List<Pair<Long, Int>>,
    /** (epoch ms, lat, lon, altitude or NaN) */
    val route: List<RoutePoint>,
    /** (pause start ms, resume ms) */
    val pauses: List<Pair<Long, Long>>,
    val goal: WorkoutGoal = WorkoutGoal.OPEN,
    /** Per-kilometre splits: (wall-clock ms at the split, active ms that km took). */
    val splits: List<Pair<Long, Long>> = emptyList(),
    /** Wall-clock ms of each segment the user marked. */
    val segments: List<Long> = emptyList(),
    /** Estimated VO2 max (mL/kg·min), when the watch reports it for runs. */
    val vo2Max: Double? = null,
    /** Running form averages: ground contact (ms), vertical oscillation (cm), stride (m). */
    val groundContactMs: Double? = null,
    val verticalOscillationCm: Double? = null,
    val strideLengthM: Double? = null,
) {
    data class RoutePoint(val t: Long, val lat: Double, val lon: Double, val alt: Double)

    val avgHeartRate: Int? get() = heartRate.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt()
    val maxHeartRate: Int? get() = heartRate.maxOfOrNull { it.second }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "workout")
        put("v", 1)
        put("id", id)
        put("activity", kind.healthKit)
        put("name", kind.label)
        put("indoor", kind.indoor)
        put("start", startMs)
        put("end", endMs)
        put("active", activeMs)
        kcal?.let { put("kcal", round(it, 1)) }
        distanceM?.let { put("distance", round(it, 1)) }
        steps?.let { put("steps", it) }
        put("hr", JSONArray().apply { heartRate.forEach { (t, bpm) -> put(JSONArray().put(t).put(bpm)) } })
        put("route", JSONArray().apply {
            route.forEach { p ->
                put(JSONArray().put(p.t).put(round(p.lat, 6)).put(round(p.lon, 6))
                    .apply { if (!p.alt.isNaN()) put(round(p.alt, 1)) })
            }
        })
        put("pauses", JSONArray().apply { pauses.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } })
        put("splits", JSONArray().apply { splits.forEach { (t, d) -> put(JSONArray().put(t).put(d)) } })
        put("segments", JSONArray().apply { segments.forEach { put(it) } })
        vo2Max?.let { put("vo2max", round(it, 1)) }
        groundContactMs?.let { put("gct", round(it, 0)) }
        verticalOscillationCm?.let { put("vosc", round(it, 1)) }
        strideLengthM?.let { put("stride", round(it, 2)) }
        if (goal.type != WorkoutGoal.Type.OPEN) {
            put("goal", JSONObject().put("type", goal.type.name.lowercase()).put("target", goal.target))
        }
    }

    companion object {
        private fun round(value: Double, digits: Int): Double {
            var factor = 1.0
            repeat(digits) { factor *= 10 }
            return Math.round(value * factor) / factor
        }
    }
}

/** Samples recorded passively during the day. */
object PassiveRecord {
    fun toJson(
        heartRate: List<Pair<Long, Int>>,
        steps: List<Triple<Long, Long, Double>>,
        distance: List<Triple<Long, Long, Double>> = emptyList(),
        floors: List<Triple<Long, Long, Double>> = emptyList(),
        activeEnergy: List<Triple<Long, Long, Double>> = emptyList(),
        basalEnergy: List<Triple<Long, Long, Double>> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "samples")
        put("v", 1)
        put("id", UUID.randomUUID().toString())
        put("hr", JSONArray().apply { heartRate.forEach { (t, bpm) -> put(JSONArray().put(t).put(bpm)) } })
        fun intervals(list: List<Triple<Long, Long, Double>>) = JSONArray().apply {
            list.forEach { (s, e, n) -> put(JSONArray().put(s).put(e).put(Math.round(n * 10) / 10.0)) }
        }
        put("steps", intervals(steps))
        put("distance", intervals(distance))
        put("floors", intervals(floors))
        // kcal, for Apple Health's Active Energy (Move ring in Fitness) and Resting Energy.
        put("active", intervals(activeEnergy))
        put("basal", intervals(basalEnergy))
    }

    fun hasData(record: JSONObject): Boolean =
        listOf("hr", "steps", "distance", "floors", "active", "basal").any { (record.optJSONArray(it)?.length() ?: 0) > 0 }

    /** A finished sleep period plus the resting heart rate measured during it. */
    /** A Breathe session → Mindful Minutes in Apple Health. */
    fun mindfulJson(interval: Pair<Long, Long>): JSONObject = JSONObject().apply {
        put("type", "samples")
        put("v", 1)
        put("id", UUID.randomUUID().toString())
        put("mindful", JSONArray().put(JSONArray().put(interval.first).put(interval.second)))
    }

    fun sleepJson(interval: Pair<Long, Long>, restingBpm: Int?): JSONObject = JSONObject().apply {
        put("type", "samples")
        put("v", 1)
        put("id", UUID.randomUUID().toString())
        put("sleep", JSONArray().put(JSONArray().put(interval.first).put(interval.second)))
        restingBpm?.let { put("resting", JSONArray().put(JSONArray().put(interval.second).put(it))) }
    }
}
