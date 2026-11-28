package com.wearos.ancsbridge.ancs

import android.content.Context

/** Stores app labels and delivery preferences without storing notifications. */
class NotificationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE)

    fun rememberApp(id: String, name: String) {
        if (id.isNotBlank() && prefs.getString("name:$id", null) != name) {
            prefs.edit().putString("name:$id", name).apply()
        }
    }

    fun apps(): List<Pair<String, String>> = prefs.all.mapNotNull { (key, value) ->
        if (key.startsWith("name:") && value is String) key.removePrefix("name:") to value
        else null
    }.sortedBy { (_, name) -> name.lowercase() }

    fun isEnabled(id: String): Boolean = prefs.getBoolean("enabled:$id", true)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled:$id", enabled).apply()
    }

    var syncDismissals: Boolean
        get() = prefs.getBoolean("sync_dismissals", false)
        set(value) { prefs.edit().putBoolean("sync_dismissals", value).apply() }

    var catchUp: Boolean
        get() = prefs.getBoolean("catch_up", false)
        set(value) { prefs.edit().putBoolean("catch_up", value).apply() }

    var showPreviews: Boolean
        get() = prefs.getBoolean("show_previews", true)
        set(value) { prefs.edit().putBoolean("show_previews", value).apply() }
}
