package com.merlin.fpslite

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile to toggle the overlay. */
class FpsTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        if (OverlayService.running) {
            OverlayService.stop(this)
            update()
            return
        }
        // Start through a tiny activity: starting a foreground service directly from
        // a tile click is subject to background-start restrictions on Android 12+.
        val i = Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (OverlayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
