package com.limaz.mpc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/** Foreground media service. Runs only while the quick settings tile is on. */
class MprisService : Service() {

    companion object {
        private const val TAG = "MprisService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1
        private const val IDLE_CHANNEL_ID = "idle"
        private const val NOTIF_IDLE_ID = 2

        const val ACTION_REPEAT = "com.limaz.mpc.ACTION_REPEAT"
        const val ACTION_RANDOM = "com.limaz.mpc.ACTION_RANDOM"

        private val REFRESH_EVENTS = setOf("player", "playlist", "mixer", "options")

        private val ACTIONS: Long =
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
    }

    private lateinit var config: AppConfig
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var cmdClient: MpdClient
    private lateinit var idleClient: MpdClient
    private var foregroundId = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val commands = Executors.newSingleThreadExecutor()

    @Volatile private var starting = false
    @Volatile private var running = false
    @Volatile private var playing = false

    private var mpdRepeat = false
    private var mpdSingle = false
    private var mpdRandom = false
    private var volumeProvider: VolumeProviderCompat? = null
    @Volatile private var shadowVolume = -1
    private val pendingVol = AtomicInteger(0)

    private var lastArtDir: String? = null
    private var lastArt: Bitmap? = null
    private var artStore: ArtStore? = null
    private var preloader: ArtPreloader? = null
    private var nowTitle = ""
    private var nowArtist = ""
    private var nowArt: Bitmap? = null

    /** Shown while the cover is loading, and for folders that have no cover. */
    private val placeholderArt: Bitmap by lazy {
        ContextCompat.getDrawable(this, R.drawable.ic_mpd_art_placeholder)!!
            .toBitmap(256, 256)
    }

    /** Default buttons until the config has been loaded. */
    private val buttons: List<String>
        get() = if (::config.isInitialized) config.notificationButtons
        else listOf("prev", "play", "next")

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        session = MediaSessionCompat(this, "MpdMpris")
        session.setCallback(sessionCallback, mainHandler)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only becomes foreground once. Media button intents must not swap the notification.
        if (foregroundId == 0) showForeground(NOTIF_IDLE_ID, buildIdleNotification())

        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                MediaButtonReceiver.handleIntent(session, intent)
                return START_NOT_STICKY
            }
            ACTION_REPEAT -> {
                cycleRepeat()
                return START_NOT_STICKY
            }
            ACTION_RANDOM -> {
                toggleRandom()
                return START_NOT_STICKY
            }
        }

        if (running || starting) return START_NOT_STICKY
        starting = true

        config = try {
            ConfigManager.load(this)
        } catch (e: ConfigException) {
            toast(e.text(this))
            stopNow("config error")
            return START_NOT_STICKY
        }

        Thread({ startUp() }, "mpd-startup").start()
        return START_NOT_STICKY
    }

    private fun startUp() {
        val store = ArtStore(this, config.artMaxSize)
        artStore = store
        if (config.albumArt && config.artPreload != "off") {
            preloader = ArtPreloader(store, config.mpd)
        }

        cmdClient = newClient()
        idleClient = newClient()
        try {
            cmdClient.connect()
            idleClient.connect()
            running = true
            AppState.running = true
            refreshTile()
            setupVolumeKeys(cmdClient.status().volume)
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            Thread(::idleLoop, "mpd-idle").start()
            refresh(cmdClient)
            // "library" indexes the whole library once at start, "queue" only the queue
            schedulePreload(full = config.artPreload == "library")
        } catch (e: MpdException) {
            toast(getString(R.string.toast_connect_failed, e.message ?: ""))
            refreshTile()
            stopNow("startup failed")
        }
    }

    private fun newClient() = MpdClient(
        config.mpd.host, config.mpd.port, config.mpd.password, config.mpd.timeoutMs
    )

    /** Event loop. Blocks in idle(), so there is no polling. */
    private fun idleLoop() {
        while (running) {
            try {
                val changed = idleClient.idle()
                if (changed.any { it in REFRESH_EVENTS }) refresh(idleClient)
                if ("playlist" in changed) schedulePreload(full = false)
                if ("database" in changed) schedulePreload(full = config.artPreload == "library")
            } catch (e: MpdException) {
                if (!running) break
                Log.w(TAG, "connection lost: ${e.message}")
                if (!reconnect()) {
                    stopNow("reconnect timeout")
                    break
                }
            }
        }
    }

    /** Retries the connection until idle_timeout_sec runs out. */
    private fun reconnect(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + config.idleTimeoutSec * 1000L
        while (running) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                toast(getString(R.string.toast_mpd_timeout))
                return false
            }
            try {
                commands.submit(Callable { cmdClient.connect() }).get()
                idleClient.connect()
                refresh(idleClient)
                return true
            } catch (e: Exception) {
                Thread.sleep(config.reconnectDelayMs)
            }
        }
        return false
    }

    private fun refresh(client: MpdClient) {
        val st = client.status()
        val song = client.currentSong()
        val dirKey = song?.file?.substringBeforeLast('/', "")
        val sameArt = dirKey != null && dirKey == lastArtDir

        // Step 1: text appears immediately
        mainHandler.post { applyState(st, song, if (sameArt) lastArt else null) }

        // Step 2: cover follows if this folder has not been checked yet
        if (!sameArt) {
            val art = artFor(song?.file)
            mainHandler.post { applyState(st, song, art) }
        }
    }

    /**
     * Asks the preloader to fill the cache.
     * full = whole library (also prunes removed folders), false = queue only.
     */
    private fun schedulePreload(full: Boolean) {
        val p = preloader ?: return
        command {
            val files = send(if (full) "listall" else "playlistinfo")
                .filter { it.first == "file" }
                .map { it.second }
            if (full) p.submitLibrary(files) else p.submit(files)
        }
    }

    private fun artFor(file: String?): Bitmap? {
        val store = artStore ?: return null
        if (!config.albumArt || file == null) return null
        val dirKey = file.substringBeforeLast('/', "")
        return try {
            val bmp = store.thumbFor(dirKey) { MpdArt.fetch(config.mpd, file) }
            lastArtDir = dirKey
            lastArt = bmp
            bmp
        } catch (e: Exception) {
            // Failed (for example MPD dropped). Do not mark the folder, retry on the next track.
            Log.w(TAG, "cover fetch failed, will retry: ${e.message}")
            lastArtDir = null
            null
        }
    }

    private fun applyState(st: MpdStatus, song: MpdSong?, art: Bitmap?) {
        playing = st.state == "play"
        mpdRepeat = st.repeat
        mpdSingle = st.single
        mpdRandom = st.random
        nowTitle = song?.title
            ?: song?.file?.substringAfterLast('/')
                    ?: getString(R.string.default_title)
        nowArtist = song?.artist ?: ""

        val cover = art ?: placeholderArt
        nowArt = cover

        // Sync from MPD only when no button press is still being sent
        if (st.volume >= 0 && pendingVol.get() == 0) {
            shadowVolume = st.volume
            volumeProvider?.setCurrentVolume(st.volume)
        }

        // Session only exists while MPD is playing or paused.
        // While stopped, KDE Connect and similar clients would show an empty player.
        val hasMedia = st.state != "stop"
        if (session.isActive != hasMedia) session.isActive = hasMedia

        val state = when (st.state) {
            "play" -> PlaybackStateCompat.STATE_PLAYING
            "pause" -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(state, (st.elapsed * 1000).toLong(), 1f)
                .build()
        )

        val duration = song?.duration ?: 0.0
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song?.album ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (duration * 1000).toLong())
        meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
        session.setMetadata(meta.build())

        if (!running) return
        if (hasMedia) {
            showForeground(NOTIF_ID, buildNotification())
        } else {
            showForeground(NOTIF_IDLE_ID, buildIdleNotification())
        }
    }

    /** Starts foreground only when the id changes. Otherwise just updates the notification. */
    private fun showForeground(id: Int, notif: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        if (foregroundId != id) {
            startForeground(id, notif)
            if (foregroundId != 0) nm.cancel(foregroundId)
            foregroundId = id
        } else {
            nm.notify(id, notif)
        }
    }

    private fun buildNotification(): Notification {
        val actions = buttons.map { buildAction(it) }
        val compact = (0 until minOf(3, actions.size)).toList().toIntArray()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mpd_art_placeholder)
            .setContentTitle(nowTitle)
            .setContentText(nowArtist)
            .setLargeIcon(nowArt)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        actions.forEach { builder.addAction(it) }

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(*compact)
        )
        return builder.build()
    }

    /** Stays in foreground while MPD is stopped. Almost invisible on purpose. */
    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(this, IDLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mpd_art_placeholder)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.idle_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()

    private fun buildAction(name: String): NotificationCompat.Action = when (name) {
        "prev" -> NotificationCompat.Action(
            R.drawable.ic_media_previous, getString(R.string.action_previous),
            mediaButton(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        )
        "next" -> NotificationCompat.Action(
            R.drawable.ic_media_next, getString(R.string.action_next),
            mediaButton(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        )
        "stop" -> NotificationCompat.Action(
            R.drawable.ic_menu_close, getString(R.string.action_stop),
            mediaButton(PlaybackStateCompat.ACTION_STOP)
        )
        "play" -> if (playing) {
            NotificationCompat.Action(
                R.drawable.ic_media_pause, getString(R.string.action_pause),
                mediaButton(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_media_play, getString(R.string.action_play),
                mediaButton(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
        }
        "repeat" -> NotificationCompat.Action(
            repeatIcon(),
            getString(R.string.action_repeat, repeatLabel()),
            servicePending(ACTION_REPEAT, 1)
        )
        "random" -> NotificationCompat.Action(
            R.drawable.ic_media_random,
            getString(if (mpdRandom) R.string.action_shuffle_on else R.string.action_shuffle_off),
            servicePending(ACTION_RANDOM, 2)
        )
        else -> throw IllegalArgumentException("unknown button: $name")
    }

    private fun repeatLabel(): String = getString(
        when {
            !mpdRepeat -> R.string.repeat_off
            !mpdSingle -> R.string.repeat_all
            else -> R.string.repeat_one
        }
    )

    private fun repeatIcon(): Int = when {
        !mpdRepeat -> R.drawable.ic_media_once
        !mpdSingle -> R.drawable.ic_media_repeat
        else -> R.drawable.ic_media_repeat_single
    }

    private fun mediaButton(action: Long) =
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    /** For actions without a media keycode (repeat, shuffle). Handled in onStartCommand. */
    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, MprisService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** off -> all -> one -> off */
    private fun cycleRepeat() {
        command {
            val st = status()
            when {
                !st.repeat -> {
                    setOption("repeat", true)
                    setOption("single", false)
                }
                !st.single -> setOption("single", true)
                else -> {
                    setOption("single", false)
                    setOption("repeat", false)
                }
            }
        }
    }

    private fun toggleRandom() {
        command { setOption("random", !status().random) }
    }

    /** All commands to MPD go through one thread, so the client is never used concurrently. */
    private fun command(block: MpdClient.() -> Unit) {
        if (!running) return
        commands.execute {
            // MPD closes idle connections, so reconnect when needed
            try {
                if (!cmdClient.isConnected) cmdClient.connect()
                cmdClient.block()
            } catch (e: MpdException) {
                // The socket may have died mid-command. Try once more on a new connection.
                try {
                    cmdClient.connect()
                    cmdClient.block()
                } catch (e2: MpdException) {
                    Log.w(TAG, "command failed: ${e2.message}")
                }
            }
        }
    }

    private fun onDeviceEvent(event: String) {
        val action = config.events[event] ?: return
        command {
            when (action) {
                "pause" -> pause(true)
                "play" -> play()
                "stop" -> stop()
                else -> {}
            }
        }
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = command { play() }
        override fun onPause() = command { pause(true) }
        override fun onSkipToNext() = command { next() }
        override fun onSkipToPrevious() = command { previous() }
        override fun onStop() = command { stop() }
        override fun onSeekTo(pos: Long) = command { seekCur(pos / 1000.0) }
    }

    /** Volume keys and volume slider go to MPD. Only set up when volume_keys = "mpd". */
    private fun setupVolumeKeys(initialVolume: Int) {
        if (config.volumeKeys != "mpd") return
        if (initialVolume < 0) {
            toast(getString(R.string.toast_no_mixer))
            return
        }
        shadowVolume = initialVolume

        val vp = object : VolumeProviderCompat(
            VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100, initialVolume
        ) {
            // Slider: Android sends the target value directly
            override fun onSetVolumeTo(volume: Int) {
                applyVolume(volume.coerceIn(0, 100), this)
            }

            // Volume keys: Android sends a direction, computed from the shadow value
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0 || shadowVolume < 0) return
                applyVolume((shadowVolume + direction * config.volumeStep).coerceIn(0, 100), this)
            }
        }
        volumeProvider = vp
        session.setPlaybackToRemote(vp)
    }

    private fun applyVolume(target: Int, vp: VolumeProviderCompat) {
        shadowVolume = target
        vp.setCurrentVolume(target) // OSD updates now, without waiting for MPD
        pendingVol.incrementAndGet()
        command {
            try {
                setVolume(target)
            } finally {
                pendingVol.decrementAndGet()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (d in removedDevices) eventFor(d.type, "disconnect")?.let { onDeviceEvent(it) }
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (d in addedDevices) eventFor(d.type, "connect")?.let { onDeviceEvent(it) }
        }
    }

    private fun eventFor(type: Int, dir: String): String? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "wired_$dir"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_$dir"
        else -> null
    }

    // ---------- Lifecycle and helpers ----------

    override fun onDestroy() {
        running = false
        AppState.running = false
        refreshTile()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (::idleClient.isInitialized) idleClient.close()
        if (::cmdClient.isInitialized) cmdClient.close()
        commands.shutdownNow()
        preloader?.stop()
        session.isActive = false
        session.release()
        artStore?.close()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(IDLE_CHANNEL_ID, getString(R.string.channel_idle), NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun refreshTile() {
        TileService.requestListeningState(
            this, ComponentName(this, ToggleTileService::class.java)
        )
    }

    private fun stopNow(reason: String) {
        Log.w(TAG, "stopping service: $reason")
        stopSelf()
    }
}