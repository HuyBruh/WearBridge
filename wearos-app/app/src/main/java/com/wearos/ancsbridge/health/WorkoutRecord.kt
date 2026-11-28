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
    }

    companion object {
        private fun round(value: Double, digits: Int): Double {
            var factor = 1.0
            repeat(digits) { factor *= 10 }
            return Math.round(value * factor) / factor
        }
    }
}

/** Heart rate / step samples recorded passively during the day. */
object PassiveRecord {
    fun toJson(heartRate: List<Pair<Long, Int>>, steps: List<Triple<Long, Long, Long>>): JSONObject =
        JSONObject().apply {
            put("type", "samples")
            put("v", 1)
            put("id", UUID.randomUUID().toString())
            put("hr", JSONArray().apply { heartRate.forEach { (t, bpm) -> put(JSONArray().put(t).put(bpm)) } })
            put("steps", JSONArray().apply {
                steps.forEach { (s, e, n) -> put(JSONArray().put(s).put(e).put(n)) }
            })
        }
}
