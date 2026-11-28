package com.wearos.ancsbridge.tiles

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Ask the system to redraw our tiles and watch-face complications. */
object TileUpdates {
    fun activity(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(ActivityTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, MoveComplicationService::class.java)
            ).requestUpdateAll()
        }
    }

    fun phone(context: Context) {
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, PhoneBatteryComplicationService::class.java)
            ).requestUpdateAll()
        }
    }
}
