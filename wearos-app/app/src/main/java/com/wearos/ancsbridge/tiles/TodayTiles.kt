package com.wearos.ancsbridge.tiles

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.model.PhoneInfo
import com.wearos.ancsbridge.ui.MainActivity
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private fun clock(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
private fun text(value: String) = PlainComplicationText.Builder(value).build()

private fun openToday(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 42,
    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TODAY),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** Weather and the next two events, like Apple Watch's Smart Stack. */
class TodayTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val info = PhoneInfo.load(this)
        val now = System.currentTimeMillis()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        val weather = info.weather
        if (weather != null) {
            column.addContent(TileStyle.text(weather.place.ifBlank { "Weather" }, TileStyle.GREY, 12f))
            column.addContent(TileStyle.text("${weather.temp.roundToInt()}° ${weather.condition}", 0xFFFFFFFF.toInt(), 20f, bold = true))
            if (weather.hi != null && weather.lo != null) {
                column.addContent(TileStyle.text("H:${weather.hi.roundToInt()}°  L:${weather.lo.roundToInt()}°", TileStyle.GREY, 12f))
            }
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
        }
        val events = info.events.filter { it.endMs > now }.take(2)
        if (events.isEmpty()) {
            column.addContent(TileStyle.text("No more events", TileStyle.GREY, 13f))
        }
        events.forEach { event ->
            val color = event.color ?: 0xFFFF453A.toInt()
            column.addContent(TileStyle.text(if (event.allDay) "All day" else clock(event.startMs), color, 12f, bold = true))
            column.addContent(TileStyle.text(event.title.take(22), 0xFFFFFFFF.toInt(), 14f))
        }
        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setClickable(TileStyle.launch(this, MainActivity.OPEN_TODAY, "open_today")).build())
            .addContent(column.build())
            .build()
        return Futures.immediateFuture(TileStyle.tile(layout, 30 * 60_000L))
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = TileStyle.resources()
}

/** Current temperature on the watch face. */
class WeatherComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, "28°", "Cloudy", null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val weather = PhoneInfo.load(this).weather
        val stale = weather == null || System.currentTimeMillis() - weather.atMs > 3 * 3_600_000L
        return build(
            request.complicationType,
            if (stale) "--°" else "${weather!!.temp.roundToInt()}°",
            weather?.condition ?: "Weather",
            openToday(this)
        )
    }

    private fun build(type: ComplicationType, temp: String, condition: String, tap: PendingIntent?): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(temp), text("$temp $condition"))
                .setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$temp $condition"), text("$temp $condition"))
                .setTapAction(tap).build()
            else -> null
        }
}

/** Next calendar event on the watch face. */
class NextEventComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, "14:00", "Team meeting", null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val event = PhoneInfo.load(this).nextEvent()
        return build(
            request.complicationType,
            event?.let { if (it.allDay) "All day" else clock(it.startMs) } ?: "--",
            event?.title ?: "No events",
            openToday(this)
        )
    }

    private fun build(type: ComplicationType, time: String, title: String, tap: PendingIntent?): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_calendar)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(time), text("$time $title"))
                .setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(title), text("$time $title"))
                .setTitle(text(time)).setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}
