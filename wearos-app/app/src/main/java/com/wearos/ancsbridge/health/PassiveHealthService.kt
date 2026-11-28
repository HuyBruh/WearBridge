package com.wearos.ancsbridge.health

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import com.wearos.ancsbridge.ancs.AncsService
import java.time.Instant

/**
 * All-day heart rate and steps, delivered in batches by Health Services even while the
 * app is closed, queued for Apple Health like Apple Watch's background measurements.
 */
class PassiveHealthService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val heartRate = dataPoints.getData(DataType.HEART_RATE_BPM).mapNotNull { point ->
            val bpm = point.value.toInt()
            if (bpm in 30..240) point.getTimeInstant(boot).toEpochMilli() to bpm else null
        }
        val steps = StepDeltas(this).consume(
            dataPoints.getData(DataType.STEPS_DAILY).map { point ->
                Triple(point.getStartInstant(boot).toEpochMilli(), point.getEndInstant(boot).toEpochMilli(), point.value)
            }
        )
        if (heartRate.isEmpty() && steps.isEmpty()) return
        HealthOutbox(this).add(PassiveRecord.toJson(heartRate, steps))
        startService(Intent(this, AncsService::class.java).setAction(AncsService.ACTION_SYNC_HEALTH))
    }

    companion object {
        private const val TAG = "PassiveHealth"
        private const val PREFS = "health_sync"

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("passive", false)

        /** Register or unregister background heart rate + steps. Returns whether it is on. */
        suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
            val client = HealthServices.getClient(context).passiveMonitoringClient
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return try {
                if (enabled) {
                    val supported = client.getCapabilities().supportedDataTypesPassiveMonitoring
                    val types = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY).filter { it in supported }.toSet()
                    if (types.isEmpty()) return false
                    client.setPassiveListenerService(
                        PassiveHealthService::class.java,
                        PassiveListenerConfig.builder().setDataTypes(types).build()
                    )
                } else {
                    client.clearPassiveListenerService()
                }
                prefs.edit().putBoolean("passive", enabled).apply()
                enabled
            } catch (e: Exception) {
                Log.w(TAG, "Passive monitoring change failed", e)
                false
            }
        }
    }
}

/**
 * STEPS_DAILY reports a running total since midnight. Apple Health wants interval
 * samples, so convert consecutive totals into (start, end, count) deltas.
 */
internal class StepDeltas(context: Context) {
    private val prefs = context.getSharedPreferences("step_deltas", Context.MODE_PRIVATE)

    fun consume(points: List<Triple<Long, Long, Long>>): List<Triple<Long, Long, Long>> {
        val (state, out) = compute(
            State(prefs.getLong("day", -1), prefs.getLong("total", 0), prefs.getLong("end", 0)), points
        )
        prefs.edit().putLong("day", state.day).putLong("total", state.total).putLong("end", state.lastEnd).apply()
        return out
    }

    data class State(val day: Long, val total: Long, val lastEnd: Long)

    companion object {
        /** Pure step-delta logic: (dayStart, end, totalSinceMidnight) → (start, end, steps). */
        fun compute(
            initial: State,
            points: List<Triple<Long, Long, Long>>
        ): Pair<State, List<Triple<Long, Long, Long>>> {
            var (day, total, lastEnd) = initial
            val out = mutableListOf<Triple<Long, Long, Long>>()
            for ((dayStart, end, value) in points.sortedBy { it.second }) {
                when {
                    day == -1L -> Unit // First reading ever: seed only, never backfill a whole day.
                    dayStart != day -> if (value > 0) out += Triple(dayStart, end, value)
                    value > total && end > lastEnd -> out += Triple(lastEnd, end, value - total)
                }
                if (dayStart != day || value >= total) {
                    day = dayStart
                    total = value
                    lastEnd = end
                }
            }
            return State(day, total, lastEnd) to out
        }
    }
}
