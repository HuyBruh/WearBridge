package com.wearos.ancsbridge.health

import com.wearos.ancsbridge.ui.formatPace
import com.wearos.ancsbridge.ui.formatTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {

    @Test
    fun `workout record serializes what HealthKit needs`() {
        val record = WorkoutRecord(
            id = "abc", kind = WorkoutKind.OUTDOOR_RUN, startMs = 1_000, endMs = 1_801_000,
            activeMs = 1_700_000, kcal = 312.456, distanceM = 5012.34, steps = 4800,
            heartRate = listOf(2_000L to 120, 7_000L to 150),
            route = listOf(WorkoutRecord.RoutePoint(3_000, 21.0285123, 105.8542123, Double.NaN)),
            pauses = listOf(100_000L to 200_000L)
        )
        val json = record.toJson()
        assertEquals("workout", json.getString("type"))
        assertEquals("running", json.getString("activity"))
        assertEquals(false, json.getBoolean("indoor"))
        assertEquals(312.5, json.getDouble("kcal"), 0.0)
        assertEquals(2, json.getJSONArray("hr").length())
        val point = json.getJSONArray("route").getJSONArray(0)
        assertEquals(3, point.length()) // no altitude when unavailable
        assertEquals(21.028512, point.getDouble(1), 1e-9)
        assertEquals(135, record.avgHeartRate)
        assertEquals(150, record.maxHeartRate)
    }

    @Test
    fun `step totals become interval deltas`() {
        val day = 1_000_000L
        // First reading only seeds.
        val (s1, out1) = StepDeltas.compute(StepDeltas.State(-1, 0, 0), listOf(Triple(day, day + 100, 500L)))
        assertTrue(out1.isEmpty())
        // Later readings emit the difference over the elapsed interval.
        val (s2, out2) = StepDeltas.compute(s1, listOf(Triple(day, day + 200, 650L), Triple(day, day + 300, 650L)))
        assertEquals(listOf(Triple(day + 100, day + 200, 150L)), out2)
        // A new day starts counting from midnight.
        val next = day + 86_400_000
        val (_, out3) = StepDeltas.compute(s2, listOf(Triple(next, next + 50, 40L)))
        assertEquals(listOf(Triple(next, next + 50, 40L)), out3)
    }

    @Test
    fun `timer and pace format like Apple Workout`() {
        assertEquals("01:05.43", formatTimer(65_432))
        assertEquals("1:01:05", formatTimer(3_665_000))
        assertEquals("5'00\"", formatPace(300_000, 1000.0))
        assertEquals("--'--\"", formatPace(300_000, 5.0))
    }
}
