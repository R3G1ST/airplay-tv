package com.airplaytv.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal logging facade. The Android layer installs a sink that forwards to
 * logcat and to the on-screen log; the core stays free of Android APIs.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var minLevel: Level = Level.INFO

    @Volatile
    var sink: ((Level, String, String) -> Unit)? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        log(Level.ERROR, tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    private fun log(level: Level, tag: String, msg: String) {
        if (level.ordinal < minLevel.ordinal) return
        val line = "${timeFormat.format(Date())} ${level.name.first()} $tag: $msg"
        sink?.invoke(level, tag, msg)
        if (sink == null) println(line)
    }
}
