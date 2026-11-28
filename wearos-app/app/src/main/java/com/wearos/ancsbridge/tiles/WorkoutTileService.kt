package com.wearos.ancsbridge.tiles

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wearos.ancsbridge.health.WorkoutKind
import com.wearos.ancsbridge.ui.MainActivity

internal object TileStyle {
    const val MOVE = 0xFFFA114F.toInt()
    const val EXERCISE = 0xFFA6FF00.toInt()
    const val STAND = 0xFF00F0FF.toInt()
    const val GREEN = 0xFFA6FF00.toInt()
    const val GREY = 0xFF98989F.toInt()

    fun dim(color: Int): Int = (color and 0x00FFFFFF) or (0x40 shl 24)

    fun launch(context: Context, target: String, id: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(
                        MainActivity.EXTRA_OPEN,
                        ActionBuilders.AndroidStringExtra.Builder().setValue(target).build()
                    )
                    .build()
            ).build()
        ).build()

    fun text(value: String, color: Int, size: Float, bold: Boolean = false): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setColor(argb(color)).setSize(sp(size))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    ).build()
            ).build()

    fun tile(layout: LayoutElementBuilders.LayoutElement, freshnessMs: Long): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(freshnessMs)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()

    fun resources(): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}

/** One-tap workouts, like the Workout app in Apple Watch's Smart Stack. */
class WorkoutTileService : TileService() {

    private val shortcuts = listOf(WorkoutKind.OUTDOOR_WALK, WorkoutKind.OUTDOOR_RUN, WorkoutKind.OUTDOOR_CYCLE)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(TileStyle.text("Workout", TileStyle.GREEN, 15f, bold = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
        shortcuts.forEach { kind ->
            column.addContent(chip(kind)).addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
        }
        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .addContent(column.build())
            .build()
        return Futures.immediateFuture(TileStyle.tile(layout, 0L))
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = TileStyle.resources()

    private fun chip(kind: WorkoutKind): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(150f)).setHeight(dp(36f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF222A12.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(18f)).build())
                            .build()
                    )
                    .setClickable(TileStyle.launch(this, MainActivity.OPEN_WORKOUT_PREFIX + kind.name, "start_${kind.name}"))
                    .build()
            )
            .addContent(TileStyle.text(kind.label, TileStyle.GREEN, 14f, bold = true))
            .build()
}
