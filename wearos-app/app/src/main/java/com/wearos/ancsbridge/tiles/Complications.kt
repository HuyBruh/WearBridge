package com.wearos.ancsbridge.tiles

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ui.MainActivity

private fun text(value: String) = PlainComplicationText.Builder(value).build()

private fun openApp(context: Context, target: String?): PendingIntent = PendingIntent.getActivity(
    context, target.hashCode(),
    Intent(context, MainActivity::class.java).apply { target?.let { putExtra(MainActivity.EXTRA_OPEN, it) } },
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** The iPhone's battery level on the watch face, like Apple Watch's Batteries complication. */
class PhoneBatteryComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val PREFS = "phone_status"

        /** Battery % from the iPhone app's status summary ("iPhone battery: 82%"). */
        fun parseBattery(summary: String): Int? =
            Regex("""battery:\s*(\d{1,3})%""", RegexOption.IGNORE_CASE).find(summary)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

        fun save(context: Context, summary: String) {
            val level = parseBattery(summary) ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("battery", level).putLong("at", System.currentTimeMillis()).apply()
            TileUpdates.phone(context)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 82)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val fresh = System.currentTimeMillis() - prefs.getLong("at", 0) < 2 * 60 * 60_000L
        val level = prefs.getInt("battery", -1).takeIf { fresh && it >= 0 }
        return build(request.complicationType, level)
    }

    private fun build(type: ComplicationType, level: Int?): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_phone)).build()
        val label = level?.let { "$it%" } ?: "--"
        val description = text(level?.let { "iPhone battery $it percent" } ?: "iPhone battery unknown")
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (level ?: 0).toFloat(), min = 0f, max = 100f, contentDescription = description
            ).setText(text(label)).setMonochromaticImage(icon).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(label), description)
                .setMonochromaticImage(icon).build()
            else -> null
        }
    }
}
