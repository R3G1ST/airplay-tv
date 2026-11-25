package com.airplaytv.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.airplaytv.core.airplay.AirPlayServer
import com.airplaytv.core.mdns.MdnsResponder
import com.airplaytv.core.util.Log as CoreLog

class AirPlayService : Service(), AirPlayServer.Listener {

    private lateinit var server: AirPlayServer
    private var mdns: MdnsResponder? = null
    private var nsdFallback: NsdFallback? = null
    private lateinit var videoDecoder: VideoDecoder
    private val audioPlayer = AudioPlayer()
    private var videoPlayer: RemoteVideoPlayer? = null
    private var dacp: DacpClient? = null
    @Volatile private var videoActive = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pendingSurface: Surface? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        CoreLog.minLevel = CoreLog.Level.INFO
        CoreLog.sink = { level, tag, msg ->
            Log.println(level.toAndroidPriority(), "AirPlay/$tag", msg)
            AppState.appendLog("$tag: $msg")
        }

        val deviceId = DeviceIdentity.deviceId(this)
        val deviceName = DeviceIdentity.deviceName(this)

        videoDecoder = VideoDecoder { width, height ->
            AppState.update { it.copy(mirrorWidth = width, mirrorHeight = height) }
        }
        pendingSurface?.let { videoDecoder.setSurface(it) }

        startForegroundNotification(deviceName)
        dacp = DacpClient(this).also { client ->
            // Real audible sound, not just packets: the sender keeps streaming
            // silence while paused, so packet timing would always say "playing".
            client.isPlayingProvider = {
                val last = audioPlayer.lastAudibleAt
                last > 0L && android.os.SystemClock.elapsedRealtime() - last < 900
            }
        }
        videoPlayer = RemoteVideoPlayer(this) {
            AppState.update {
                it.copy(phase = AppState.Phase.IDLE, subtitle = "Ожидание подключения")
            }
        }

        server = AirPlayServer(
            AirPlayServer.Config(
                name = deviceName,
                deviceId = deviceId,
                port = 7000,
                displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1920),
                displayHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1080),
            ),
            this,
        )
        try {
            server.start()
        } catch (e: Throwable) {
            Log.e(TAG, "server start failed", e)
            AppState.update { it.copy(phase = AppState.Phase.ERROR, subtitle = "Порт 7000 занят") }
            return
        }

        acquireMulticastLock()
        startMdns(deviceId, deviceName)
        AppState.update { it.copy(deviceName = deviceName) }

        AppState.update {
            it.copy(
                phase = AppState.Phase.IDLE,
                deviceName = deviceName,
                subtitle = "Ожидание подключения",
                detail = "Откройте «Повтор экрана» на iPhone или Mac",
            )
        }
    }

    private fun startMdns(deviceId: String, name: String) {
        try {
            val responder = MdnsResponder(
                serviceName = name,
                deviceId = deviceId,
                port = 7000,
                airplayTxt = server.airplayTxt(),
                raopTxt = server.raopTxt(),
            )
            responder.start()
            if (!responder.isRunning) throw IllegalStateException("no multicast interface")
            mdns = responder
            CoreLog.i("Mdns", "advertising ${server.airplayTxt()}")
        } catch (e: Throwable) {
            Log.e(TAG, "mDNS advertising failed, falling back to NsdManager", e)
            AppState.appendLog("mDNS: ${e.message}")
            try {
                val fallback = NsdFallback(this)
                fallback.register(name, deviceId, 7000, server.airplayTxt(), server.raopTxt())
                nsdFallback = fallback
            } catch (inner: Throwable) {
                Log.e(TAG, "NsdManager fallback failed too", inner)
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("airplay-tv").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "multicast lock unavailable: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        mdns?.stop()
        mdns = null
        nsdFallback?.unregister()
        nsdFallback = null
        try {
            server.stop()
        } catch (_: Throwable) {
        }
        videoDecoder.release()
        audioPlayer.release()
        videoPlayer?.stop()
        dacp?.stop()
        releaseWifiLock()
        multicastLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Throwable) {
            }
        }
        multicastLock = null
        super.onDestroy()
    }

    /** The receiver window went to the background: stop decoding to a dead surface. */
    fun onUiHidden() {
        if (::videoDecoder.isInitialized) videoDecoder.pause()
    }

    fun attachSurface(surface: Surface?, forceReconfigure: Boolean = false) {
        pendingSurface = surface
        if (::videoDecoder.isInitialized) videoDecoder.setSurface(surface, forceReconfigure)
        videoPlayer?.setSurface(surface)
    }

    fun restartReceiver() {
        try {
            mdns?.stop()
            server.stop()
        } catch (_: Throwable) {
        }
        mdns = null
        val deviceId = DeviceIdentity.deviceId(this)
        val deviceName = DeviceIdentity.deviceName(this)
        server = AirPlayServer(
            AirPlayServer.Config(name = deviceName, deviceId = deviceId, port = 7000),
            this,
        )
        server.start()
        startMdns(deviceId, deviceName)
        AppState.update { it.copy(phase = AppState.Phase.IDLE, subtitle = "Ожидание подключения") }
    }

    // ------------------------------------------------------- protocol events

    private var lastUiLaunch = 0L
    private var launchRetryScheduled = false

    /**
     * How long ago the user last touched the remote. Uses the hidden
     * PowerManager API where available; MAX_VALUE means "unknown".
     */
    private fun msSinceUserActivity(): Long {
        // 1) Usage stats: the moment the user last switched an app on the TV.
        //    (Needs the "Usage access" permission, granted once via adb/settings.)
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val nowWall = System.currentTimeMillis()
            val events = usm.queryEvents(nowWall - 20_000, nowWall)
            val event = android.app.usage.UsageEvents.Event()
            var last = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val resumed = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (resumed && event.packageName != packageName) {
                    last = maxOf(last, event.timeStamp)
                }
            }
            if (last > 0L) return nowWall - last
        } catch (_: Throwable) {
        }
        // 2) Hidden platform API, when the device allows it.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val method = android.os.PowerManager::class.java.getMethod("getLastUserActivityTime")
            val last = method.invoke(pm) as Long
            return android.os.SystemClock.uptimeMillis() - last
        } catch (_: Throwable) {
        }
        return Long.MAX_VALUE
    }

    /**
     * Brings the receiver UI to the front for a new cast. It deliberately waits
     * until the remote has been idle for a while, so a cast never yanks the
     * screen away while somebody is navigating the TV.
     */
    private fun launchUi(force: Boolean = false) {
        // Already on screen: never re-launch, or the activity re-attaches its
        // surface and the decoder is recreated every few seconds (black picture).
        if (MainActivity.isVisible) return
        val now = System.currentTimeMillis()
        if (now - lastUiLaunch < 4000) return

        val idle = msSinceUserActivity()
        val requiredIdle = 12_000L
        // A brand-new cast is an explicit action on the phone: show it at once.
        if (!force && idle < requiredIdle) {
            if (!launchRetryScheduled) {
                launchRetryScheduled = true
                val delay = (requiredIdle - idle) + 500
                Log.i(TAG, "remote active ${idle}ms ago, deferring UI by ${delay}ms")
                uiHandler.postDelayed({
                    launchRetryScheduled = false
                    launchUi()
                }, delay)
            }
            return
        }

        lastUiLaunch = now
        try {
            val intent = Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(intent)
            Log.i(TAG, "UI raised, remote idle ${idle}ms")
        } catch (e: Throwable) {
            Log.w(TAG, "cannot bring UI to front: ${e.message}")
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay-tv:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "high-performance Wi-Fi lock acquired")
        } catch (e: Throwable) {
            Log.w(TAG, "Wi-Fi lock unavailable: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Throwable) {
            }
        }
        wifiLock = null
    }

    /** Keeps checking whether the receiver should come back after 12 s of remote idleness. */
    private val uiTicker = object : Runnable {
        override fun run() {
            if (AppState.snapshot.phase == AppState.Phase.IDLE) return
            launchUi()
            uiHandler.postDelayed(this, 4_000)
        }
    }

    private fun startUiTicker() {
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.postDelayed(uiTicker, 4_000)
    }

    override fun onSessionStarted(deviceName: String?) {
        Log.i(TAG, "session started, remote idle ${msSinceUserActivity()}ms")
        acquireWifiLock()
        launchUi(force = true)
        // The phase is decided by the first SETUP: video config -> mirroring,
        // audio stream -> music. RECORD alone is sent by both. A paused track
        // sends no packets at all, so fall back to the music screen shortly
        // after RECORD unless video shows up.
        uiHandler.postDelayed({
            if (!videoActive && AppState.snapshot.phase != AppState.Phase.PLAYING) {
                restoreLastTrackIfNeeded()
                AppState.update {
                    it.copy(phase = AppState.Phase.PLAYING, subtitle = "AirPlay • музыка", detail = "")
                }
            }
        }, 900)
    }

    override fun onSessionEnded() {
        Log.i(TAG, "session ended")
        uiHandler.removeCallbacks(uiTicker)
        releaseWifiLock()
        uiHandler.removeCallbacks(audioWatchdog)
        videoActive = false
        stopProgressTicker()
        uiHandler.removeCallbacks(audioWatchdog)
        dacp?.stop()
        AppState.update {
            it.copy(
                trackTitle = "", trackArtist = "", trackAlbum = "", artwork = null, audioFormat = "",
                mirrorWidth = 0, mirrorHeight = 0,
                progressPosition = 0.0, progressDuration = 0.0, progressKnown = false,
            )
        }
        videoDecoder.release()
        audioPlayer.release()
        pendingSurface?.let { videoDecoder.setSurface(it) }
        AppState.update {
            it.copy(phase = AppState.Phase.IDLE, subtitle = "Ожидание подключения", mirrorWidth = 0, mirrorHeight = 0)
        }
    }

    override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        videoActive = true
        launchUi()
        videoDecoder.configure(sps, pps, width, height)
        AppState.update {
            it.copy(
                phase = AppState.Phase.MIRRORING,
                subtitle = "Идёт трансляция",
                mirrorWidth = width,
                mirrorHeight = height,
            )
        }
    }

    private val boosted = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private fun boostCurrentThread(priority: Int) {
        if (boosted.get()) return
        boosted.set(true)
        try {
            android.os.Process.setThreadPriority(priority)
        } catch (_: Throwable) {
        }
    }

    override fun onVideoFrame(data: ByteArray, ptsMicros: Long) {
        boostCurrentThread(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        videoDecoder.submit(data, ptsMicros)
    }

    private val audioWatchdog = object : Runnable {
        override fun run() {
            if (AppState.snapshot.phase != AppState.Phase.PLAYING) {
                return // nothing to watch
            }
            val idle = server.audioIdleMillis()
            if (audioPlayer.isActive && idle > 1_500) {
                // The sender paused or stopped: drop the buffered tail so the TV
                // goes quiet exactly when the phone does.
                audioPlayer.flushOutput()
            }
            if (idle > 60_000) {
                // Really gone (not a pause): free the audio path and hand the
                // screen back to the system.
                audioPlayer.release()
                videoActive = false
                stopProgressTicker()
                AppState.update {
                    it.copy(
                        phase = AppState.Phase.IDLE,
                        trackTitle = "", trackArtist = "", trackAlbum = "",
                        artwork = null, progressKnown = false,
                        progressPosition = 0.0, progressDuration = 0.0,
                    )
                }
                return
            }
            uiHandler.postDelayed(this, 250)
        }
    }

    override fun onAudioFrame(frame: ByteArray, ptsMicros: Long) {
        boostCurrentThread(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        launchUi()
        if (!videoActive && AppState.snapshot.phase != AppState.Phase.PLAYING) {
            AppState.update {
                it.copy(phase = AppState.Phase.PLAYING, subtitle = "AirPlay • музыка", detail = "Звук с iPhone")
            }
        }
        if (audioPlayer.ensureStarted()) audioPlayer.submit(frame, ptsMicros)
    }

    private fun lastTrackFile() = java.io.File(filesDir, "last_track.txt")
    private fun lastArtworkFile() = java.io.File(filesDir, "last_artwork.img")

    private fun persistTrack(title: String?, artist: String?, album: String?) {
        try {
            lastTrackFile().writeText(listOf(title ?: "", artist ?: "", album ?: "").joinToString("\u0001"))
        } catch (_: Throwable) {
        }
    }

    /** Shows the previously played track until the sender provides fresh metadata. */
    private fun restoreLastTrackIfNeeded() {
        if (AppState.snapshot.trackTitle.isNotBlank()) return
        try {
            val f = lastTrackFile()
            if (!f.exists()) return
            val parts = f.readText().split("\u0001")
            if (parts.size < 3) return
            val title = parts[0]
            if (title.isBlank()) return
            val art = lastArtworkFile().takeIf { it.exists() }?.readBytes()
            AppState.update {
                it.copy(
                    trackTitle = title,
                    trackArtist = parts[1],
                    trackAlbum = parts[2],
                    artwork = art ?: it.artwork,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cannot restore last track: ${e.message}")
        }
    }

    override fun onTrackMetadata(title: String?, artist: String?, album: String?) {
        Log.i(TAG, "metadata: title=$title artist=$artist album=$album")
        persistTrack(title, artist, album)
        AppState.update {
            it.copy(
                trackTitle = title ?: it.trackTitle,
                trackArtist = artist ?: it.trackArtist,
                trackAlbum = album ?: it.trackAlbum,
            )
        }
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressBasePosition = 0.0
    private var progressAccum = 0.0
    private var lastProgressTick = 0L
    private var progressDuration = 0.0
    private var progressTicker: Runnable? = null

    override fun onProgress(positionSeconds: Double, durationSeconds: Double) {
        progressBasePosition = positionSeconds
        progressAccum = 0.0
        lastProgressTick = android.os.SystemClock.elapsedRealtime()
        if (durationSeconds > 0.0) progressDuration = durationSeconds
        AppState.update { it.copy(progressKnown = true) }
        publishProgress()
        startProgressTicker()
    }

    override fun onTrackDuration(seconds: Double) {
        if (seconds > 0.0) {
            progressDuration = seconds
            publishProgress()
        }
    }

    private fun publishProgress() {
        val duration = progressDuration
        val now = android.os.SystemClock.elapsedRealtime()
        val since = if (lastProgressTick == 0L) 0L else now - lastProgressTick
        lastProgressTick = now
        // While the sender is paused it keeps streaming silence; only real
        // audio (or no audio at all for a paused start) moves the timeline.
        val audible = audioPlayer.lastAudibleAt > 0L && now - audioPlayer.lastAudibleAt < 1200
        if (audible && since in 1..1000) {
            progressAccum += since / 1000.0
        }
        val position = (progressBasePosition + progressAccum)
            .coerceIn(0.0, if (duration > 0) duration else Double.MAX_VALUE)
        AppState.update { it.copy(progressPosition = position, progressDuration = duration) }
    }

    private fun startProgressTicker() {
        if (progressTicker != null) return
        val ticker = object : Runnable {
            override fun run() {
                val snapshot = AppState.snapshot
                if (snapshot.phase == AppState.Phase.PLAYING && snapshot.mirrorWidth == 0) {
                    publishProgress()
                    uiHandler.postDelayed(this, 100)
                } else {
                    progressTicker = null
                }
            }
        }
        progressTicker = ticker
        uiHandler.postDelayed(ticker, 100)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { uiHandler.removeCallbacks(it) }
        progressTicker = null
        progressBasePosition = 0.0
        progressAccum = 0.0
        lastProgressTick = 0L
        progressDuration = 0.0
    }

    override fun onArtwork(bytes: ByteArray) {
        Log.i(TAG, "artwork: ${bytes.size} bytes")
        try {
            lastArtworkFile().writeBytes(bytes)
        } catch (_: Throwable) {
        }
        AppState.update { it.copy(artwork = bytes) }
    }

    override fun onAudioCodec(codecType: Int, samplesPerFrame: Int) {
        audioPlayer.setCodec(codecType, samplesPerFrame)
        // Show the music screen as soon as the audio session is established:
        // a track that is paused on the phone sends no packets, and previously
        // the TV stayed on the idle screen until playback started.
        if (!videoActive) {
            restoreLastTrackIfNeeded()
            AppState.update {
                it.copy(
                    phase = AppState.Phase.PLAYING,
                    subtitle = "AirPlay • музыка",
                    detail = "",
                    mirrorWidth = 0,
                    mirrorHeight = 0,
                )
            }
        }
        val format = when (codecType) {
            2 -> "ALAC · 44,1 кГц · 16 бит · lossless"
            4 -> "AAC · 44,1 кГц"
            else -> "AAC-ELD · 44,1 кГц"
        }
        AppState.update { it.copy(audioFormat = format) }
        uiHandler.removeCallbacks(audioWatchdog)
        uiHandler.postDelayed(audioWatchdog, 1000)
    }

    override fun onDacpInfo(dacpId: String?, activeRemote: String?) {
        dacp?.configure(dacpId, activeRemote)
    }

    fun togglePlayPause() {
        dacp?.send("playpause")
    }

    fun nextTrack() {
        dacp?.send("nextitem")
    }

    fun previousTrack() {
        dacp?.send("previtem")
    }

    override fun onVolumeChanged(volumeDb: Float) {
        val linear = Math.pow(10.0, volumeDb / 20.0).toFloat()
        audioPlayer.setVolume(linear)
    }

    override fun onPlayVideo(url: String, startPositionSeconds: Double, headers: Map<String, String>) {
        videoDecoder.release()
        videoPlayer?.play(url, startPositionSeconds, headers)
        AppState.update {
            it.copy(
                phase = AppState.Phase.PLAYING,
                subtitle = "Воспроизведение",
                detail = "Видео с iPhone",
            )
        }
    }

    override fun onStopVideo() {
        videoPlayer?.stop()
        AppState.update {
            it.copy(phase = AppState.Phase.IDLE, subtitle = "Ожидание подключения")
        }
    }

    override fun onSetRate(rate: Double) {
        if (rate > 0.0) videoPlayer?.resume() else videoPlayer?.pause()
    }

    override fun onSeek(positionSeconds: Double) {
        videoPlayer?.seek(positionSeconds)
    }

    override fun videoPosition(): Pair<Double, Double> = videoPlayer?.snapshot() ?: (0.0 to 0.0)

    // ---------------------------------------------------------- notification

    private fun startForegroundNotification(deviceName: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirPlay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Приём AirPlay" }
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val notification = builder
            .setContentIntent(openIntent)
            .setContentTitle("AirPlay TV")
            .setContentText("$deviceName принимает подключения")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_receiver"
        private const val NOTIFICATION_ID = 7000

        @Volatile
        var instance: AirPlayService? = null
            private set

        fun attachSurfaceFromUi(surface: Surface?, forceReconfigure: Boolean = false) {
            instance?.attachSurface(surface, forceReconfigure)
        }

        private fun CoreLog.Level.toAndroidPriority(): Int = when (this) {
            CoreLog.Level.DEBUG -> Log.DEBUG
            CoreLog.Level.INFO -> Log.INFO
            CoreLog.Level.WARN -> Log.WARN
            CoreLog.Level.ERROR -> Log.ERROR
        }
    }
}
