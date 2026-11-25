package com.airplaytv.core.alac

/**
 * Decoder configuration for Apple Lossless (ALAC).
 *
 * These values mirror an `ALACSpecificConfig` magic cookie. AirPlay/RAOP sends
 * raw ALAC frames without a cookie, so the receiver supplies the values from
 * the stream SETUP: `frameLength = spf` (352), 16-bit, pb = 40, mb = 10,
 * kb = 14, stereo at 44.1 kHz.
 *
 * @property frameLength samples per frame (352 for AirPlay, 4096 for files)
 * @property bitDepth output bit depth, normally 16
 * @property pb adaptive-Golomb `pb` parameter
 * @property mb adaptive-Golomb `mb` parameter
 * @property kb adaptive-Golomb `kb` parameter (maximum `k`)
 * @property channels number of channels (1 or 2 are handled)
 * @property sampleRate sample rate in Hz
 */
data class AlacConfig(
    val frameLength: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val sampleRate: Int,
) {
    init {
        require(frameLength in 1..65536) { "invalid frameLength: $frameLength" }
        require(bitDepth == 16 || bitDepth == 20 || bitDepth == 24 || bitDepth == 32) {
            "unsupported bitDepth: $bitDepth"
        }
        require(pb in 0..255) { "invalid pb: $pb" }
        require(mb in 0..255) { "invalid mb: $mb" }
        require(kb in 0..31) { "invalid kb: $kb" }
        require(channels in 1..8) { "invalid channels: $channels" }
        require(sampleRate > 0) { "invalid sampleRate: $sampleRate" }
    }
}
