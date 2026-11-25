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
        runOnUiThread {
            try {
                render(snapshot)
            } catch (t: Throwable) {
                // A rendering bug must never take the receiver down: the last
                // good frame stays on screen and the service keeps serving.
                android.util.Log.e("AirPlayTV", "render failed", t)
            }
        }
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
    private var interRegular: android.graphics.Typeface? = null
    private var interSemiBold: android.graphics.Typeface? = null
    private lateinit var appTitle: TextView
    private var sourcePill: TextView? = null
    private var statusHalo: View? = null
    private var statusPulse: android.animation.ValueAnimator? = null
    private var statusColor = 0xFF34C759.toInt()

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
        loadFonts()
        buildUi()
        applyFonts(root)
        if (intent?.getBooleanExtra("self_test", false) == true) startSelfTest()
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

    /**
     * Smoke test used before every release: drives the real UI through the music
     * and mirroring screens and back to idle. Any crash shows up in logcat while
     * the TV is still on the bench.
     */
    private fun startSelfTest() {
        android.util.Log.i("AirPlayTV", "self-test: scheduling UI states")
        val steps = listOf<() -> Unit>(
            {
                AppState.update {
                    it.copy(
                        phase = AppState.Phase.PLAYING,
                        senderName = "Self-test iPhone",
                        trackTitle = "Self-test",
                        trackArtist = "Receiver",
                        trackAlbum = "UI check",
                        progressKnown = true,
                        progressPosition = 60.0,
                        progressDuration = 200.0,
                        mirrorWidth = 0,
                        mirrorHeight = 0,
                    )
                }
            },
            {
                AppState.update {
                    it.copy(
                        phase = AppState.Phase.MIRRORING,
                        mirrorWidth = 1920,
                        mirrorHeight = 1080,
                        detail = "self-test",
                    )
                }
            },
            {
                AppState.update {
                    it.copy(
                        phase = AppState.Phase.IDLE,
                        mirrorWidth = 0,
                        mirrorHeight = 0,
                        senderName = "",
                        trackTitle = "",
                        trackArtist = "",
                        trackAlbum = "",
                        progressKnown = false,
                    )
                }
            },
        )
        // Wait for the foreground service to finish its own start-up (it resets
        // the state to IDLE), otherwise the first step would be overwritten.
        steps.forEachIndexed { i, step -> root.postDelayed(step, 4_000L + 1_400L * i) }
    }

    /**
     * Inter (SIL OFL) is used as a legal, metrically close stand-in for Apple's
     * SF Pro, which may not be redistributed. Only Latin + Cyrillic + digits are
     * subset into the APK (~30 KB per weight).
     */
    private fun loadFonts() {
        interRegular = try {
            android.graphics.Typeface.createFromAsset(assets, "fonts/inter_regular.ttf")
        } catch (_: Throwable) {
            null
        }
        interSemiBold = try {
            android.graphics.Typeface.createFromAsset(assets, "fonts/inter_semibold.ttf")
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyFonts(view: android.view.View) {
        if (view is TextView) interRegular?.let { view.typeface = it }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) applyFonts(view.getChildAt(i))
        }
    }

    private fun applyDisplayFonts() {
        interSemiBold?.let {
            appTitle.typeface = it
            trackTitle.typeface = it
        }
        // Tabular digits: the timeline does not wobble while counting.
        timeElapsed.fontFeatureSettings = "tnum"
        timeRemaining.fontFeatureSettings = "tnum"
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
            setPadding(dp(48), dp(28), dp(48), dp(36))
        }

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_launcher)
        icon.alpha = 0.95f
        overlay.addView(icon, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        appTitle = TextView(this).apply {
            text = "AirPlay TV"
            setTextColor(Color.WHITE)
            textSize = 28f
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
        }
        overlay.addView(appTitle, wrapParams())

        deviceText = TextView(this).apply {
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        overlay.addView(deviceText, wrapParams())

        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            background = chipDrawable(0x14FFFFFF, 0x2EFFFFFF)
        }
        // Dot + expanding halo: a quiet "alive" signal, drawn with one animator
        // and zero assets.
        val dotBox = android.widget.FrameLayout(this)
        statusHalo = View(this).apply {
            background = dotDrawable(statusColor, 70)
        }
        dotBox.addView(statusHalo, android.widget.FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
        statusDot = View(this).apply {
            background = dotDrawable(statusColor, 255)
        }
        dotBox.addView(statusDot, android.widget.FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
        statusPill.addView(dotBox, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginEnd = dp(6)
        })
        statusText = TextView(this).apply {
            text = "Готов к подключению"
            setTextColor(Color.parseColor("#E8E8ED"))
            textSize = 13f
            letterSpacing = 0.01f
        }
        statusPill.addView(statusText)
        overlay.addView(statusPill, wrapParams())

        detailText = TextView(this).apply {
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(26))
            maxLines = 2
        }
        overlay.addView(detailText, wrapParams())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // The focused chip scales up by ~4%; without this the outer edges
            // were clipped flat (the "cut outline" on the left button).
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), 0)
        }
        buttons.addView(button("Перезапустить") { restart() })
        buttons.addView(button("Имя устройства") { renameDevice() })
        overlay.addView(buttons, wrapParams().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        })

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
        row.addView(artworkView, LinearLayout.LayoutParams(dp(340), dp(340)).apply {
            marginEnd = dp(40)
        })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // 340 + 40 + 484 = 864 dp: on a 1080p (960 dp) screen this leaves a
        // comfortable 48 dp margin instead of the cover almost touching the edge.
        row.addView(column, LinearLayout.LayoutParams(dp(484), ViewGroup.LayoutParams.WRAP_CONTENT))

        val pill = TextView(this).apply {
            text = "AirPlay"
            setTextColor(Color.parseColor("#D0D0D5"))
            textSize = 13f
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#26FFFFFF"))
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        sourcePill = pill
        column.addView(pill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(22)
        })

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
            gravity = Gravity.CENTER_HORIZONTAL
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
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })

        root.addView(nowPlaying, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // Regular everywhere, display weight for the app title and the track.
        applyFonts(root)
        applyDisplayFonts()
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#E8E8ED"))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = chipDrawable(0x14FFFFFF, 0x2EFFFFFF)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, focused ->
                v.background = chipDrawable(if (focused) 0x33FFFFFF else 0x14FFFFFF, 0x2EFFFFFF)
                v.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f)
                    .setDuration(120).start()
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
        }

    private fun dotDrawable(color: Int, alpha: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.argb(alpha, android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color)))
        }

    private fun startStatusPulse() {
        if (statusPulse != null) return
        val halo = statusHalo ?: return
        val dot = statusDot
        statusPulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                halo.scaleX = 1f + 1.6f * t
                halo.scaleY = halo.scaleX
                halo.alpha = 0.6f * (1f - t)
                dot.alpha = 1f - 0.3f * t
            }
            start()
        }
    }

    private fun stopStatusPulse() {
        statusPulse?.cancel()
        statusPulse = null
        statusHalo?.let {
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
        statusDot.alpha = 1f
    }

    /** Glass chip with an inset-looking hairline, shared by status and buttons. */
    private fun chipDrawable(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
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
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName,
                )
            }
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
        // Avoid the silly "AirPlay TV / AirPlay TV • AirPlay" when the device
        // keeps its default name.
        val name = state.deviceName.trim()
        deviceText.text = when {
            name.isBlank() || name.equals("AirPlay TV", ignoreCase = true) -> "Приёмник AirPlay"
            else -> "$name • AirPlay"
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
        if (dotColor != statusColor) {
            statusColor = dotColor
            (statusDot.background as? GradientDrawable)?.setColor(dotColor)
            statusHalo?.background = dotDrawable(dotColor, 70)
        }
        val detail = when (state.phase) {
            AppState.Phase.MIRRORING -> if (state.mirrorWidth > 0) {
                "${state.mirrorWidth}×${state.mirrorHeight} • ${state.detail}"
            } else {
                state.detail
            }
            AppState.Phase.ERROR, AppState.Phase.PLAYING, AppState.Phase.CONNECTING -> state.detail
            // Idle needs no instructions: the status pill is enough, and the
            // screen stays calm like a receiver should.
            else -> ""
        }
        detailText.text = detail
        detailText.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

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
        badge.text = state.senderName.trim().ifBlank { "AirPlay" }
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
    private val artworkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "artwork-decode").apply { isDaemon = true }
    }

    /** Decodes cover art at max 1024 px; some senders send multi-megabyte images. */
    private fun decodeArtwork(bytes: ByteArray): android.graphics.Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1024) sample *= 2
        val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
    } catch (_: Throwable) {
        null
    }

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
        val sender = state.senderName.trim()
        sourcePill?.text = if (sender.isBlank()) "AirPlay" else sender.take(30)
        trackTitle.text = state.trackTitle.ifBlank { "Играет музыка" }
        trackArtist.text = state.trackArtist
        trackAlbum.text = state.trackAlbum
        // No metadata from the sender (radio, system audio): keep the screen
        // honest instead of showing empty artist/album lines.
        val hasMeta = state.trackTitle.isNotBlank()
        trackArtist.visibility = if (hasMeta) View.VISIBLE else View.GONE
        trackAlbum.visibility = if (hasMeta) View.VISIBLE else View.GONE
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
            // A 160 KB JPEG takes tens of milliseconds to decode: never on the
            // main thread, or the music screen visibly stutters on track change.
            artworkExecutor.execute {
                val bitmap = decodeArtwork(art)
                if (bitmap == null) return@execute
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        bitmap.recycle()
                        return@runOnUiThread
                    }
                    if (shownArtwork !== art) return@runOnUiThread
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
        }
        // When artwork is missing (start of a session or a track change) the
        // previous cover stays on screen: no placeholder, no flicker.
    }

    private var applyingAspect = false

    private fun applyAspect() {
        if (applyingAspect) return
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return
        val container = root.width.takeIf { it > 0 } ?: return
        val containerHeight = root.height.takeIf { it > 0 } ?: return

        val videoRatio = width.toFloat() / height.toFloat()
        val containerRatio = container.toFloat() / containerHeight.toFloat()
        val fitWidth: Int
        val fitHeight: Int
        // A 1804x1016 stream in a 1920x1080 window differs from 16:9 by a
        // fraction of a percent. Fitting it exactly would scale the whole image
        // by a non-integer factor (and leave a 1-3 px gap) for no reason, so snap
        // to the full container in that case: one scaling step less, sharper text.
        val ratioDelta = kotlin.math.abs(videoRatio - containerRatio) / containerRatio
        if (ratioDelta < 0.006f) {
            fitWidth = container
            fitHeight = containerHeight
        } else if (videoRatio > containerRatio) {
            fitWidth = container
            fitHeight = kotlin.math.round(container / videoRatio).toInt()
        } else {
            fitHeight = containerHeight
            fitWidth = kotlin.math.round(containerHeight * videoRatio).toInt()
        }

        var targetWidth = fitWidth
        var targetHeight = fitHeight
        var targetScale = 1f
        when (aspectMode) {
            1 -> {
                targetWidth = container
                targetHeight = containerHeight
            }
            2 -> {
                targetScale = maxOf(
                    container.toFloat() / fitWidth.toFloat(),
                    containerHeight.toFloat() / fitHeight.toFloat(),
                ).coerceAtLeast(1f)
            }
        }

        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        val same = lp.width == targetWidth && lp.height == targetHeight &&
            surfaceView.scaleX == targetScale && lp.gravity == Gravity.CENTER
        if (same) return

        // Guard against the layout listener re-entering this method: changing
        // layoutParams schedules another layout, which would loop forever and
        // starve the video surface.
        applyingAspect = true
        try {
            lp.width = targetWidth
            lp.height = targetHeight
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
            surfaceView.pivotX = targetWidth / 2f
            surfaceView.pivotY = targetHeight / 2f
            surfaceView.scaleX = targetScale
            surfaceView.scaleY = targetScale
        } finally {
            applyingAspect = false
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("self_test", false)) startSelfTest()
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        startStatusPulse()
    }

    override fun onStop() {
        isVisible = false
        stopStatusPulse()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(stateListener)
        // Re-attach the surface: after the activity was in the background the
        // decoder may still point at a dead surface.
        surfaceView.holder.surface?.let {
            AirPlayService.attachSurfaceFromUi(it)
        }
        surfaceView.holder.surface?.let { AirPlayService.attachSurfaceFromUi(it) }
    }

    override fun onPause() {
        AppState.removeListener(stateListener)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkExecutor.shutdownNow()
        AirPlayService.attachSurfaceFromUi(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // A genuinely new native surface: the decoder must re-bind to it even
        // though the Java Surface object may be the same instance.
        AirPlayService.attachSurfaceFromUi(holder.surface, fresh = true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AirPlayService.attachSurfaceFromUi(holder.surface)
        applyAspect()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlayService.attachSurfaceFromUi(null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        MainActivity.hasWindowFocus = hasFocus
        if (!hasFocus) {
            // Covered by the launcher or another app: the surface is about to
            // die, so stop decoding instead of feeding a dead surface.
            AirPlayService.instance?.onUiHidden()
        } else {
            surfaceView.holder.surface?.let {
                AirPlayService.attachSurfaceFromUi(it)
            }
        }
    }

    companion object {
        @Volatile
        var isVisible: Boolean = false

        @Volatile
        var hasWindowFocus: Boolean = false
    }
}
