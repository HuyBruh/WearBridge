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
        val (s1, out1) = DailyDeltas.compute(DailyDeltas.State(-1, 0.0, 0), listOf(Triple(day, day + 100, 500.0)))
        assertTrue(out1.isEmpty())
        // Later readings emit the difference over the elapsed interval.
        val (s2, out2) = DailyDeltas.compute(s1, listOf(Triple(day, day + 200, 650.0), Triple(day, day + 300, 650.0)))
        assertEquals(listOf(Triple(day + 100, day + 200, 150.0)), out2)
        // A new day starts counting from midnight.
        val next = day + 86_400_000
        val (_, out3) = DailyDeltas.compute(s2, listOf(Triple(next, next + 50, 40.0)))
        assertEquals(listOf(Triple(next, next + 50, 40.0)), out3)
    }

    @Test
    fun `timer and pace format like Apple Workout`() {
        assertEquals("01:05.43", formatTimer(65_432))
        assertEquals("1:01:05", formatTimer(3_665_000))
        assertEquals("5'00\"", formatPace(300_000, 1000.0))
        assertEquals("--'--\"", formatPace(300_000, 5.0))
    }
}

class ActivityTest {
    private val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
    private val date = "2026-11-28"
    private val midnight = java.time.LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int = 0) = midnight + (h * 60 + m) * 60_000L

    @Test
    fun `brisk walking counts as exercise and fills stand hours`() {
        val day = ActivityDay(date).withSteps(listOf(
            Triple(at(8), at(8, 10), 1_200.0),   // 120 steps/min: brisk
            Triple(at(9), at(9, 30), 600.0),     // 20 steps/min: not brisk
            Triple(at(10), at(10, 1), 30.0),     // too few for a stand hour
        ), zone)
        assertEquals(10.0, day.exerciseMin, 0.01)
        assertEquals(2, day.standHours)
        assertEquals(1_830, day.steps)
    }

    @Test
    fun `walking inside a workout is not counted twice`() {
        val day = ActivityDay(date).withWorkout(at(7), at(7, 30), 30 * 60_000L)
            .withSteps(listOf(Triple(at(7, 5), at(7, 15), 1_500.0)), zone)
        assertEquals(30.0, day.exerciseMin, 0.01)
    }

    @Test
    fun `move subtracts resting burn`() {
        val day = ActivityDay(date, totalKcal = 1_000.0, lastCalEndMs = at(12))
        assertEquals(1_000.0 - 720.0, day.activeKcal(1.0, zone), 0.01)
        assertEquals(0.0, ActivityDay(date, totalKcal = 500.0, lastCalEndMs = at(12)).activeKcal(1.0, zone), 0.0)
    }

    @Test
    fun `quiet periods teach the resting rate`() {
        val learned = ActivityTracker.learnResting(1.0, at(2), at(3), 48.0, emptyList())
        assertTrue(learned < 1.0)
        // Active periods never change it.
        assertEquals(1.0, ActivityTracker.learnResting(1.0, at(2), at(3), 300.0,
            listOf(Triple(at(2), at(3), 3_000.0))), 0.0)
    }

    @Test
    fun `day survives a JSON round trip`() {
        val day = ActivityDay(date, 900.0, at(10), 12.5, List(24) { it }, listOf(at(7) to at(8)))
        assertEquals(day, ActivityDay.fromJson(day.toJson()))
    }

    @Test
    fun `iPhone battery is parsed from the status summary`() {
        assertEquals(82, com.wearos.ancsbridge.tiles.PhoneBatteryComplicationService.parseBattery("iPhone battery: 82%\nNo events"))
        assertEquals(null, com.wearos.ancsbridge.tiles.PhoneBatteryComplicationService.parseBattery("Battery unavailable"))
    }

    @Test
    fun `quick replies from the iPhone are cleaned up`() {
        assertEquals(listOf("OK", "On my way"), com.wearos.ancsbridge.model.QuickReplies.parse("""[" OK ", "", "On my way"]"""))
        assertEquals(null, com.wearos.ancsbridge.model.QuickReplies.parse("not json"))
        assertEquals(null, com.wearos.ancsbridge.model.QuickReplies.parse("[]"))
    }

    @Test
    fun `goal progress`() {
        val goal = WorkoutGoal(WorkoutGoal.Type.DISTANCE, 5_000.0)
        assertEquals(0.5f, goal.progress(0, 2_500.0, 0.0), 0.001f)
        assertEquals("5 km", goal.label)
        assertEquals(12, WorkoutGoal.presets(WorkoutKind.OUTDOOR_RUN).size)
        assertEquals(8, WorkoutGoal.presets(WorkoutKind.YOGA).size)
    }
}

class TodayAndSleepTest {
    @Test
    fun `parses the iPhone Today payload`() {
        val json = """{"at":1,"weather":{"temp":28.4,"code":61,"day":true,"hi":31.2,"lo":24,"place":"Hà Nội","at":5,
            "hourly":[[1000,28.4,61],[2000,27.0,3]]},
            "events":[{"title":"Họp nhóm","start":100,"end":200,"allDay":false,"color":"#FF3B30"}],
            "reminders":[{"id":"r1","title":"Mua sữa","due":50}]}"""
        val info = com.wearos.ancsbridge.model.PhoneInfo.parse(json)!!
        assertEquals("Rain", info.weather!!.condition)
        assertEquals(31.2, info.weather!!.hi!!, 0.0)
        assertEquals(2, info.weather!!.hourly.size)
        assertEquals(0xFFFF3B30.toInt(), info.events[0].color)
        assertEquals("Mua sữa", info.reminders[0].title)
        assertEquals("Họp nhóm", info.nextEvent(now = 150)?.title)
        assertEquals(null, info.nextEvent(now = 300))
        assertEquals(null, com.wearos.ancsbridge.model.PhoneInfo.parse("nope"))
    }

    @Test
    fun `resting heart rate uses the lowest readings`() {
        assertEquals(null, SleepTracker.restingHeartRate(listOf(60, 61)))
        val night = List(14) { 70 } + listOf(52, 53, 54, 55, 56, 57)
        assertEquals(54, SleepTracker.restingHeartRate(night))
    }

    @Test
    fun `running metrics and sleep serialize for HealthKit`() {
        val record = WorkoutRecord(kind = WorkoutKind.OUTDOOR_RUN, startMs = 0, endMs = 1, activeMs = 1,
            kcal = null, distanceM = null, steps = null, heartRate = emptyList(), route = emptyList(),
            pauses = emptyList(), vo2Max = 47.26, groundContactMs = 251.4, verticalOscillationCm = 8.84, strideLengthM = 1.123)
        val json = record.toJson()
        assertEquals(47.3, json.getDouble("vo2max"), 0.0)
        assertEquals(251.0, json.getDouble("gct"), 0.0)
        assertEquals(1.12, json.getDouble("stride"), 0.0)
        val sleep = PassiveRecord.sleepJson(10L to 20L, 55)
        assertEquals(2, sleep.getJSONArray("sleep").getJSONArray(0).length())
        assertEquals(55, sleep.getJSONArray("resting").getJSONArray(0).getInt(1))
    }
}
