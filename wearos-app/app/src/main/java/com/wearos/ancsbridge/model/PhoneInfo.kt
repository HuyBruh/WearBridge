package com.wearos.ancsbridge.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Weather, calendar and reminders sent by the iPhone app (cached for offline use). */
data class PhoneInfo(
    val weather: Weather? = null,
    val events: List<Event> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val atMs: Long = 0,
) {
    data class Weather(
        val temp: Double, val code: Int, val day: Boolean, val hi: Double?, val lo: Double?,
        val place: String, val atMs: Long, val hourly: List<Triple<Long, Double, Int>>,
    ) {
        val condition: String get() = conditionFor(code)
    }
    data class Event(val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean, val location: String, val color: Int?)
    data class Reminder(val id: String, val title: String, val dueMs: Long?)

    /** Next event that has not ended. */
    fun nextEvent(now: Long = System.currentTimeMillis()): Event? =
        events.filter { it.endMs > now }.minByOrNull { if (it.allDay) Long.MAX_VALUE else it.startMs }
            ?: events.firstOrNull { it.endMs > now }

    companion object {
        private const val PREFS = "phone_info"
        private val _current = MutableStateFlow(PhoneInfo())
        val current: StateFlow<PhoneInfo> = _current.asStateFlow()

        /** Called after every change so the Today tile and complications refresh. */
        var onChanged: ((Context) -> Unit)? = null

        fun load(context: Context): PhoneInfo {
            if (_current.value.atMs == 0L) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
                    ?.let(::parse)?.let { _current.value = it }
            }
            return _current.value
        }

        fun save(context: Context, json: String) {
            val info = parse(json) ?: return
            _current.value = info
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", json).apply()
            onChanged?.invoke(context)
        }

        /** Optimistic update after the user ticks a reminder. */
        fun removeReminder(id: String) {
            _current.value = _current.value.copy(reminders = _current.value.reminders.filter { it.id != id })
        }

        fun parse(json: String): PhoneInfo? = runCatching {
            val root = JSONObject(json)
            val weather = root.optJSONObject("weather")?.let { w ->
                val hourly = w.optJSONArray("hourly")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONArray(i).let { Triple(it.getLong(0), it.getDouble(1), it.getInt(2)) } }
                }.orEmpty()
                Weather(
                    temp = w.optDouble("temp", 0.0), code = w.optInt("code", 0), day = w.optBoolean("day", true),
                    hi = w.optDouble("hi").takeUnless { it.isNaN() }, lo = w.optDouble("lo").takeUnless { it.isNaN() },
                    place = w.optString("place"), atMs = w.optLong("at"), hourly = hourly,
                )
            }
            val events = root.optJSONArray("events")?.let { a ->
                (0 until a.length()).map { i ->
                    val e = a.getJSONObject(i)
                    Event(
                        e.optString("title"), e.optLong("start"), e.optLong("end"), e.optBoolean("allDay"),
                        e.optString("location"),
                        e.optString("color").takeIf { it.startsWith("#") && it.length == 7 }
                            ?.let { (0xFF000000 or it.substring(1).toLong(16)).toInt() },
                    )
                }
            }.orEmpty()
            val reminders = root.optJSONArray("reminders")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    Reminder(r.optString("id"), r.optString("title"), r.optLong("due").takeIf { it > 0 })
                }
            }.orEmpty()
            PhoneInfo(weather, events, reminders, root.optLong("at", System.currentTimeMillis()))
        }.getOrNull()

        /** WMO weather interpretation codes (Open-Meteo). */
        fun conditionFor(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly Clear"
            2 -> "Partly Cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66, 80, 81 -> "Rain"
            65, 67, 82 -> "Heavy Rain"
            71, 73, 75, 77, 85, 86 -> "Snow"
            95, 96, 99 -> "Thunderstorms"
            else -> "—"
        }
    }
}
