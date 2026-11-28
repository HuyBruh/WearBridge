package com.wearos.ancsbridge.ui

import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Warning
import com.wearos.ancsbridge.ancs.AncsService
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wearos.ancsbridge.ancs.AppIconMapper
import com.wearos.ancsbridge.ble.MediaCommand
import com.wearos.ancsbridge.ble.NowPlaying
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.theme.WearBridgeColors
import com.wearos.ancsbridge.viewmodel.MainViewModel
import kotlin.math.abs

private object Routes {
    const val HOME = "home"
    const val NOW_PLAYING = "now_playing"
    const val IPHONE = "iphone"
    const val NOTIFICATIONS = "notifications"
    const val PAIR = "pair"
    const val WORKOUTS = "workouts"
    const val BREATHE = "breathe"
    const val WORKOUT_LIVE = "workout_live"
    const val WORKOUT_GOAL = "workout_goal"
    const val MESSAGES = "messages"
    const val TODAY = "today"
    const val MESSAGES_SETUP = "messages_setup"
    const val CONVERSATION = "conversation"
    const val REPLY = "reply"
    const val COMPOSE = "compose"
    const val HEALTH = "health"
}

/** Root: system time on top, swipe right to go back on every screen (native Wear OS). */
@Composable
fun MainScreen(viewModel: MainViewModel, deepLink: String? = null, onDeepLinkHandled: () -> Unit = {}) {
    val navController = rememberSwipeDismissableNavController()
    val startFromLink = rememberWorkoutStarter(viewModel) {
        navController.navigate(Routes.WORKOUT_LIVE) { popUpTo(Routes.HOME) }
    }
    // Tiles, complications and the workout chip open a specific screen.
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        onDeepLinkHandled()
        val workoutRunning = viewModel.workoutState.value !is com.wearos.ancsbridge.health.WorkoutState.Idle
        when {
            link == MainActivity.OPEN_LIVE_WORKOUT && workoutRunning -> navController.navigate(Routes.WORKOUT_LIVE)
            link == MainActivity.OPEN_ACTIVITY -> navController.navigate(Routes.HEALTH)
            link == MainActivity.OPEN_TODAY -> navController.navigate(Routes.TODAY)
            link == MainActivity.OPEN_NOW_PLAYING -> navController.navigate(Routes.NOW_PLAYING)
            link.startsWith(MainActivity.OPEN_WORKOUT_PREFIX) -> {
                if (workoutRunning) navController.navigate(Routes.WORKOUT_LIVE)
                else runCatching {
                    com.wearos.ancsbridge.health.WorkoutKind.valueOf(link.removePrefix(MainActivity.OPEN_WORKOUT_PREFIX))
                }.getOrNull()?.let { startFromLink(it, com.wearos.ancsbridge.health.WorkoutGoal.OPEN) }
            }
        }
    }
    AppScaffold(timeText = { TimeText() }) {
        SwipeDismissableNavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(viewModel) { navController.navigate(it) } }
            composable(Routes.NOW_PLAYING) { NowPlayingScreen(viewModel) }
            composable(Routes.IPHONE) { PhoneToolsScreen(viewModel) }
            composable(Routes.NOTIFICATIONS) { NotificationSettingsScreen(viewModel) }
            composable(Routes.PAIR) { PairScreen(viewModel) { navController.popBackStack() } }
            composable(Routes.WORKOUTS) {
                WorkoutListScreen(
                    viewModel,
                    openGoals = { navController.navigate("${Routes.WORKOUT_GOAL}/${it.name}") },
                    openLive = { navController.navigate(Routes.WORKOUT_LIVE) { popUpTo(Routes.HOME) } }
                )
            }
            composable("${Routes.WORKOUT_GOAL}/{kind}") { entry ->
                val kind = entry.arguments?.getString("kind")
                    ?.let { runCatching { com.wearos.ancsbridge.health.WorkoutKind.valueOf(it) }.getOrNull() }
                    ?: com.wearos.ancsbridge.health.WorkoutKind.OTHER
                WorkoutGoalScreen(viewModel, kind) {
                    navController.navigate(Routes.WORKOUT_LIVE) { popUpTo(Routes.HOME) }
                }
            }
            composable(Routes.WORKOUT_LIVE) {
                LiveWorkoutScreen(viewModel) { navController.popBackStack(Routes.HOME, inclusive = false) }
            }
            composable(Routes.HEALTH) { HealthScreen(viewModel) { navController.navigate(Routes.BREATHE) } }
            composable(Routes.BREATHE) { BreatheScreen(onSaved = viewModel::syncHealthNow) }
            composable(Routes.TODAY) { TodayScreen(viewModel) }
            composable(Routes.MESSAGES) {
                MessagesScreen(
                    viewModel,
                    openConversation = { navController.navigate("${Routes.CONVERSATION}/${android.net.Uri.encode(it)}") },
                    openCompose = { navController.navigate(Routes.COMPOSE) },
                    openSetup = { navController.navigate(Routes.MESSAGES_SETUP) },
                )
            }
            composable(Routes.MESSAGES_SETUP) {
                MessagesSetupScreen(viewModel) {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                    navController.navigate(Routes.MESSAGES)
                }
            }
            composable("${Routes.CONVERSATION}/{address}") { entry ->
                val address = entry.arguments?.getString("address").orEmpty()
                ConversationScreen(viewModel, address) {
                    val name = viewModel.conversations.value
                        .firstOrNull { com.wearos.ancsbridge.messages.MapFormats.sameAddress(it.address, address) }?.name ?: address
                    navController.navigate("${Routes.REPLY}/${android.net.Uri.encode(address)}/${android.net.Uri.encode(name)}")
                }
            }
            composable(Routes.COMPOSE) {
                ComposeScreen(viewModel) { address, name ->
                    navController.navigate("${Routes.REPLY}/${android.net.Uri.encode(address)}/${android.net.Uri.encode(name)}")
                }
            }
            composable("${Routes.REPLY}/{address}/{name}") { entry ->
                val address = entry.arguments?.getString("address").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                ReplyScreen(viewModel, address, name) {
                    navController.popBackStack(Routes.MESSAGES, inclusive = false)
                    navController.navigate("${Routes.CONVERSATION}/${android.net.Uri.encode(address)}")
                }
            }
        }
    }
}

/** Items of a [ListScreen]; a plain list so previews can lay them out without lazy layout. */
internal class WearListBuilder {
    val items = mutableListOf<Pair<Any?, @Composable () -> Unit>>()
    fun item(key: Any? = null, content: @Composable () -> Unit) {
        items += key to content
    }
}

/** A scrolling screen with curved scroll indicator and fisheye scaling, like system apps. */
@Composable
internal fun ListScreen(build: WearListBuilder.() -> Unit) {
    val items = WearListBuilder().apply(build).items
    if (LocalInspectionMode.current) {
        // Previews/screenshot tests never run ScalingLazyColumn's first layout pass
        // (it renders empty there), so lay the same items out in a plain Column.
        ScreenScaffold {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) { items.forEach { (_, content) -> content() } }
        }
        return
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { (key, content) -> item(key = key) { content() } }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun HomeScreen(viewModel: MainViewModel, navigate: (String) -> Unit) {
    val state by viewModel.connectionState.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val mediaAvailable by viewModel.mediaAvailable.collectAsState()
    val phoneTools by viewModel.phoneTools.collectAsState()
    val hasBonded = remember(state) { viewModel.hasBondedIPhone() }
    val workout by viewModel.workoutState.collectAsState()
    val healthPending by viewModel.healthPending.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.wearos.ancsbridge.model.PhoneInfo.load(context) }
    val todayInfo by com.wearos.ancsbridge.model.PhoneInfo.current.collectAsState()
    HomeContent(
        state = state, hasBonded = hasBonded, nowPlaying = nowPlaying,
        workoutActive = workout !is com.wearos.ancsbridge.health.WorkoutState.Idle,
        healthPending = healthPending,
        todayInfo = todayInfo,
        mediaAvailable = mediaAvailable, phoneTools = phoneTools, navigate = navigate,
        onClearAll = viewModel::clearAllNotifications, onDisconnect = viewModel::disconnect,
        onReconnect = viewModel::reconnectNow
    )
}

@Composable
internal fun HomeContent(
    state: ConnectionState,
    hasBonded: Boolean,
    nowPlaying: NowPlaying,
    workoutActive: Boolean,
    healthPending: Int,
    todayInfo: com.wearos.ancsbridge.model.PhoneInfo = com.wearos.ancsbridge.model.PhoneInfo(),
    mediaAvailable: Boolean,
    phoneTools: Boolean,
    navigate: (String) -> Unit,
    onClearAll: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    ListScreen {
        item { StatusHeader(state, hasBonded) }

        // Workouts work without the iPhone, like on Apple Watch.
        item {
            if (workoutActive) {
                NavButton(Icons.AutoMirrored.Rounded.DirectionsRun, Color(0xFF4C7A00), "Workout in Progress",
                    "Tap to return") { navigate(Routes.WORKOUT_LIVE) }
            } else {
                NavButton(Icons.AutoMirrored.Rounded.DirectionsRun, Color(0xFF4C7A00), "Workout",
                    "Syncs to Apple Health") { navigate(Routes.WORKOUTS) }
            }
        }
        // Like iOS's own "expires soon" — the sideloaded iPhone app stops opening when it does.
        todayInfo.expiresInHours()?.let { hours ->
            item {
                NavButton(Icons.Rounded.Warning, Color(0xFFFF9F0A),
                    if (hours <= 0) "iPhone app expired" else "iPhone app expires in ${if (hours < 24) "$hours h" else "${hours / 24} d"}",
                    "Re-sign WearBridge with Sideloadly") { navigate(Routes.HEALTH) }
            }
        }
        item { TodaySummaryButton(todayInfo) { navigate(Routes.TODAY) } }
        item {
            NavButton(Icons.AutoMirrored.Rounded.Chat, Color(0xFF34C759), "Messages",
                "iMessage & SMS") { navigate(Routes.MESSAGES) }
        }
        item {
            NavButton(Icons.Rounded.Favorite, Color(0xFFFF375F), "Apple Health",
                when {
                    todayInfo.health == "missing" -> "Needs reinstall on iPhone"
                    healthPending > 0 -> "$healthPending waiting to sync"
                    else -> "Steps, calories, heart rate, sleep"
                }) {
                navigate(Routes.HEALTH)
            }
        }
        item {
            NavButton(Icons.Rounded.SelfImprovement, Color(0xFF5AC8FA), "Breathe", "Mindful minutes") {
                navigate(Routes.BREATHE)
            }
        }

        when (state) {
            is ConnectionState.Connected -> {
                if (mediaAvailable) item {
                    NavButton(
                        icon = Icons.Rounded.MusicNote,
                        tint = WearBridgeColors.Music,
                        label = "Now Playing",
                        secondary = when {
                            nowPlaying.hasTrack -> listOf(nowPlaying.title, nowPlaying.artist)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            nowPlaying.player.isNotBlank() -> nowPlaying.player
                            else -> "Control music on iPhone"
                        }
                    ) { navigate(Routes.NOW_PLAYING) }
                }
                if (phoneTools) item {
                    NavButton(Icons.Rounded.PhoneIphone, WearBridgeColors.Accent, "iPhone",
                        "Ping, camera, battery") { navigate(Routes.IPHONE) }
                }
                item {
                    NavButton(Icons.Rounded.Notifications, WearBridgeColors.Alert, "Notifications",
                        "Apps, previews, sync") { navigate(Routes.NOTIFICATIONS) }
                }
                item {
                    NavButton(Icons.Rounded.ClearAll, Color(0xFF8E8E93), "Clear All", null) {
                        onClearAll()
                    }
                }
                item {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2C1414),
                            contentColor = WearBridgeColors.Danger,
                            iconColor = WearBridgeColors.Danger
                        ),
                        icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                    ) { Text("Disconnect") }
                }
            }

            is ConnectionState.Connecting, is ConnectionState.Bonding -> {
                item { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
            }

            else -> {
                if (hasBonded) item {
                    Button(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    ) { Text("Reconnect") }
                }
                item {
                    NavButton(Icons.Rounded.Add, WearBridgeColors.Accent,
                        if (hasBonded) "Pair Again" else "Pair iPhone",
                        if (hasBonded) "Fix or replace the pairing" else null) { navigate(Routes.PAIR) }
                }
                if (hasBonded) item {
                    NavButton(Icons.Rounded.Notifications, WearBridgeColors.Alert, "Notifications",
                        "Apps, previews, sync") { navigate(Routes.NOTIFICATIONS) }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(state: ConnectionState, hasBonded: Boolean) {
    val connected = state is ConnectionState.Connected
    val (title, subtitle) = when (state) {
        is ConnectionState.Connected -> (state.deviceName ?: "iPhone") to "Connected"
        is ConnectionState.Connecting -> (state.deviceName ?: "iPhone") to "Connecting…"
        is ConnectionState.Bonding -> (state.deviceName ?: "iPhone") to "Accept the pairing request on iPhone"
        is ConnectionState.Error -> "iPhone" to state.message
        else -> if (hasBonded) "iPhone" to "Not nearby · reconnects automatically"
        else "WearBridge" to "Get iPhone notifications on this watch"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (connected) WearBridgeColors.Accent else Color(0xFF3A3A3C)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PhoneIphone, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Box(
                modifier = Modifier.size(12.dp).clip(CircleShape).background(Color.Black).padding(2.dp)
            ) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(
                    when (state) {
                        is ConnectionState.Connected -> WearBridgeColors.Online
                        is ConnectionState.Error -> WearBridgeColors.Danger
                        is ConnectionState.Connecting, is ConnectionState.Bonding -> WearBridgeColors.Alert
                        else -> Color(0xFF8E8E93)
                    }
                ))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = if (connected) WearBridgeColors.Online else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
internal fun NavButton(
    icon: ImageVector,
    tint: Color,
    label: String,
    secondary: String?,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { IconBadge(icon, tint) },
        secondaryLabel = secondary?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Colored circular icon, like the app icons in Apple Watch's settings lists. */
@Composable
internal fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------------------------------
// Now Playing — controls the iPhone's current player over Apple Media Service
// ---------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingScreen(viewModel: MainViewModel) {
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val supported by viewModel.supportedCommands.collectAsState()
    val artwork by AncsService.artwork.collectAsState()
    NowPlayingContent(nowPlaying, supported, artwork) { viewModel.sendMediaCommand(it.code) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingContent(
    nowPlaying: NowPlaying,
    supported: Set<Int>?,
    artwork: android.graphics.Bitmap? = null,
    send: (MediaCommand) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var crownDelta by remember { mutableFloatStateOf(0f) }
    fun can(command: MediaCommand) = MediaCommand.isSupported(supported, command)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Digital-crown volume, as on Apple Watch.
                .onRotaryScrollEvent { event ->
                    crownDelta += event.verticalScrollPixels
                    if (abs(crownDelta) > 48f) {
                        val up = crownDelta > 0
                        crownDelta = 0f
                        val command = if (up) MediaCommand.VOLUME_UP else MediaCommand.VOLUME_DOWN
                        if (can(command)) send(command)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            // Album art as a soft, dimmed backdrop, like Apple Watch's Now Playing.
            val art = remember(artwork) { artwork?.asImageBitmap() }
            if (art != null) {
                androidx.compose.foundation.Image(
                    art, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(28.dp).alpha(0.5f)
                )
                Box(Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.75f))
                    )
                ))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
            ) {
                if (art != null) {
                    androidx.compose.foundation.Image(
                        art, contentDescription = "Album art",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    nowPlaying.player.ifBlank { "iPhone" },
                    style = MaterialTheme.typography.labelSmall,
                    color = WearBridgeColors.Music, maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    nowPlaying.title.ifBlank { "Not Playing" },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                if (nowPlaying.artist.isNotBlank()) {
                    Text(
                        nowPlaying.artist, fontSize = 12.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.basicMarquee()
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { send(MediaCommand.PREVIOUS_TRACK) },
                        enabled = can(MediaCommand.PREVIOUS_TRACK),
                        colors = mediaIconColors(),
                    ) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(30.dp)) }
                    FilledIconButton(
                        onClick = { send(MediaCommand.TOGGLE_PLAYBACK) },
                        modifier = Modifier.size(60.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF3A3A3C), contentColor = Color.White
                        ),
                    ) {
                        Icon(
                            if (nowPlaying.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(
                        onClick = { send(MediaCommand.NEXT_TRACK) },
                        enabled = can(MediaCommand.NEXT_TRACK),
                        colors = mediaIconColors(),
                    ) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(30.dp)) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { send(MediaCommand.VOLUME_DOWN) },
                        enabled = can(MediaCommand.VOLUME_DOWN),
                        colors = mediaIconColors(),
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "Volume down", modifier = Modifier.size(18.dp)) }
                    VolumeBar(nowPlaying.volume, Modifier.width(56.dp))
                    IconButton(
                        onClick = { send(MediaCommand.VOLUME_UP) },
                        enabled = can(MediaCommand.VOLUME_UP),
                        colors = mediaIconColors(),
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume up", modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun mediaIconColors() = IconButtonDefaults.iconButtonColors(
    contentColor = Color.White,
    disabledContentColor = Color(0xFF636366)
)

/** iOS-style switches: dark card, green track when on. */
@Composable
internal fun appleSwitchColors() = SwitchButtonDefaults.switchButtonColors(
    checkedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    checkedContentColor = Color.White,
    checkedSecondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    checkedIconColor = WearBridgeColors.Accent,
    checkedTrackColor = WearBridgeColors.Online,
    checkedTrackBorderColor = WearBridgeColors.Online,
    checkedThumbColor = Color.White,
    checkedThumbIconColor = WearBridgeColors.Online,
)

@Composable
private fun VolumeBar(volume: Float?, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3C))) {
        Box(
            Modifier.fillMaxWidth(volume ?: 0f).height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White)
        )
    }
}

// ---------------------------------------------------------------------------------------
// iPhone tools (requires the WearBridge iPhone app)
// ---------------------------------------------------------------------------------------

@Composable
private fun PhoneToolsScreen(viewModel: MainViewModel) {
    val summary by viewModel.phoneSummary.collectAsState()
    LaunchedEffect(Unit) { viewModel.phoneControl(0) }

    ListScreen {
        item { ListHeader { Text("iPhone") } }
        item {
            Text(
                summary.ifBlank { "Loading battery & agenda…" },
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        item {
            NavButton(Icons.Rounded.NotificationsActive, WearBridgeColors.Accent, "Ping iPhone",
                "Find your iPhone") { viewModel.phoneControl(1) }
        }
        item {
            NavButton(Icons.Rounded.PhotoCamera, Color(0xFF8E8E93), "Camera Shutter",
                "Open Camera in WearBridge first") { viewModel.phoneControl(2) }
        }
        item {
            NavButton(Icons.Rounded.Refresh, Color(0xFF636366), "Refresh", null) { viewModel.phoneControl(0) }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Notification settings
// ---------------------------------------------------------------------------------------

@Composable
private fun NotificationSettingsScreen(viewModel: MainViewModel) {
    val preferences = viewModel.notificationPreferences
    val stats by viewModel.sessionStats.collectAsState()
    val context = LocalContext.current
    val apps = remember {
        preferences.apps().map { (id, name) ->
            AppToggle(id, name, preferences.isEnabled(id),
                AppIconMapper.badge(context, id, -1, 64).asImageBitmap())
        }
    }
    var showPreviews by remember { mutableStateOf(preferences.showPreviews) }
    var catchUp by remember { mutableStateOf(preferences.catchUp) }
    var syncDismissals by remember { mutableStateOf(preferences.syncDismissals) }

    NotificationSettingsContent(
        showPreviews = showPreviews,
        onShowPreviews = {
            showPreviews = it
            preferences.showPreviews = it
            viewModel.clearAllNotifications()
        },
        syncDismissals = syncDismissals,
        onSyncDismissals = { syncDismissals = it; preferences.syncDismissals = it },
        catchUp = catchUp,
        onCatchUp = { catchUp = it; preferences.catchUp = it },
        apps = apps,
        onAppToggle = { id, enabled -> preferences.setEnabled(id, enabled) },
        statsLine = "This connection: ${stats.received} events · ${stats.posted} shown" +
            if (stats.timeouts > 0) " · ${stats.timeouts} late" else "",
    )
}

internal data class AppToggle(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val badge: androidx.compose.ui.graphics.ImageBitmap,
)

@Composable
internal fun NotificationSettingsContent(
    showPreviews: Boolean,
    onShowPreviews: (Boolean) -> Unit,
    syncDismissals: Boolean,
    onSyncDismissals: (Boolean) -> Unit,
    catchUp: Boolean,
    onCatchUp: (Boolean) -> Unit,
    apps: List<AppToggle>,
    onAppToggle: (String, Boolean) -> Unit,
    statsLine: String,
) {
    ListScreen {
        item { ListHeader { Text("Notifications") } }
        item {
            SettingSwitch(showPreviews, Icons.Rounded.Visibility, "Previews",
                "Show message text", onShowPreviews)
        }
        item {
            SettingSwitch(syncDismissals, Icons.Rounded.Sync, "Sync Clearing",
                "Swipe clears it on iPhone", onSyncDismissals)
        }
        item {
            SettingSwitch(catchUp, Icons.Rounded.History, "Missed Alerts",
                "Show quietly on reconnect", onCatchUp)
        }

        item { ListHeader { Text("Apps") } }
        if (apps.isEmpty()) {
            item {
                Text(
                    "Apps appear here after their first alert.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }
        }
        apps.forEach { app ->
            item(key = app.id) {
                var enabled by remember { mutableStateOf(app.enabled) }
                SwitchButton(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        onAppToggle(app.id, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = appleSwitchColors(),
                    icon = { Image(app.badge, contentDescription = null, modifier = Modifier.size(26.dp)) },
                ) { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        item {
            Text(
                statsLine,
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    checked: Boolean,
    icon: ImageVector,
    label: String,
    secondary: String,
    onChange: (Boolean) -> Unit
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        colors = appleSwitchColors(),
        icon = { Icon(icon, contentDescription = null) },
        secondaryLabel = { Text(secondary, maxLines = 2) },
    ) { Text(label, maxLines = 1) }
}

// ---------------------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun PairScreen(viewModel: MainViewModel, onDone: () -> Unit) {
    val state by viewModel.connectionState.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    var paired by remember { mutableStateOf(viewModel.pairedPhones()) }
    var forgetResult by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(state) { if (state is ConnectionState.Connected) onDone() }

    ListScreen {
        item { ListHeader { Text("Pair iPhone") } }
        when (state) {
            is ConnectionState.Connecting, is ConnectionState.Bonding -> {
                item { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                item {
                    Text(
                        if (state is ConnectionState.Bonding) "Tap Pair on your iPhone, then Allow notifications"
                        else "Connecting…",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }
            else -> {
                item {
                    Text(
                        "Open WearBridge on your iPhone, keep it unlocked, then tap Search.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
                item {
                    Button(
                        onClick = { if (isScanning) viewModel.stopScan() else viewModel.scanAndConnect() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            else Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                        },
                        secondaryLabel = scanStatus.takeIf { it.isNotBlank() }?.let {
                            { Text(it, maxLines = 2) }
                        },
                    ) { Text(if (isScanning) "Stop" else "Search") }
                }
                if (paired.isNotEmpty()) {
                    item { ListHeader { Text("Already paired") } }
                    paired.forEach { (name, address) ->
                        item(key = address) {
                            NavButton(Icons.Rounded.PhoneIphone, WearBridgeColors.Accent, name, "Connect") {
                                viewModel.connectToDevice(address)
                            }
                        }
                    }
                }
                // After "Forget This Device" on the iPhone, the watch's old keys block pairing.
                item { ListHeader { Text("Trouble pairing?") } }
                item {
                    NavButton(Icons.Rounded.LinkOff, WearBridgeColors.Danger, "Forget iPhone",
                        "Start over after forgetting on iPhone") {
                        forgetResult = viewModel.forgetIPhone()
                        paired = viewModel.pairedPhones()
                    }
                }
                when (forgetResult) {
                    true -> item {
                        Text("Done. Now open Add New Watch in the iPhone app and tap Search.",
                            style = MaterialTheme.typography.labelSmall, color = WearBridgeColors.Online,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    false -> {
                        item {
                            Text("This watch won't let apps remove pairings. Remove your iPhone in Bluetooth settings, then come back and Search.",
                                style = MaterialTheme.typography.labelSmall, color = WearBridgeColors.Alert,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                        item {
                            Button(onClick = viewModel::openBluetoothSettings, modifier = Modifier.fillMaxWidth(),
                                icon = { Icon(Icons.Rounded.Bluetooth, contentDescription = null) }) { Text("Bluetooth Settings") }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}
