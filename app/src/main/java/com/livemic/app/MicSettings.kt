package com.livemic.app

import android.content.Context

/** User settings, read live by the audio thread and saved between launches. */
object MicSettings {
    @Volatile var gain = 1.5f
    @Volatile var noiseReduction = true

    fun load(context: Context) {
        val prefs = prefs(context)
        gain = prefs.getFloat(KEY_GAIN, gain)
        noiseReduction = prefs.getBoolean(KEY_NOISE, noiseReduction)
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_GAIN, gain)
            .putBoolean(KEY_NOISE, noiseReduction)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("live_mic", Context.MODE_PRIVATE)

    private const val KEY_GAIN = "gain"
    private const val KEY_NOISE = "noise_reduction"
}
