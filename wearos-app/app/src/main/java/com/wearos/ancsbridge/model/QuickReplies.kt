package com.wearos.ancsbridge.model

import android.content.Context
import org.json.JSONArray

/**
 * Quick replies edited in the iPhone app (Settings › Quick Reply Messages), read over
 * BLE on every connection and cached here so they work while the iPhone is away.
 */
object QuickReplies {
    private const val PREFS = "quick_replies"
    private const val KEY = "list"

    val DEFAULTS = listOf(
        "Can't talk right now.",
        "Hey! Can't talk rn, what's up?",
        "I'll call you back shortly.",
        "In a meeting, will call later.",
        "Driving right now, will call later."
    )

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return DEFAULTS
        return parse(raw) ?: DEFAULTS
    }

    /** Stores the JSON array sent by the iPhone; ignores anything malformed. */
    fun save(context: Context, json: String): Boolean {
        val list = parse(json) ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(list).toString()).apply()
        return true
    }

    fun parse(json: String): List<String>? = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getString(it).trim() }
            .filter { it.isNotEmpty() }.map { it.take(160) }.take(12)
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
