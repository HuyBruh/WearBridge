# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep ANCS model classes
-keep class com.wearos.ancsbridge.model.** { *; }
-keep class com.wearos.ancsbridge.data.** { *; }

# Enum names are stored in preferences and navigation routes (WorkoutKind, goal types).
-keepclassmembers enum com.wearos.ancsbridge.** { *; }
-keepnames enum com.wearos.ancsbridge.** { *; }
