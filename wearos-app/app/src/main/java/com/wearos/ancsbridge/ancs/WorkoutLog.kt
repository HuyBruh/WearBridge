package com.wearos.ancsbridge.ancs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The service and activity call this store on the main thread. */
class WorkoutLog(context: Context) {
    private val prefs = context.getSharedPreferences("timed_workouts", Context.MODE_PRIVATE)
    companion object {
        private val shared = MutableStateFlow<Pair<String?, Int>>(null to 0)
    }
    val state get() = shared
    init { refresh() }

    fun completed(): List<JSONObject> {
        val entries = JSONArray(prefs.getString("completed", "[]"))
        return (0 until entries.length()).map { entries.getJSONObject(it) }
    }
    private fun refresh() {
        shared.value = prefs.getString("kind", null) to completed().size
    }
    fun start(kind: String) {
        if (kind !in listOf("Walk", "Run", "Cycle") || prefs.contains("kind") || completed().size >= 50) return
        prefs.edit().putString("kind", kind).putLong("start", System.currentTimeMillis()).apply()
        refresh()
    }
    fun finish() {
        val kind = prefs.getString("kind", null) ?: return
        val start = prefs.getLong("start", 0L)
        val end = System.currentTimeMillis()
        val editor = prefs.edit().remove("kind").remove("start")
        if (end > start) {
            val entry = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
                .put("start", start).put("end", end)
            editor.putString("completed", JSONArray(completed() + entry).toString())
        }
        editor.apply()
        refresh()
    }
    fun acknowledge(id: String) {
        val remaining = completed().filter { it.getString("id") != id }
        prefs.edit().putString("completed", JSONArray(remaining).toString()).apply()
        refresh()
    }
}
