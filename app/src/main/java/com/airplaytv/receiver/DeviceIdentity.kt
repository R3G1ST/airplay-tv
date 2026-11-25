package com.airplaytv.receiver

import android.content.Context
import java.security.SecureRandom

/** Stable per-install identity: AirPlay only needs a unique, unchanged device id. */
object DeviceIdentity {
    private const val PREFS = "airplay_prefs"
    private const val KEY_MAC = "device_mac"
    private const val KEY_NAME = "device_name"
    private const val KEY_ASPECT = "aspect_mode"
    private const val KEY_VOLUME = "last_volume"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_MAC, null)?.let { return it }
        // Locally administered unicast MAC, stable for this install.
        val rnd = SecureRandom()
        val bytes = ByteArray(6).also { rnd.nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        val id = bytes.joinToString("") { "%02X".format(it) }
        prefs.edit().putString(KEY_MAC, id).apply()
        return id
    }

    fun deviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.let { if (it.isNotBlank()) return it }
        return defaultName()
    }

    fun setDeviceName(context: Context, name: String) {
        val clean = name.replace('.', ' ').replace('/', ' ').trim().ifEmpty { "Android TV" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAME, clean).apply()
    }

    /**
     * Last AirPlay volume (0..1). A new cast starts at this level instead of
     * jumping to full volume; the sender can still change it afterwards.
     */
    fun lastVolume(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_VOLUME, 0.7f)
            .coerceIn(0f, 1f)

    fun setLastVolume(context: Context, volume: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    /** 0 = fit (letterbox), 1 = stretch (fill, keeps geometry), 2 = zoom (fill, crops). */
    fun aspectMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ASPECT, 0)

    fun setAspectMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ASPECT, mode).apply()
    }

    private fun defaultName(): String {
        val model = android.os.Build.MODEL?.trim().orEmpty()
        val brand = android.os.Build.BRAND?.trim().orEmpty()
        val base = when {
            model.isEmpty() -> "Android TV"
            model.length > 24 -> model.substring(0, 24)
            else -> model
        }
        val genericBrand = brand.equals("google", ignoreCase = true) || brand.isBlank()
        val full = if (!genericBrand && !base.lowercase().contains(brand.lowercase())) {
            "$brand $base"
        } else {
            base
        }
        // Dots terminate a DNS-SD label and slashes/control chars break clients.
        val clean = full.replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .ifEmpty { "Android TV" }
        // Many Android TVs report their model as "Chromecast" (the built-in Cast
        // target has the same name), which makes the AirPlay entry impossible to
        // tell apart in the senders' list. Use a distinct, honest default.
        val generic = setOf("chromecast", "android tv", "google tv", "tv")
        return if (clean.lowercase() in generic) "AirPlay TV" else clean
    }
}
