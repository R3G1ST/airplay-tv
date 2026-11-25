package com.airplaytv.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The receiver screen. It is deliberately a single, static screen: a TV that is
 * waiting for an AirPlay connection should look like an appliance, not like an
 * app with a menu.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var deviceText: TextView
    private lateinit var detailText: TextView
    private lateinit var badge: TextView

    private enum class Screen { IDLE, MUSIC, VIDEO }

    private val stateListener: (AppState.Snapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }
    private var videoWidth = 0
    private var videoHeight = 0
    private var wasMirroring = false
    private var hadSession = false
    private var currentScreen: Screen? = null
    private var coverAnimatedFor: ByteArray? = null
    private var idleEviction: Runnable? = null
    private var aspectMode = 0
    private lateinit var aspectButton: TextView
    private lateinit var nowPlaying: android.widget.FrameLayout
    private lateinit var artworkView: ImageView
    private lateinit var backdropView: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackAlbum: TextView
    private lateinit var progressBar: TimelineView
    private lateinit var remoteHintView: TextView
    private var lastHintSeq = 0
    private lateinit var timeElapsed: TextView
    private lateinit var timeRemaining: TextView
    private var shownArtwork: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        aspectMode = DeviceIdentity.aspectMode(this)
        buildUi()
        startReceiverService()
        ensureOverlayPermission()
        ensureUsageAccess()
        if (intent?.getBooleanExtra("preview_music", false) == true) {
            root.postDelayed({
            AppState.update {
                it.copy(
                    phase = AppState.Phase.PLAYING,
                    subtitle = "AirPlay • музыка",
                    trackTitle = "Bohemian Rhapsody",
                    trackArtist = "Queen",
                    trackAlbum = "A Night at the Opera",
                    artwork = previewArtwork(),
                    progressPosition = 134.0,
                    progressDuration = 355.0,
                    progressKnown = true,
                    mirrorWidth = 0,
                )
            }
            }, 2500)
        }
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundResource(R.drawable.bg_gradient)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.visibility = View.GONE
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(40), dp(48), dp(48))
        }

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_launcher)
        icon.alpha = 0.95f
        overlay.addView(icon, LinearLayout.LayoutParams(dp(120), dp(120)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28)
        })

        val title = TextView(this).apply {
            text = "AirPlay TV"
            setTextColor(Color.WHITE)
            textSize = 34f
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
        }
        overlay.addView(title, wrapParams())

        deviceText = TextView(this).apply {
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(36))
        }
        overlay.addView(deviceText, wrapParams())

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(18), dp(9))
            background = capsule(0x1FFFFFFF)
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF34C759.toInt())
            }
        }
        statusPill.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(10)
        })
        statusText = TextView(this).apply {
            text = "Готов к подключению"
            setTextColor(Color.parseColor("#E8E8ED"))
            textSize = 17f
        }
        statusPill.addView(statusText)
        overlay.addView(statusPill, wrapParams())

        detailText = TextView(this).apply {
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(40))
            maxLines = 2
        }
        overlay.addView(detailText, wrapParams())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(button("Перезапустить") { restart() })
        buttons.addView(button("Имя устройства") { renameDevice() })
        overlay.addView(buttons, wrapParams().apply { gravity = Gravity.CENTER_HORIZONTAL })

        badge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            visibility = View.GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(24), dp(24), 0, 0) })

        buildNowPlaying()

        aspectButton = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#66000000"))
            }
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener { cycleAspect() }
        }
        root.addView(aspectButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(24), dp(24)) })

        remoteHintView = TextView(this).apply {
            setTextColor(Color.parseColor("#E8E8ED"))
            textSize = 13f
            letterSpacing = 0.02f
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = capsule(0x2E000000)
            visibility = View.GONE
        }
        root.addView(remoteHintView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(36) })

        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
        setContentView(root)
    }

    private fun wrapParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun buildNowPlaying() {
        // Apple TV style: full-bleed blurred artwork, large cover on the left,
        // typography and timeline on the right.
        nowPlaying = android.widget.FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        backdropView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.55f
        }
        nowPlaying.addView(backdropView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(235, 0, 0, 0)),
            )
        }
        nowPlaying.addView(scrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nowPlaying.addView(row, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
            }
            clipToOutline = true
        }
        row.addView(artworkView, LinearLayout.LayoutParams(dp(380), dp(380)).apply {
            marginEnd = dp(44)
        })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        row.addView(column, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT))

        val pill = TextView(this).apply {
            text = "  AirPlay  "
            setTextColor(Color.parseColor("#D0D0D5"))
            textSize = 13f
            letterSpacing = 0.12f
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        column.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(22) })

        trackTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            letterSpacing = -0.01f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(trackTitle, wrapParams())

        trackArtist = TextView(this).apply {
            setTextColor(Color.parseColor("#B8B8BE"))
            textSize = 23f
            setPadding(0, dp(12), 0, 0)
            maxLines = 1
        }
        column.addView(trackArtist, wrapParams())

        trackAlbum = TextView(this).apply {
            setTextColor(Color.parseColor("#7E7E85"))
            textSize = 17f
            setPadding(0, dp(6), 0, 0)
        }
        column.addView(trackAlbum, wrapParams())

        progressBar = TimelineView(this)
        column.addView(progressBar, LinearLayout.LayoutParams(dp(484), dp(6)).apply {
            topMargin = dp(34)
        })

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeElapsed = TextView(this).apply {
            setTextColor(Color.parseColor("#B0B0B6"))
            textSize = 14f
        }
        timeRemaining = TextView(this).apply {
            setTextColor(Color.parseColor("#B0B0B6"))
            textSize = 14f
            gravity = Gravity.END
        }
        timeRow.addView(timeElapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(timeRemaining, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(timeRow, LinearLayout.LayoutParams(dp(484), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        root.addView(nowPlaying, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(14), dp(26), dp(14))
            background = capsule(0x1FFFFFFF)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, focused ->
                v.background = capsule(if (focused) 0x3DFFFFFF else 0x1FFFFFFF)
                v.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f)
                    .setDuration(120).start()
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(14)
            layoutParams = lp
        }

    private fun capsule(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(color)
        }

    private fun ensureOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        if (android.provider.Settings.canDrawOverlays(this)) return
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("Сделать приёмник частью телевизора")
                .setMessage(
                    "Разрешите приложению показывать экран поверх других приложений — " +
                        "тогда при подключении AirPlay он сам выйдет на передний план, " +
                        "а после окончания вернёт вас в систему."
                )
                .setPositiveButton("Разрешить") { _, _ ->
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        )
                    )
                }
                .setNegativeButton("Позже", null)
                .show()
        } catch (_: Throwable) {
        }
    }

    /** Usage access lets the receiver wait until the remote is idle before taking over the screen. */
    private fun ensureUsageAccess() {
        try {
            val appOps = getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName,
            )
            if (mode == android.app.AppOpsManager.MODE_ALLOWED) return
        } catch (_: Throwable) {
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Не мешать вам на телевизоре")
            .setMessage(
                "Разрешите доступ к статистике использования — тогда приёмник не будет " +
                    "выходить на экран, пока вы пользуетесь пультом, и появится после " +
                    "10–15 секунд покоя."
            )
            .setPositiveButton("Разрешить") { _, _ ->
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun startReceiverService() {
        val intent = Intent(this, AirPlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun restart() {
        AirPlayService.instance?.restartReceiver()
        Toast.makeText(this, "Приёмник перезапущен", Toast.LENGTH_SHORT).show()
    }

    private fun renameDevice() {
        val input = EditText(this).apply {
            setText(DeviceIdentity.deviceName(this@MainActivity))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Имя устройства в AirPlay")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    DeviceIdentity.setDeviceName(this, name)
                    restart()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun render(state: AppState.Snapshot) {
        deviceText.text = if (state.deviceName.isNotBlank()) {
            "${state.deviceName} • AirPlay"
        } else {
            "AirPlay"
        }
        statusText.text = when (state.phase) {
            AppState.Phase.IDLE -> "Готов к подключению"
            AppState.Phase.PLAYING -> "Играет музыка"
            AppState.Phase.MIRRORING -> "Идёт трансляция"
            AppState.Phase.ERROR -> "Ошибка"
            AppState.Phase.CONNECTING -> "Подключение…"
        }
        val dotColor = when (state.phase) {
            AppState.Phase.IDLE -> 0xFF34C759.toInt()
            AppState.Phase.PLAYING, AppState.Phase.MIRRORING, AppState.Phase.CONNECTING -> 0xFF0A84FF.toInt()
            AppState.Phase.ERROR -> 0xFFFF453A.toInt()
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
        detailText.text = when (state.phase) {
            AppState.Phase.MIRRORING -> if (state.mirrorWidth > 0) {
                "${state.mirrorWidth}×${state.mirrorHeight} • ${state.detail}"
            } else {
                state.detail
            }
            AppState.Phase.ERROR -> state.detail
            AppState.Phase.PLAYING -> state.detail
            else -> "Откройте «Повтор экрана» на iPhone или Mac"
        }

        val videoActive = state.phase == AppState.Phase.MIRRORING ||
            (state.phase == AppState.Phase.PLAYING && state.mirrorWidth > 0)
        val musicActive = state.phase == AppState.Phase.PLAYING && !videoActive
        switchScreen(
            when {
                videoActive -> Screen.VIDEO
                musicActive -> Screen.MUSIC
                else -> Screen.IDLE
            }
        )
        if (musicActive) renderNowPlaying(state)
        if (state.remoteHintSeq != lastHintSeq) {
            lastHintSeq = state.remoteHintSeq
            showRemoteHint(state.remoteHint)
        }
        badge.text = "AirPlay • ${state.deviceName}"
        if (state.mirrorWidth > 0 && state.mirrorHeight > 0) {
            videoWidth = state.mirrorWidth
            videoHeight = state.mirrorHeight
        }
        if (videoActive) applyAspect()

        wasMirroring = videoActive

        if (state.phase != AppState.Phase.IDLE) {
            hadSession = true
            // A new cast started while we were about to yield the screen.
            idleEviction?.let { root.removeCallbacks(it) }
            idleEviction = null
        } else if (hadSession && idleEviction == null) {
            // The cast ended. Give the sender a generous grace period (Apple
            // Music tears the stream down on pause and resumes seconds later),
            // then quietly return to the TV without killing the activity, so
            // the next connection is instant.
            val evict = Runnable {
                idleEviction = null
                hadSession = false
                if (!isFinishing) moveTaskToBack(true)
            }
            idleEviction = evict
            root.postDelayed(evict, 45_000)
        }
    }

    /** Debug helper: a synthetic artwork so the now-playing layout can be checked without a phone. */
    private fun previewArtwork(): ByteArray {
        val size = 600
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF3B1E5A.toInt(), 0xFFB4402F.toInt(), 0xFFE8A33D.toInt()),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    /**
     * Fake blur: downscale hard and let the ImageView upscale it smoothly.
     * A real RenderEffect blur breaks composition on some TV GPU drivers
     * (the whole window turns black), so this stays on the safe path.
     */
    private fun softBackdrop(source: android.graphics.Bitmap): android.graphics.Bitmap {
        return try {
            val tiny = android.graphics.Bitmap.createScaledBitmap(source, 48, 48, true)
            val smooth = android.graphics.Bitmap.createScaledBitmap(tiny, 96, 96, true)
            if (tiny !== smooth) tiny.recycle()
            smooth
        } catch (_: Throwable) {
            source
        }
    }

    private fun formatTime(seconds: Double): String {
        val total = kotlin.math.round(seconds).toLong().coerceAtLeast(0)
        val m = total / 60
        val sec = total % 60
        return "%d:%02d".format(m, sec)
    }

    private fun switchScreen(target: Screen) {
        if (currentScreen == target) return
        val previous = currentScreen
        currentScreen = target

        val fadeIn = android.view.animation.DecelerateInterpolator()
        val fadeOut = android.view.animation.AccelerateInterpolator()

        fun show(v: View, delay: Long = 0) {
            v.animate().cancel()
            v.visibility = View.VISIBLE
            if (v !== surfaceView) {
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(delay).setDuration(320).setInterpolator(fadeIn).start()
            }
        }
        fun hide(v: View, delay: Long = 0) {
            v.animate().cancel()
            if (v.visibility != View.VISIBLE) return
            if (v === surfaceView) { v.visibility = View.GONE; return }
            v.animate().alpha(0f).setStartDelay(delay).setDuration(200).setInterpolator(fadeOut)
                .withEndAction {
                    if (currentScreen != Screen.VIDEO || v !== surfaceView) {
                        if (!isTargetOf(v)) { v.visibility = View.GONE; v.alpha = 1f }
                    }
                }.start()
        }

        when (target) {
            Screen.IDLE -> {
                hide(nowPlaying); hide(surfaceView); hide(aspectButton); hide(badge)
                show(overlay)
                overlay.translationY = dp(12).toFloat()
                overlay.animate().translationY(0f).setDuration(380).setInterpolator(fadeIn).start()
            }
            Screen.MUSIC -> {
                hide(overlay); hide(surfaceView); hide(aspectButton); hide(badge)
                show(nowPlaying)
                nowPlaying.translationY = dp(16).toFloat()
                nowPlaying.animate().translationY(0f).setDuration(420).setInterpolator(fadeIn).start()
            }
            Screen.VIDEO -> {
                hide(overlay); hide(nowPlaying)
                hide(badge); hide(aspectButton)
                surfaceView.visibility = View.VISIBLE
            }
        }
        if (previous == Screen.VIDEO && target != Screen.VIDEO) {
            surfaceView.visibility = View.GONE
        }
    }

    private fun isTargetOf(v: View): Boolean = when (currentScreen) {
        Screen.IDLE -> v === overlay
        Screen.MUSIC -> v === nowPlaying
        Screen.VIDEO -> v === surfaceView || v === badge || v === aspectButton
        null -> false
    }

    private fun showRemoteHint(text: String) {
        if (text.isBlank()) return
        remoteHintView.text = text
        remoteHintView.animate().cancel()
        remoteHintView.visibility = View.VISIBLE
        remoteHintView.alpha = 0f
        remoteHintView.translationY = dp(6).toFloat()
        remoteHintView.animate().alpha(1f).translationY(0f)
            .setDuration(140)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        root.postDelayed({
            remoteHintView.animate().alpha(0f).setDuration(200).withEndAction {
                remoteHintView.visibility = View.GONE
            }.start()
        }, 1200)
    }

    private fun renderNowPlaying(state: AppState.Snapshot) {
        trackTitle.text = state.trackTitle.ifBlank { "AirPlay" }
        trackArtist.text = state.trackArtist
        trackAlbum.text = state.trackAlbum
        val duration = state.progressDuration
        val position = state.progressPosition.coerceIn(0.0, if (duration > 0) duration else Double.MAX_VALUE)
        // Without a real position from the sender (a track that is paused when
        // we connect) showing 0:00 would be a lie: keep the bar hidden instead.
        if (duration > 0.0 && state.progressKnown) {
            progressBar.visibility = View.VISIBLE
            timeElapsed.visibility = View.VISIBLE
            timeRemaining.visibility = View.VISIBLE
            progressBar.setFraction((position / duration).toFloat())
            timeElapsed.text = formatTime(position)
            timeRemaining.text = "-" + formatTime((duration - position).coerceAtLeast(0.0))
        } else {
            progressBar.visibility = View.GONE
            timeElapsed.visibility = View.GONE
            timeRemaining.visibility = View.GONE
        }
        val art = state.artwork
        if (art != null && art !== shownArtwork) {
            val firstArt = shownArtwork == null
            shownArtwork = art
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
            if (bitmap != null) {
                artworkView.setImageBitmap(bitmap)
                backdropView.setImageBitmap(softBackdrop(bitmap))
                if (firstArt) {
                    artworkView.alpha = 0f
                    artworkView.scaleX = 0.94f
                    artworkView.scaleY = 0.94f
                    artworkView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(450).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } else {
                    artworkView.alpha = 0.45f
                    artworkView.animate().alpha(1f).setDuration(260).start()
                }
            }
        }
        // When artwork is missing (start of a session or a track change) the
        // previous cover stays on screen: no placeholder, no flicker.
    }

    private fun applyAspect() {
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return
        val container = root.width.takeIf { it > 0 } ?: return
        val containerHeight = root.height.takeIf { it > 0 } ?: return
        val videoRatio = width.toFloat() / height.toFloat()
        val containerRatio = container.toFloat() / containerHeight.toFloat()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams

        // Letterboxed ("contain") size.
        val fitWidth: Int
        val fitHeight: Int
        if (videoRatio > containerRatio) {
            fitWidth = container
            fitHeight = (container / videoRatio).toInt()
        } else {
            fitHeight = containerHeight
            fitWidth = (containerHeight * videoRatio).toInt()
        }

        surfaceView.scaleX = 1f
        surfaceView.scaleY = 1f
        surfaceView.pivotX = 0f
        surfaceView.pivotY = 0f

        when (aspectMode) {
            1 -> { // stretch: fill the screen, geometry is not preserved
                lp.width = container
                lp.height = containerHeight
            }
            2 -> { // zoom: fill the screen and crop the overflow
                lp.width = fitWidth
                lp.height = fitHeight
                val scale = maxOf(
                    container.toFloat() / fitWidth.toFloat(),
                    containerHeight.toFloat() / fitHeight.toFloat(),
                )
                if (scale > 1f) {
                    surfaceView.pivotX = fitWidth / 2f
                    surfaceView.pivotY = fitHeight / 2f
                    surfaceView.scaleX = scale
                    surfaceView.scaleY = scale
                }
            }
            else -> { // fit: keep aspect, black bars where needed
                lp.width = fitWidth
                lp.height = fitHeight
            }
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
        updateAspectButton()
    }

    private fun cycleAspect() {
        aspectMode = (aspectMode + 1) % 3
        DeviceIdentity.setAspectMode(this, aspectMode)
        applyAspect()
        Toast.makeText(this, aspectLabel(), Toast.LENGTH_SHORT).show()
    }

    private fun aspectLabel(): String = when (aspectMode) {
        1 -> "Формат: заполнить"
        2 -> "Формат: заполнить с обрезкой"
        else -> "Формат: по размеру"
    }

    private fun updateAspectButton() {
        aspectButton.text = when (aspectMode) {
            1 -> "Заполнить"
            2 -> "Обрезать"
            else -> "По размеру"
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val snapshot = AppState.snapshot
        val musicMode = snapshot.phase == AppState.Phase.PLAYING && snapshot.mirrorWidth == 0
        if (musicMode) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_HEADSETHOOK,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    AirPlayService.instance?.togglePlayPause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    AirPlayService.instance?.nextTrack()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    AirPlayService.instance?.previousTrack()
                    return true
                }
            }
        }
        if ((keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                keyCode == android.view.KeyEvent.KEYCODE_PROG_BLUE ||
                keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y) &&
            AppState.snapshot.phase == AppState.Phase.MIRRORING
        ) {
            cycleAspect()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        AppState.addListener(stateListener)
        // Re-attach the surface: after the activity was in the background the
        // decoder may still point at a dead surface.
        surfaceView.holder.surface?.let {
            AirPlayService.attachSurfaceFromUi(it, forceReconfigure = true)
        }
        surfaceView.holder.surface?.let { AirPlayService.attachSurfaceFromUi(it) }
    }

    override fun onPause() {
        isVisible = false
        AppState.removeListener(stateListener)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        AirPlayService.attachSurfaceFromUi(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AirPlayService.attachSurfaceFromUi(holder.surface, forceReconfigure = true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AirPlayService.attachSurfaceFromUi(holder.surface)
        applyAspect()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlayService.attachSurfaceFromUi(null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var isVisible: Boolean = false
    }
}
