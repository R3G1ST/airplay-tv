package com.airplaytv.core

import kotlin.system.exitProcess

/**
 * Runs every self-test suite registered below. Each suite is an object with a
 * `@JvmStatic fun run()` method. Missing suites are skipped so that parallel
 * work on different modules never breaks the harness.
 */
private val SUITES = listOf(
    "com.airplaytv.core.plist.PlistVerify",
    "com.airplaytv.core.crypto.CryptoVerify",
    "com.airplaytv.core.fairplay.FairPlayVerify",
    "com.airplaytv.core.fairplay.FairPlayV2Verify",
    "com.airplaytv.core.mdns.MdnsVerify",
    "com.airplaytv.core.alac.AlacVerify",
    "com.airplaytv.core.rtsp.RtspVerify",
    "com.airplaytv.core.airplay.AirPlayVerify",
)

fun main() {
    var failed = 0
    for (name in SUITES) {
        val cls = try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            println("skip  $name (not implemented yet)")
            continue
        }
        val method = cls.methods.firstOrNull { it.name == "run" && it.parameterCount == 0 }
        if (method == null) {
            println("skip  $name (no run())")
            continue
        }
        try {
            method.invoke(cls.getField("INSTANCE").get(null))
            println("ok    $name")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            println("FAIL  $name: ${cause.message}")
            cause.printStackTrace()
            failed++
        }
    }
    if (failed > 0) {
        println("$failed suite(s) failed")
        exitProcess(1)
    }
    println("all suites passed")
}
