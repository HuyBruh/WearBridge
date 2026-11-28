package com.wearos.ancsbridge.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Durable queue of health records waiting to reach Apple Health via the iPhone app.
 * One JSON file per record; a record is deleted only after the iPhone confirmed it was
 * stored, so nothing is lost when the phone is away, locked or the app is closed.
 */
class HealthOutbox(context: Context) {
    private val dir = File(context.filesDir, "health_outbox").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("health_sync", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_RECORDS = 500
        private val _pending = MutableStateFlow(-1)
        val pending: StateFlow<Int> = _pending.asStateFlow()
        private val _lastSync = MutableStateFlow(0L)
        val lastSync: StateFlow<Long> = _lastSync.asStateFlow()
    }

    init {
        refresh()
        _lastSync.value = prefs.getLong("last_sync", 0L)
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    @Synchronized
    fun add(record: JSONObject) {
        val existing = files()
        // Never fill the watch: drop the oldest passive samples first, keep workouts.
        if (existing.size >= MAX_RECORDS) {
            existing.firstOrNull { it.name.contains("-samples-") }?.delete()
        }
        val name = "%013d-%s-%s.json".format(
            System.currentTimeMillis(), record.optString("type", "record"), record.getString("id")
        )
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(record.toString())
        tmp.renameTo(File(dir, name))
        refresh()
    }

    @Synchronized
    fun oldest(): File? = files().firstOrNull()

    @Synchronized
    fun remove(file: File) {
        file.delete()
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_sync", now).apply()
        _lastSync.value = now
        refresh()
    }

    fun workoutsPending(): Int = files().count { it.name.contains("-workout-") }

    private fun refresh() {
        _pending.value = files().size
    }
}
