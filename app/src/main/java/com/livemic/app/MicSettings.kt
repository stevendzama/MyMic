package com.livemic.app

import android.content.Context

/** User settings, read live by the audio thread and saved between launches. */
object MicSettings {
    @Volatile var gain = 1.5f
    @Volatile var noiseReduction = true
    @Volatile var antiSqueal = true

    /** Echo gate strength, 0 (off) to 1; see [echoThresholdDb]. */
    @Volatile var echoReduction = 0.5f

    /** Gate threshold: stronger reduction treats louder sounds as a pause, -60 to -20 dBFS. */
    fun echoThresholdDb() = -60f + 40f * echoReduction

    /** Equalizer band centres, matching VLC's 10-band equalizer. */
    val EQ_FREQS = doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
    const val EQ_MAX_DB = 20f

    @Volatile var eqEnabled = false
    @Volatile var eqPreampDb = 0f
    val eqBandsDb = FloatArray(EQ_FREQS.size)

    /**
     * Bumped after any equalizer change so the audio thread rebuilds its filters. The volatile
     * write also makes the new [eqBandsDb] values visible to that thread.
     */
    @Volatile var eqVersion = 0
        private set

    fun eqChanged() {
        eqVersion++
    }

    fun load(context: Context) {
        val prefs = prefs(context)
        gain = prefs.getFloat(KEY_GAIN, gain)
        noiseReduction = prefs.getBoolean(KEY_NOISE, noiseReduction)
        antiSqueal = prefs.getBoolean(KEY_ANTI_SQUEAL, antiSqueal)
        echoReduction = prefs.getFloat(KEY_ECHO, echoReduction)
        eqEnabled = prefs.getBoolean(KEY_EQ_ENABLED, eqEnabled)
        eqPreampDb = prefs.getFloat(KEY_EQ_PREAMP, eqPreampDb)
        prefs.getString(KEY_EQ_BANDS, null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == eqBandsDb.size }
            ?.forEachIndexed { i, db -> eqBandsDb[i] = db }
        eqChanged()
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_GAIN, gain)
            .putBoolean(KEY_NOISE, noiseReduction)
            .putBoolean(KEY_ANTI_SQUEAL, antiSqueal)
            .putFloat(KEY_ECHO, echoReduction)
            .putBoolean(KEY_EQ_ENABLED, eqEnabled)
            .putFloat(KEY_EQ_PREAMP, eqPreampDb)
            .putString(KEY_EQ_BANDS, eqBandsDb.joinToString(","))
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("live_mic", Context.MODE_PRIVATE)

    private const val KEY_GAIN = "gain"
    private const val KEY_NOISE = "noise_reduction"
    private const val KEY_ANTI_SQUEAL = "anti_squeal"
    private const val KEY_ECHO = "echo_reduction"
    private const val KEY_EQ_ENABLED = "eq_enabled"
    private const val KEY_EQ_PREAMP = "eq_preamp_db"
    private const val KEY_EQ_BANDS = "eq_bands_db"
}
