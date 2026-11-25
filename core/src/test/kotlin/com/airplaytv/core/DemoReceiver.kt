package com.airplaytv.core

import com.airplaytv.core.airplay.AirPlayServer
import com.airplaytv.core.mdns.MdnsResponder
import com.airplaytv.core.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the protocol core as a desktop receiver. Useful for protocol work on a
 * laptop: `./gradlew :core:demo` makes this machine appear in the AirPlay menu
 * of every iPhone/Mac on the LAN, with the full handshake logged.
 */
fun main() {
    Log.minLevel = Log.Level.DEBUG
    Log.sink = { level, tag, msg -> println("$level $tag: $msg") }

    val videoFrames = AtomicLong()
    val audioFrames = AtomicLong()

    val demoPort = System.getenv("AIRPLAY_PORT")?.toIntOrNull() ?: 7100
    val server = AirPlayServer(
        AirPlayServer.Config(name = "AirPlay TV Demo", deviceId = "A1B2C3D4E5F6", port = demoPort),
        object : AirPlayServer.Listener {
            override fun onSessionStarted(deviceName: String?) = println("== session started")
            override fun onSessionEnded() = println("== session ended, frames=${videoFrames.get()}")
            override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) =
                println("== video config ${width}x${height} sps=${sps.size} pps=${pps.size}")
            override fun onVideoFrame(data: ByteArray, ptsMicros: Long) {
                val n = videoFrames.incrementAndGet()
                if (n <= 3 || n % 60 == 0L) println("== video frame #$n ${data.size}b pts=$ptsMicros")
            }
            override fun onAudioFrame(frame: ByteArray, ptsMicros: Long) {
                audioFrames.incrementAndGet()
            }
            override fun onVolumeChanged(volumeDb: Float) = println("== volume $volumeDb dB")
        },
    )
    server.start()

    val mdns = MdnsResponder(
        serviceName = "AirPlay TV Demo",
        deviceId = "A1B2C3D4E5F6",
        port = demoPort,
        airplayTxt = server.airplayTxt(),
        raopTxt = server.raopTxt(),
    )
    mdns.start()
    println("== ready: receiver on port ${server.port}, mDNS running=${mdns.isRunning}")
    println("== address seen by clients: ${server.airplayTxt()["deviceid"]}")

    Runtime.getRuntime().addShutdownHook(Thread {
        mdns.stop()
        server.stop()
    })
    Thread.currentThread().join()
    mdns.stop()
    server.stop()
    println("== stopped")
}
