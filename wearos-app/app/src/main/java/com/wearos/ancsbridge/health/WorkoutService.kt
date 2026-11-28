package com.wearos.ancsbridge.health

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.pauseExercise
import androidx.health.services.client.resumeExercise
import androidx.health.services.client.startExercise
import androidx.wear.ongoing.OngoingActivity
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** What the workout screens show. */
sealed interface WorkoutState {
    data object Idle : WorkoutState
    data class Starting(val kind: WorkoutKind) : WorkoutState
    data class Running(
        val kind: WorkoutKind,
        val paused: Boolean,
        /** Wall clock (ms) at which [activeMsAtCheckpoint] was measured. */
        val checkpointMs: Long,
        val activeMsAtCheckpoint: Long,
        val heartRate: Int?,
        val heartRateAvailable: Boolean,
        val kcal: Double,
        val distanceM: Double,
        val speedMps: Double?,
        val gpsReady: Boolean,
    ) : WorkoutState {
        fun activeMs(now: Long = System.currentTimeMillis()): Long =
            if (paused) activeMsAtCheckpoint else activeMsAtCheckpoint + (now - checkpointMs).coerceAtLeast(0)
    }
    data class Summary(val record: WorkoutRecord) : WorkoutState
    data class Failed(val message: String) : WorkoutState
}

/**
 * Runs a workout with Health Services (heart rate, calories, distance, GPS) in a
 * foreground service, shows the ongoing-activity chip on the watch face like Apple
 * Watch's workout indicator, and queues the result for Apple Health.
 */
class WorkoutService : Service() {

    companion object {
        private const val TAG = "WorkoutService"
        private const val CHANNEL = "workout"
        private const val NOTIFICATION_ID = 77
        private const val ACTION_START = "com.wearos.ancsbridge.workout.START"
        private const val ACTION_PAUSE = "com.wearos.ancsbridge.workout.PAUSE"
        private const val ACTION_RESUME = "com.wearos.ancsbridge.workout.RESUME"
        private const val ACTION_END = "com.wearos.ancsbridge.workout.END"
        private const val EXTRA_KIND = "kind"
        private const val PREFS = "workout_session"
        private const val HR_INTERVAL_MS = 5_000L
        private const val ROUTE_INTERVAL_MS = 3_000L

        private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
        val state: StateFlow<WorkoutState> = _state.asStateFlow()

        fun start(context: Context, kind: WorkoutKind) {
            _state.value = WorkoutState.Starting(kind)
            ContextCompat.startForegroundService(
                context, Intent(context, WorkoutService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_KIND, kind.name)
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun end(context: Context) = send(context, ACTION_END)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, WorkoutService::class.java).setAction(action))
        }

        fun dismissSummary() {
            if (_state.value is WorkoutState.Summary || _state.value is WorkoutState.Failed) {
                _state.value = WorkoutState.Idle
            }
        }

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val bootInstant: Instant
        get() = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    private var kind: WorkoutKind = WorkoutKind.OTHER
    private var startMs = 0L
    private var gpsEnabled = false
    private val heartRate = ArrayList<Pair<Long, Int>>()
    private val route = ArrayList<WorkoutRecord.RoutePoint>()
    private val pauses = ArrayList<Pair<Long, Long>>()
    private var pauseStartedMs: Long? = null
    private var latestHr: Int? = null
    private var hrAvailable = false
    private var gpsReady = false
    private var kcal = 0.0
    private var distance = 0.0
    private var steps: Long? = null
    private var speed: Double? = null
    private var checkpointMs = 0L
    private var activeAtCheckpoint = 0L
    private var paused = false
    private var finished = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = handleUpdate(update)
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise callback registration failed", throwable)
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            val available = availability == DataTypeAvailability.AVAILABLE
            when (dataType) {
                DataType.HEART_RATE_BPM -> hrAvailable = available
                DataType.LOCATION -> gpsReady = available
            }
            publish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requested = intent.getStringExtra(EXTRA_KIND)
                    ?.let { runCatching { WorkoutKind.valueOf(it) }.getOrNull() } ?: WorkoutKind.OTHER
                // FOREGROUND_SERVICE_TYPE_HEALTH throws without Sensors/Activity permission.
                val started = runCatching { goForeground(requested) }.onFailure {
                    Log.e(TAG, "Cannot start workout foreground service", it)
                }.isSuccess
                if (started) scope.launch { begin(requested) } else {
                    _state.value = WorkoutState.Failed("Allow Physical activity and Sensors for WearBridge in Settings › Apps")
                    stopSelf()
                }
            }
            ACTION_PAUSE -> scope.launch { runCatching { exerciseClient.pauseExercise() } }
            ACTION_RESUME -> scope.launch { runCatching { exerciseClient.resumeExercise() } }
            ACTION_END -> scope.launch {
                runCatching { exerciseClient.endExercise() }.onFailure {
                    Log.w(TAG, "endExercise failed", it)
                    finish()
                }
            }
            null -> {
                // Restarted by the system mid-workout: reattach to the running exercise.
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("kind", null)
                val restored = saved?.let { runCatching { WorkoutKind.valueOf(it) }.getOrNull() }
                if (restored == null) {
                    stopSelf()
                } else if (runCatching { goForeground(restored) }.isSuccess) {
                    scope.launch { reattach(restored) }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground(kind: WorkoutKind) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_WORKOUT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fitness)
            .setContentTitle(kind.label)
            .setContentText("Workout in progress")
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(open)
        // Watch-face chip, like Apple Watch's running-workout indicator.
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_fitness)
            .setTouchIntent(open)
            .build()
            .apply(this)
        val notification: Notification = builder.build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (kind.usesGps && hasLocationPermission(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private suspend fun begin(requested: WorkoutKind) {
        kind = requested
        try {
            val supported = exerciseClient.getCapabilities()
                .getExerciseTypeCapabilities(kind.exercise).supportedDataTypes
            gpsEnabled = kind.usesGps && hasLocationPermission(this) && DataType.LOCATION in supported
            val wanted = buildSet<DataType<*, *>> {
                add(DataType.HEART_RATE_BPM)
                add(DataType.CALORIES_TOTAL)
                if (kind.showsDistance) add(DataType.DISTANCE_TOTAL)
                add(DataType.STEPS_TOTAL)
                if (kind.showsDistance) add(DataType.SPEED)
                if (gpsEnabled) add(DataType.LOCATION)
            }.filter { it in supported }.toSet()

            exerciseClient.setUpdateCallback(ContextCompat.getMainExecutor(this), callback)
            exerciseClient.startExercise(
                ExerciseConfig.builder(kind.exercise)
                    .setDataTypes(wanted)
                    .setIsGpsEnabled(gpsEnabled)
                    .setIsAutoPauseAndResumeEnabled(false)
                    .build()
            )
            resetSession()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("kind", kind.name).putLong("start", startMs).apply()
            publish()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start workout", e)
            _state.value = WorkoutState.Failed(
                if (e is SecurityException) "Allow Physical activity, Sensors and Location for WearBridge"
                else "Could not start workout"
            )
            stopEverything()
        }
    }

    private suspend fun reattach(restored: WorkoutKind) {
        kind = restored
        val info = runCatching { exerciseClient.getCurrentExerciseInfo() }.getOrNull()
        if (info?.exerciseTrackedStatus != ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
            stopEverything()
            return
        }
        resetSession()
        startMs = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("start", startMs)
        exerciseClient.setUpdateCallback(ContextCompat.getMainExecutor(this), callback)
        publish()
    }

    private fun resetSession() {
        startMs = System.currentTimeMillis()
        heartRate.clear(); route.clear(); pauses.clear()
        pauseStartedMs = null; latestHr = null; kcal = 0.0; distance = 0.0
        steps = null; speed = null; paused = false; finished = false
        checkpointMs = startMs; activeAtCheckpoint = 0L
    }

    private fun handleUpdate(update: ExerciseUpdate) {
        val boot = bootInstant
        update.startTime?.let { startMs = it.toEpochMilli() }
        update.activeDurationCheckpoint?.let {
            checkpointMs = it.time.toEpochMilli()
            activeAtCheckpoint = it.activeDuration.toMillis()
        }

        val metrics = update.latestMetrics
        metrics.getData(DataType.HEART_RATE_BPM).forEach { point ->
            val bpm = point.value.toInt()
            if (bpm in 30..240) {
                latestHr = bpm
                val t = point.getTimeInstant(boot).toEpochMilli()
                if (heartRate.isEmpty() || t - heartRate.last().first >= HR_INTERVAL_MS) heartRate += t to bpm
            }
        }
        metrics.getData(DataType.CALORIES_TOTAL)?.let { kcal = it.total }
        metrics.getData(DataType.DISTANCE_TOTAL)?.let { distance = it.total }
        metrics.getData(DataType.STEPS_TOTAL)?.let { steps = it.total }
        metrics.getData(DataType.SPEED).lastOrNull()?.let { speed = it.value }
        metrics.getData(DataType.LOCATION).forEach { point ->
            val t = point.getTimeInstant(boot).toEpochMilli()
            if (route.isEmpty() || t - route.last().t >= ROUTE_INTERVAL_MS) {
                val loc = point.value
                val alt = loc.altitude.takeIf { it.isFinite() && kotlin.math.abs(it) < 20_000 } // "unavailable" is a sentinel
                route += WorkoutRecord.RoutePoint(t, loc.latitude, loc.longitude, alt ?: Double.NaN)
            }
        }

        val exerciseState = update.exerciseStateInfo.state
        val now = System.currentTimeMillis()
        when {
            exerciseState.isEnded -> {
                finish()
                return
            }
            exerciseState.isPaused && !paused -> {
                paused = true
                pauseStartedMs = now
            }
            exerciseState == ExerciseState.ACTIVE && paused -> {
                paused = false
                pauseStartedMs?.let { pauses += it to now }
                pauseStartedMs = null
            }
        }
        publish()
    }

    private fun publish() {
        if (finished) return
        _state.value = WorkoutState.Running(
            kind = kind, paused = paused, checkpointMs = checkpointMs,
            activeMsAtCheckpoint = activeAtCheckpoint, heartRate = latestHr,
            heartRateAvailable = hrAvailable, kcal = kcal, distanceM = distance,
            speedMps = speed, gpsReady = gpsReady || !gpsEnabled
        )
    }

    private fun finish() {
        if (finished) return
        finished = true
        val end = System.currentTimeMillis()
        pauseStartedMs?.let { pauses += it to end }
        val activeMs = if (paused) activeAtCheckpoint else activeAtCheckpoint + (end - checkpointMs).coerceAtLeast(0)
        val record = WorkoutRecord(
            kind = kind, startMs = startMs, endMs = end, activeMs = activeMs,
            kcal = kcal.takeIf { it > 0 }, distanceM = distance.takeIf { it > 0 && kind.showsDistance },
            steps = steps, heartRate = heartRate.toList(), route = route.toList(), pauses = pauses.toList()
        )
        // Very short sessions are almost always accidental starts: show but don't sync.
        if (activeMs >= 60_000) {
            HealthOutbox(this).add(record.toJson())
            startService(Intent(this, AncsService::class.java).setAction(AncsService.ACTION_SYNC_HEALTH))
        }
        _state.value = WorkoutState.Summary(record)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        stopEverything()
    }

    private fun stopEverything() {
        scope.launch {
            runCatching { exerciseClient.clearUpdateCallback(callback) }
            ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
