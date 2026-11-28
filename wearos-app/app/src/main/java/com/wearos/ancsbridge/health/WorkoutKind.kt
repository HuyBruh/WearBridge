package com.wearos.ancsbridge.health

import androidx.health.services.client.data.ExerciseType

/**
 * Workouts offered on the watch, modelled on the Apple Workout app list.
 * [healthKit] is the HKWorkoutActivityType name the iPhone app maps to.
 */
enum class WorkoutKind(
    val label: String,
    val exercise: ExerciseType,
    val healthKit: String,
    val indoor: Boolean,
    val usesGps: Boolean,
    val showsDistance: Boolean,
) {
    OUTDOOR_WALK("Outdoor Walk", ExerciseType.WALKING, "walking", false, true, true),
    OUTDOOR_RUN("Outdoor Run", ExerciseType.RUNNING, "running", false, true, true),
    OUTDOOR_CYCLE("Outdoor Cycle", ExerciseType.BIKING, "cycling", false, true, true),
    INDOOR_WALK("Indoor Walk", ExerciseType.WALKING, "walking", true, false, true),
    INDOOR_RUN("Indoor Run", ExerciseType.RUNNING_TREADMILL, "running", true, false, true),
    INDOOR_CYCLE("Indoor Cycle", ExerciseType.BIKING_STATIONARY, "cycling", true, false, false),
    HIKING("Hiking", ExerciseType.HIKING, "hiking", false, true, true),
    STRENGTH("Strength Training", ExerciseType.STRENGTH_TRAINING, "traditionalStrengthTraining", true, false, false),
    HIIT("HIIT", ExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING, "highIntensityIntervalTraining", true, false, false),
    YOGA("Yoga", ExerciseType.YOGA, "yoga", true, false, false),
    ELLIPTICAL("Elliptical", ExerciseType.ELLIPTICAL, "elliptical", true, false, false),
    ROWER("Rower", ExerciseType.ROWING_MACHINE, "rowing", true, false, false),
    STAIR_STEPPER("Stair Stepper", ExerciseType.STAIR_CLIMBING_MACHINE, "stairClimbing", true, false, false),
    DANCE("Dance", ExerciseType.DANCING, "socialDance", true, false, false),
    OTHER("Other", ExerciseType.WORKOUT, "other", true, false, false);

    /** Pace (min/km) reads better than speed for these. */
    val showsPace: Boolean get() = showsDistance && this != OUTDOOR_CYCLE
}

/** Workout goal, like the "…" goal options in Apple's Workout app. */
data class WorkoutGoal(val type: Type, val target: Double) {
    enum class Type { OPEN, TIME, DISTANCE, CALORIES }

    val label: String get() = when (type) {
        Type.OPEN -> "Open"
        Type.TIME -> "${(target / 60_000).toInt()} min"
        Type.DISTANCE -> if (target % 1000 == 0.0) "${(target / 1000).toInt()} km" else "%.1f km".format(target / 1000)
        Type.CALORIES -> "${target.toInt()} kcal"
    }

    /** 0..1 (may exceed 1 once passed). */
    fun progress(activeMs: Long, distanceM: Double, kcal: Double): Float = when (type) {
        Type.OPEN -> 0f
        Type.TIME -> (activeMs / target).toFloat()
        Type.DISTANCE -> (distanceM / target).toFloat()
        Type.CALORIES -> (kcal / target).toFloat()
    }

    companion object {
        val OPEN = WorkoutGoal(Type.OPEN, 0.0)

        fun presets(kind: WorkoutKind): List<WorkoutGoal> = buildList {
            if (kind.showsDistance) listOf(1, 3, 5, 10).forEach { add(WorkoutGoal(Type.DISTANCE, it * 1000.0)) }
            listOf(15, 30, 45, 60).forEach { add(WorkoutGoal(Type.TIME, it * 60_000.0)) }
            listOf(100, 200, 300, 500).forEach { add(WorkoutGoal(Type.CALORIES, it.toDouble())) }
        }
    }
}
