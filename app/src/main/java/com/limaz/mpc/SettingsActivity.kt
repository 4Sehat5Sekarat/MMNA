package com.limaz.mpc

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * Opened by long-pressing the tile (QS_TILE_PREFERENCES).
 * Has no UI. Creates the config files if missing, validates the config, and shows a toast.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The config root is hidden while the app is running, so editing is blocked
        if (AppState.running) {
            toast(getString(R.string.settings_app_running))
            finish()
            return
        }

        val existed = File(filesDir, "config/config.json").exists()
        try {
            ConfigManager.load(this) // creates defaults if missing, and validates
            toast(getString(
                if (existed) R.string.settings_config_ready else R.string.settings_config_created
            ))
        } catch (e: ConfigException) {
            toast(getString(R.string.settings_config_error, e.text(this)))
        }

        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}