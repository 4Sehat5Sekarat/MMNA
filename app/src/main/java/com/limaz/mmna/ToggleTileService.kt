package com.limaz.mmna

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile. Starts and stops MprisService. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, MprisService::class.java)
        val turningOn = !AppState.running

        if (turningOn) {
            // startForegroundService exists from API 26. Android 7 uses startService.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(intent)
        }

        // Update the tile right away. MprisService corrects it if startup fails.
        qsTile?.apply {
            state = if (turningOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        tile.label = getString(R.string.app_name)
        tile.state = if (AppState.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}