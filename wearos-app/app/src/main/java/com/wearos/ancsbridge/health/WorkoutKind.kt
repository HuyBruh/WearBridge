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
