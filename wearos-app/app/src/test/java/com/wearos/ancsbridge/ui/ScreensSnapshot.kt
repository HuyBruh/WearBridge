package com.wearos.ancsbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wearos.ancsbridge.ancs.AppIconMapper
import com.wearos.ancsbridge.ble.NowPlaying
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import org.junit.Rule
import org.junit.Test

/** Renders the main screens on a round Wear OS frame for visual review. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class ScreensSnapshot {
    @get:Rule
    val paparazzi = Paparazzi(
        // Pixel Watch 4 (45 mm) class screen: ~228 dp round.
        deviceConfig = DeviceConfig.WEAR_OS_SMALL_ROUND.copy(screenWidth = 456, screenHeight = 456),
        theme = "android:ThemeOverlay.Material.Dark"
    )

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalInspectionMode provides true
        ) { AncsBridgeTheme {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) {
                AppScaffold(timeText = { TimeText() }) { content() }
            }
        } }
    }

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot { Frame(content) }

    private val song = NowPlaying("Chúng Ta Của Hiện Tại", "Sơn Tùng M-TP", "Spotify", true, 0.6f)

    @Test
    fun homeConnected() = shot {
        HomeContent(ConnectionState.Connected("Huy's iPhone"), true, song, false, 2, true, true, {}, {}, {}, {})
    }

    @Test
    fun homeDisconnected() = shot {
        HomeContent(ConnectionState.Disconnected(), true, NowPlaying(), false, 0, false, false, {}, {}, {}, {})
    }

    @Test
    fun nowPlaying() = paparazzi.snapshot {
        Frame { NowPlayingContent(song, null) {} }
    }

    @Test
    fun settings() {
        val context = paparazzi.context
        val apps = listOf(
            "vn.com.vng.zingalo" to "Zalo",
            "com.apple.MobileSMS" to "Messages",
            "com.facebook.Messenger" to "Messenger",
            "com.google.Gmail" to "Gmail"
        ).map { (id, name) ->
            AppToggle(id, name, id != "com.google.Gmail",
                AppIconMapper.badge(context, id, -1, 64).asImageBitmap())
        }
        shot {
            NotificationSettingsContent(true, {}, false, {}, false, {}, apps, { _, _ -> },
                "This connection: 12 events · 9 shown")
        }
    }

    /** Notification badges for common apps (large icon + sender avatar). */
    @Test
    fun appBadges() {
        val ids = listOf("vn.com.vng.zingalo", "com.apple.MobileSMS", "com.facebook.Messenger",
            "com.facebook.Facebook", "net.whatsapp.WhatsApp", "org.telegram.Telegram",
            "com.google.Gmail", "com.apple.mobilecal", "com.burbn.instagram", "com.example.unknown")
        val context = paparazzi.context
        val badges = ids.map { AppIconMapper.badge(context, it, -1, 96).asImageBitmap() }
        paparazzi.snapshot {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = 4
                ) {
                    badges.forEach {
                        androidx.compose.foundation.Image(it, null, Modifier.size(40.dp))
                    }
                }
            }
        }
    }

    private val running = com.wearos.ancsbridge.health.WorkoutState.Running(
        kind = com.wearos.ancsbridge.health.WorkoutKind.OUTDOOR_RUN, paused = false,
        checkpointMs = 1_000_000L, activeMsAtCheckpoint = 1_534_560L, heartRate = 148,
        heartRateAvailable = true, kcal = 287.0, distanceM = 5230.0, speedMps = 3.1, gpsReady = true
    )

    @Test
    fun workoutMetrics() = shot { WorkoutMetrics(running, staticNow = 1_000_000L) }

    @Test
    fun workoutControls() = shot {
        LiveWorkoutContent(running, {}, {}, {}, null, null, {}, initialPage = 0)
    }

    @Test
    fun workoutList() = shot { WorkoutListContent {} }

    @Test
    fun workoutSummary() = shot {
        SummaryContent(
            com.wearos.ancsbridge.health.WorkoutRecord(
                kind = com.wearos.ancsbridge.health.WorkoutKind.OUTDOOR_RUN, startMs = 0, endMs = 1_900_000,
                activeMs = 1_834_000, kcal = 312.0, distanceM = 5230.0, steps = 5100,
                heartRate = listOf(1L to 140, 2L to 158), route = emptyList(), pauses = emptyList()
            ), healthAvailable = true
        ) {}
    }

    @Test
    fun health() = shot { HealthContent(3, 0, false, false, true, {}, {}, 0) }
}
