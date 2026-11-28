package com.wearos.ancsbridge.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsGymnastics
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.ble.MediaCommand
import com.wearos.ancsbridge.ble.NowPlaying
import com.wearos.ancsbridge.health.WorkoutGoal
import com.wearos.ancsbridge.health.WorkoutKind
import com.wearos.ancsbridge.health.WorkoutRecord
import com.wearos.ancsbridge.health.WorkoutState
import com.wearos.ancsbridge.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Apple Workout palette. */
internal object WorkoutColors {
    val Green = Color(0xFFA6FF00)
    val Timer = Color(0xFFFFD60A)
    val Heart = Color(0xFFFF375F)
    val Energy = Color(0xFFFF375F)
    val Distance = Color(0xFF64D2FF)
    val End = Color(0xFFFF453A)
}

internal fun WorkoutKind.icon(): ImageVector = when (this) {
    WorkoutKind.OUTDOOR_WALK, WorkoutKind.INDOOR_WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
    WorkoutKind.OUTDOOR_RUN, WorkoutKind.INDOOR_RUN -> Icons.AutoMirrored.Rounded.DirectionsRun
    WorkoutKind.OUTDOOR_CYCLE, WorkoutKind.INDOOR_CYCLE -> Icons.AutoMirrored.Rounded.DirectionsBike
    WorkoutKind.HIKING -> Icons.Rounded.Hiking
    WorkoutKind.STRENGTH -> Icons.Rounded.FitnessCenter
    WorkoutKind.HIIT -> Icons.Rounded.Whatshot
    WorkoutKind.YOGA -> Icons.Rounded.SelfImprovement
    WorkoutKind.ELLIPTICAL -> Icons.Rounded.SportsGymnastics
    WorkoutKind.ROWER -> Icons.Rounded.Rowing
    WorkoutKind.STAIR_STEPPER -> Icons.Rounded.Stairs
    WorkoutKind.DANCE -> Icons.Rounded.MusicNote
    WorkoutKind.OTHER -> Icons.Rounded.Timer
}

private fun workoutPermissions(gps: Boolean): Array<String> = buildList {
    add(Manifest.permission.BODY_SENSORS)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
    if (gps) add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

// ---------------------------------------------------------------------------------------
// Workout list
// ---------------------------------------------------------------------------------------

/** Asks for sensor/location permission, then starts the workout. */
@Composable
internal fun rememberWorkoutStarter(viewModel: MainViewModel, openLive: () -> Unit): (WorkoutKind, WorkoutGoal) -> Unit {
    var pending by remember { mutableStateOf<Pair<WorkoutKind, WorkoutGoal>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Start even if GPS was refused: Health Services falls back to step-based distance.
        pending?.let { (kind, goal) -> viewModel.startWorkout(kind, goal); openLive() }
        pending = null
    }
    return { kind, goal ->
        pending = kind to goal
        launcher.launch(workoutPermissions(kind.usesGps))
    }
}

@Composable
internal fun WorkoutListScreen(viewModel: MainViewModel, openGoals: (WorkoutKind) -> Unit, openLive: () -> Unit) {
    val start = rememberWorkoutStarter(viewModel, openLive)
    WorkoutListContent(onStart = { start(it, WorkoutGoal.OPEN) }, onGoals = openGoals)
}

@Composable
internal fun WorkoutGoalScreen(viewModel: MainViewModel, kind: WorkoutKind, openLive: () -> Unit) {
    val start = rememberWorkoutStarter(viewModel, openLive)
    ListScreen {
        item { ListHeader { Text(kind.label) } }
        item {
            Button(
                onClick = { start(kind, WorkoutGoal.OPEN) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3A10), contentColor = WorkoutColors.Green),
                secondaryLabel = { Text("No goal") },
            ) { Text("Open") }
        }
        WorkoutGoal.presets(kind).forEach { goal ->
            item(key = goal.type.name + goal.target) {
                Button(
                    onClick = { start(kind, goal) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = {
                        Text(when (goal.type) {
                            WorkoutGoal.Type.DISTANCE -> "Distance"
                            WorkoutGoal.Type.TIME -> "Time"
                            WorkoutGoal.Type.CALORIES -> "Calories"
                            WorkoutGoal.Type.OPEN -> ""
                        })
                    },
                ) { Text(goal.label) }
            }
        }
    }
}

@Composable
internal fun WorkoutListContent(onStart: (WorkoutKind) -> Unit, onGoals: (WorkoutKind) -> Unit = {}) {
    ListScreen {
        item { ListHeader { Text("Workout") } }
        item {
            Text("Tap to start · hold for goals", style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF98989F), textAlign = TextAlign.Center)
        }
        WorkoutKind.entries.forEach { kind ->
            item(key = kind.name) {
                Button(
                    onClick = { onStart(kind) },
                    onLongClick = { onGoals(kind) },
                    onLongClickLabel = "Set a goal",
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF2B3A10)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.wear.compose.material3.Icon(
                                kind.icon(), contentDescription = null,
                                tint = WorkoutColors.Green, modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                ) { Text(kind.label, maxLines = 2) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Live workout: countdown → [controls | metrics | now playing] → summary
// ---------------------------------------------------------------------------------------

@Composable
internal fun LiveWorkoutScreen(viewModel: MainViewModel, onDone: () -> Unit) {
    val state by viewModel.workoutState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val supported by viewModel.supportedCommands.collectAsState()
    val mediaAvailable by viewModel.mediaAvailable.collectAsState()
    val healthAvailable by viewModel.healthAvailable.collectAsState()

    when (val s = state) {
        is WorkoutState.Starting -> CountdownContent(s.kind)
        is WorkoutState.Running -> LiveWorkoutContent(
            s,
            onPause = viewModel::pauseWorkout,
            onResume = viewModel::resumeWorkout,
            onEnd = viewModel::endWorkout,
            onSegment = viewModel::markSegment,
            nowPlaying = if (mediaAvailable) nowPlaying else null,
            supportedCommands = supported,
            sendMedia = { viewModel.sendMediaCommand(it.code) },
        )
        is WorkoutState.Summary -> SummaryContent(s.record, healthAvailable) {
            viewModel.dismissWorkoutSummary(); onDone()
        }
        is WorkoutState.Failed -> FailedContent(s.message) { viewModel.dismissWorkoutSummary(); onDone() }
        WorkoutState.Idle -> LaunchedEffect(Unit) { onDone() }
    }
}

@Composable
internal fun CountdownContent(kind: WorkoutKind) {
    var count by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (count > 1) { delay(900); count-- }
    }
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(kind.label, color = WorkoutColors.Green, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF1C2A08)),
                contentAlignment = Alignment.Center
            ) {
                Text("$count", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = WorkoutColors.Green)
            }
        }
    }
}

@Composable
internal fun LiveWorkoutContent(
    state: WorkoutState.Running,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onSegment: () -> Unit = {},
    nowPlaying: NowPlaying?,
    supportedCommands: Set<Int>?,
    sendMedia: (MediaCommand) -> Unit,
    initialPage: Int = 1,
) {
    val pages = if (nowPlaying != null) 3 else 2
    val pagerState = rememberPagerState(initialPage = initialPage) { pages }
    val scope = rememberCoroutineScope()
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> WorkoutControls(state.paused, onPause = {
                onPause(); scope.launch { pagerState.animateScrollToPage(1) }
            }, onResume = {
                onResume(); scope.launch { pagerState.animateScrollToPage(1) }
            }, onEnd = onEnd, onSegment = {
                onSegment(); scope.launch { pagerState.animateScrollToPage(1) }
            })
            1 -> WorkoutMetrics(state)
            else -> nowPlaying?.let { NowPlayingContent(it, supportedCommands, send = sendMedia) }
        }
    }
}

@Composable
private fun WorkoutControls(
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onSegment: () -> Unit,
) {
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(Icons.Rounded.Close, "End", WorkoutColors.End, Color(0xFF3A1210), onEnd)
                if (paused) ControlButton(Icons.Rounded.PlayArrow, "Resume", WorkoutColors.Green, Color(0xFF233108), onResume)
                else ControlButton(Icons.Rounded.Pause, "Pause", WorkoutColors.Timer, Color(0xFF3A3208), onPause)
            }
            Spacer(Modifier.height(8.dp))
            ControlButton(Icons.Rounded.Flag, "Segment", WorkoutColors.Distance, Color(0xFF0E2A36), onSegment, size = 48)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector, label: String, tint: Color, background: Color, onClick: () -> Unit, size: Int = 64,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            androidx.wear.compose.material3.Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((size * 0.56f).dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun WorkoutMetrics(state: WorkoutState.Running, staticNow: Long? = null) {
    var now by remember { mutableLongStateOf(staticNow ?: System.currentTimeMillis()) }
    if (staticNow == null) LaunchedEffect(state.paused) {
        while (true) { now = System.currentTimeMillis(); delay(50) }
    }
    val active = state.activeMs(now)
    // Apple blinks the timer while paused.
    val timerVisible = !state.paused || (now / 600) % 2 == 0L
    val goalProgress = state.goal.progress(active, state.distanceM, state.kcal)
    val showSplit = state.lastSplit != null && now - state.lastSplitAtMs < 6_000

    ScreenScaffold {
        if (state.goal.type != WorkoutGoal.Type.OPEN) {
            // Goal progress around the bezel, as on Apple Watch.
            CircularProgressIndicator(
                progress = { goalProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize().padding(2.dp),
                colors = ProgressIndicatorDefaults.colors(
                    indicatorColor = WorkoutColors.Green, trackColor = Color(0xFF1E2A0C)
                ),
                strokeWidth = 5.dp,
            )
        }
        if (showSplit) {
            val (km, ms) = state.lastSplit!!
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.clip(CircleShape).background(Color(0xFF0E2A36)).padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("KM $km", color = WorkoutColors.Distance, style = MaterialTheme.typography.labelMedium)
                    Text(formatPace(ms, 1000.0), fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text("SPLIT", style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F))
                }
            }
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(start = 34.dp, end = 16.dp, top = 30.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                formatTimer(active),
                fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                color = if (timerVisible) WorkoutColors.Timer else Color.Transparent, maxLines = 1
            )
            if (state.paused) {
                Text("PAUSED", color = WorkoutColors.Timer, style = MaterialTheme.typography.labelSmall)
            } else if (state.goal.type != WorkoutGoal.Type.OPEN) {
                Text(
                    if (state.goalReached) "GOAL REACHED" else "${(goalProgress * 100).toInt()}% OF ${state.goal.label.uppercase()}",
                    color = WorkoutColors.Green, style = MaterialTheme.typography.labelSmall
                )
            }
            Metric(
                value = state.heartRate?.toString() ?: "--", unit = "BPM",
                icon = Icons.Rounded.Favorite, iconTint = if (state.heartRateAvailable) WorkoutColors.Heart else Color(0xFF636366)
            )
            Metric(value = "%.0f".format(state.kcal), unit = "ACTIVE KCAL")
            if (state.kind.showsDistance) {
                Metric(value = "%.2f".format(Locale.US, state.distanceM / 1000), unit = "KM")
                if (state.kind.showsPace) {
                    Metric(value = formatPace(active, state.distanceM), unit = "AVG PACE")
                } else {
                    val kmh = (state.speedMps ?: 0.0) * 3.6
                    Metric(value = "%.1f".format(Locale.US, kmh), unit = "KM/H")
                }
                if (state.kind.usesGps && !state.gpsReady) {
                    Text("Finding GPS…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F))
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, unit: String, icon: ImageVector? = null, iconTint: Color = Color.White, size: TextUnit = 24.sp) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = size, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Text(unit, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(bottom = 4.dp))
        if (icon != null) {
            Spacer(Modifier.width(4.dp))
            androidx.wear.compose.material3.Icon(icon, contentDescription = null, tint = iconTint,
                modifier = Modifier.size(16.dp).padding(bottom = 3.dp))
        }
    }
}

@Composable
internal fun SummaryContent(record: WorkoutRecord, healthAvailable: Boolean, onDone: () -> Unit) {
    val tooShort = record.activeMs < 60_000
    ListScreen {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 18.dp)) {
                IconBadge(record.kind.icon(), Color(0xFF2B3A10))
                Spacer(Modifier.height(4.dp))
                Text(record.kind.label, style = MaterialTheme.typography.titleMedium, color = WorkoutColors.Green)
                Text(
                    if (tooShort) "Too short to save" else "Workout saved",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF98989F)
                )
            }
        }
        item { SummaryRow("Total Time", formatTimer(record.activeMs, hundredths = false), WorkoutColors.Timer) }
        record.kcal?.let { item { SummaryRow("Active Kcal", "%.0f".format(it), WorkoutColors.Energy) } }
        record.distanceM?.let {
            item { SummaryRow("Distance", "%.2f km".format(Locale.US, it / 1000), WorkoutColors.Distance) }
            if (record.kind.showsPace) item {
                SummaryRow("Avg. Pace", formatPace(record.activeMs, it) + " /km", WorkoutColors.Distance)
            }
        }
        if (record.goal.type != WorkoutGoal.Type.OPEN) item {
            val reached = record.goal.progress(record.activeMs, record.distanceM ?: 0.0, record.kcal ?: 0.0) >= 1f
            SummaryRow("Goal · ${record.goal.label}", if (reached) "Completed" else "Not completed", WorkoutColors.Green)
        }
        record.avgHeartRate?.let { item { SummaryRow("Avg. Heart Rate", "$it bpm", WorkoutColors.Heart) } }
        record.vo2Max?.let { item { SummaryRow("VO₂ Max", "%.1f".format(it), WorkoutColors.Heart) } }
        record.strideLengthM?.let { item { SummaryRow("Avg. Stride", "%.2f m".format(Locale.US, it), WorkoutColors.Distance) } }
        record.groundContactMs?.let { item { SummaryRow("Ground Contact", "${it.toInt()} ms", WorkoutColors.Distance) } }
        record.verticalOscillationCm?.let { item { SummaryRow("Vertical Oscillation", "%.1f cm".format(Locale.US, it), WorkoutColors.Distance) } }
        record.maxHeartRate?.let { item { SummaryRow("Max Heart Rate", "$it bpm", WorkoutColors.Heart) } }
        if (record.splits.isNotEmpty()) {
            item { ListHeader { Text("Splits") } }
            record.splits.forEachIndexed { index, (_, ms) ->
                item { SummaryRow("Km ${index + 1}", formatPace(ms, 1000.0) + " /km", WorkoutColors.Distance) }
            }
        }
        if (record.segments.isNotEmpty()) item {
            SummaryRow("Segments", "${record.segments.size}", WorkoutColors.Distance)
        }
        if (!tooShort) item {
            Text(
                if (healthAvailable) "Sending to Apple Health…" else "Syncs to Apple Health when your iPhone is nearby",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        item {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF98989F))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun FailedContent(message: String, onDone: () -> Unit) {
    ListScreen {
        item { ListHeader { Text("Workout") } }
        item {
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp))
        }
        item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("OK") } }
    }
}

// ---------------------------------------------------------------------------------------
// Apple Health sync screen
// ---------------------------------------------------------------------------------------

@Composable
internal fun HealthScreen(viewModel: MainViewModel) {
    val pending by viewModel.healthPending.collectAsState()
    val lastSync by viewModel.healthLastSync.collectAsState()
    val syncing by viewModel.healthSyncing.collectAsState()
    val available by viewModel.healthAvailable.collectAsState()
    var passive by remember { mutableStateOf(viewModel.passiveHealthEnabled()) }
    val context = LocalContext.current
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.setPassiveHealth(true) { passive = it }
    }
    val sensorsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            // Background readings need a second, separate grant.
            if (Build.VERSION.SDK_INT >= 36) backgroundLauncher.launch("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
            else backgroundLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
        }
    }

    HealthContent(
        pending = pending, lastSync = lastSync, syncing = syncing, iPhoneReady = available,
        passive = passive,
        onPassive = { on ->
            if (on) sensorsLauncher.launch(workoutPermissions(gps = false))
            else viewModel.setPassiveHealth(false) { passive = it }
        },
        onSync = viewModel::syncHealthNow,
        now = System.currentTimeMillis(),
    )
    LaunchedEffect(context) { viewModel.syncHealthNow() }
}

@Composable
internal fun HealthContent(
    pending: Int,
    lastSync: Long,
    syncing: Boolean,
    iPhoneReady: Boolean,
    passive: Boolean,
    onPassive: (Boolean) -> Unit,
    onSync: () -> Unit,
    now: Long,
) {
    val status = when {
        syncing -> "Sending to iPhone…"
        pending > 0 && !iPhoneReady -> "$pending waiting · open WearBridge on iPhone"
        pending > 0 -> "$pending waiting"
        lastSync > 0 -> "Up to date · ${relativeTime(now - lastSync)}"
        else -> "Nothing to sync yet"
    }
    ListScreen {
        item { ListHeader { Text("Apple Health") } }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(Icons.Rounded.Favorite, WorkoutColors.Heart)
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        item {
            SwitchButton(
                checked = passive,
                onCheckedChange = onPassive,
                modifier = Modifier.fillMaxWidth(),
                colors = appleSwitchColors(),
                icon = { androidx.wear.compose.material3.Icon(Icons.Rounded.MonitorHeart, contentDescription = null) },
                secondaryLabel = { Text("All day, in the background", maxLines = 2) },
            ) { Text("Heart Rate & Steps", maxLines = 2) }
        }
        item {
            NavButton(Icons.Rounded.Sync, Color(0xFF0A84FF), "Sync Now", null, onSync)
        }
        item {
            Text(
                "Workouts and readings stay on the watch until the WearBridge iPhone app saves them to Health.",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = Color(0xFF98989F), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------

internal fun formatTimer(ms: Long, hundredths: Boolean = true): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    return if (hundredths && h == 0L) base + ".%02d".format((ms % 1000) / 10) else base
}

/** Average pace as m'ss" per km. */
internal fun formatPace(activeMs: Long, distanceM: Double): String {
    if (distanceM < 10) return "--'--\""
    val secondsPerKm = (activeMs / 1000.0) / (distanceM / 1000.0)
    if (secondsPerKm > 3600) return "--'--\""
    val total = secondsPerKm.toInt()
    return "%d'%02d\"".format(total / 60, total % 60)
}

private fun relativeTime(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    ms < 86_400_000 -> "${ms / 3_600_000} h ago"
    else -> "${ms / 86_400_000} d ago"
}
