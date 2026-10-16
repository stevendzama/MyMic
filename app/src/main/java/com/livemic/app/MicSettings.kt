package com.livemic.app

import android.content.Context

/** User settings, read live by the audio thread and saved between launches. */
object MicSettings {
    @Volatile var gain = 1.5f
    @Volatile var noiseReduction = true
    @Volatile var antiSqueal = true

    fun load(context: Context) {
        val prefs = prefs(context)
        gain = prefs.getFloat(KEY_GAIN, gain)
        noiseReduction = prefs.getBoolean(KEY_NOISE, noiseReduction)
        antiSqueal = prefs.getBoolean(KEY_ANTI_SQUEAL, antiSqueal)
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_GAIN, gain)
            .putBoolean(KEY_NOISE, noiseReduction)
            .putBoolean(KEY_ANTI_SQUEAL, antiSqueal)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("live_mic", Context.MODE_PRIVATE)

    private const val KEY_GAIN = "gain"
    private const val KEY_NOISE = "noise_reduction"
    private const val KEY_ANTI_SQUEAL = "anti_squeal"
}
