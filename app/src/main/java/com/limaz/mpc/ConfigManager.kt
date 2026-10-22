package com.limaz.mpc

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Config error. Carries a string resource id so the message can be localized. */
class ConfigException(val messageRes: Int, vararg val args: Any) : Exception()

/** Localized message for this error. */
fun ConfigException.text(context: Context): String =
    context.getString(messageRes, *args)

data class MpdConfig(
    val host: String,
    val port: Int,
    val password: String,
    val timeoutMs: Int
)

data class AppConfig(
    val mpd: MpdConfig,
    val idleTimeoutSec: Int,
    val reconnectDelayMs: Long,
    val albumArt: Boolean,
    val artMaxSize: Int,
    val artPreload: String,
    val events: Map<String, String>,
    val notificationButtons: List<String>,
    val volumeKeys: String,
    val volumeStep: Int
)

object ConfigManager {

    private const val DIR = "config"
    private const val FILE = "config.json"
    private const val README_FILE = "README.txt"

    private val allowedEvents = setOf(
        "wired_disconnect", "wired_connect",
        "bt_disconnect", "bt_connect"
    )
    private val allowedActions = setOf("pause", "play", "stop")
    private val allowedButtons = setOf("prev", "play", "next", "stop", "repeat", "random")
    private val allowedVolumeKeys = setOf("system", "mpd")
    private val allowedPreload = setOf("off", "queue", "library")

    /**
     * Loads config/config.json. Missing files are created from the bundled defaults
     * (res/raw/default_config.json and res/raw/config_readme.txt).
     */
    @Throws(ConfigException::class)
    fun load(context: Context): AppConfig {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        copyRawIfMissing(context, R.raw.config_readme, File(dir, README_FILE))
        copyRawIfMissing(context, R.raw.default_config, File(dir, FILE))

        val text = try {
            File(dir, FILE).readText()
        } catch (e: Exception) {
            throw ConfigException(R.string.err_config_read)
        }
        return parse(text)
    }

    private fun copyRawIfMissing(context: Context, rawId: Int, target: File) {
        if (target.exists()) return
        context.resources.openRawResource(rawId).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Throws(ConfigException::class)
    fun parse(text: String): AppConfig {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ConfigException(R.string.err_config_syntax, e.message ?: "unknown")
        }

        val mpdObj = root.optJSONObject("mpd") ?: JSONObject()
        val host = mpdObj.optString("host", "127.0.0.1")
        val port = mpdObj.optInt("port", 6600)
        val password = mpdObj.optString("password", "")
        val timeoutMs = mpdObj.optInt("timeout_ms", 5000)

        if (host.isBlank()) throw ConfigException(R.string.err_host_empty)
        if (port !in 1..65535) throw ConfigException(R.string.err_port_range)
        if (timeoutMs <= 0) throw ConfigException(R.string.err_timeout)

        val idleTimeout = root.optInt("idle_timeout_sec", 60)
        if (idleTimeout <= 0) throw ConfigException(R.string.err_idle_timeout)

        val reconnectDelay = root.optLong("reconnect_delay_ms", 2000L)
        if (reconnectDelay < 500) throw ConfigException(R.string.err_reconnect_delay)

        val albumArt = root.optBoolean("album_art", true)

        val artMax = root.optInt("art_max_size", 256)
        if (artMax !in 64..1024) throw ConfigException(R.string.err_art_size)

        val artPreload = root.optString("art_preload", "queue")
        if (artPreload !in allowedPreload) throw ConfigException(R.string.err_art_preload)

        val events = mutableMapOf<String, String>()
        val eventsObj = root.optJSONObject("events") ?: JSONObject()
        val keys = eventsObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val action = eventsObj.optString(name)
            if (name !in allowedEvents) throw ConfigException(R.string.err_event_unknown, name)
            if (action !in allowedActions) throw ConfigException(R.string.err_action_unknown, action)
            events[name] = action
        }

        val buttonsArr = root.optJSONArray("notification_buttons")
        val buttons = if (buttonsArr == null) {
            listOf("prev", "play", "next")
        } else {
            (0 until buttonsArr.length()).map { buttonsArr.optString(it) }
        }
        if (buttons.size > 5) throw ConfigException(R.string.err_buttons_max)
        if (buttons.distinct().size != buttons.size) throw ConfigException(R.string.err_buttons_duplicate)
        buttons.forEach {
            if (it !in allowedButtons) throw ConfigException(R.string.err_button_unknown, it)
        }

        val volumeKeys = root.optString("volume_keys", "system")
        if (volumeKeys !in allowedVolumeKeys) throw ConfigException(R.string.err_volume_keys)

        val volumeStep = root.optInt("volume_step", 5)
        if (volumeStep !in 1..50) throw ConfigException(R.string.err_volume_step)

        return AppConfig(
            mpd = MpdConfig(host, port, password, timeoutMs),
            idleTimeoutSec = idleTimeout,
            reconnectDelayMs = reconnectDelay,
            albumArt = albumArt,
            artMaxSize = artMax,
            artPreload = artPreload,
            events = events,
            notificationButtons = buttons,
            volumeKeys = volumeKeys,
            volumeStep = volumeStep
        )
    }
}