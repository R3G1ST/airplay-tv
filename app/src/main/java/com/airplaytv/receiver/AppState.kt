package com.airplaytv.receiver

import java.util.concurrent.CopyOnWriteArrayList

/** Tiny observable app state shared between the service and the TV UI. */
object AppState {
    enum class Phase { IDLE, CONNECTING, MIRRORING, PLAYING, ERROR }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val title: String = "AirPlay TV",
        val subtitle: String = "Ожидание подключения",
        val detail: String = "",
        val deviceName: String = "",
        val mirrorWidth: Int = 0,
        val mirrorHeight: Int = 0,
        val audioActive: Boolean = false,
        val trackTitle: String = "",
        val trackArtist: String = "",
        val trackAlbum: String = "",
        val artwork: ByteArray? = null,
        val progressPosition: Double = 0.0,
        val progressDuration: Double = 0.0,
        val audioFormat: String = "",
        val progressKnown: Boolean = false,
        val remoteHint: String = "",
        val remoteHintSeq: Int = 0,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val logLines = ArrayDeque<String>()

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(snapshot)
        snapshot = next
        for (l in listeners) l(next)
    }

    @Volatile private var lastLogAt = 0L

    fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 40) logLines.removeFirst()
        }
        // Logs are for diagnostics only; they must never leak into the UI.
        lastLogAt = System.currentTimeMillis()
    }

    fun logSnapshot(): List<String> = synchronized(logLines) { logLines.toList() }
}
