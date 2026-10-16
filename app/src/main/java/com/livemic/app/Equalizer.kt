package com.livemic.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band graphic equalizer plus preamp, using the bands and +/-20 dB range from [MicSettings].
 * Each band is a peaking filter about one octave wide. Call only from the audio thread.
 */
class Equalizer(private val rate: Int) {
    private val bands = MicSettings.EQ_FREQS.size
    private val b0 = DoubleArray(bands)
    private val b1 = DoubleArray(bands)
    private val b2 = DoubleArray(bands)
    private val a1 = DoubleArray(bands)
    private val a2 = DoubleArray(bands)
    private val x1 = DoubleArray(bands)
    private val x2 = DoubleArray(bands)
    private val y1 = DoubleArray(bands)
    private val y2 = DoubleArray(bands)
    private val active = BooleanArray(bands)
    private var preamp = 1.0
    private var version = -1

    fun process(x: FloatArray, n: Int) {
        if (version != MicSettings.eqVersion) rebuild()
        for (i in 0 until n) {
            var s = x[i] * preamp
            for (b in 0 until bands) {
                if (!active[b]) continue
                val y = b0[b] * s + b1[b] * x1[b] + b2[b] * x2[b] - a1[b] * y1[b] - a2[b] * y2[b]
                x2[b] = x1[b]
                x1[b] = s
                y2[b] = y1[b]
                y1[b] = y
                s = y
            }
            x[i] = s.toFloat()
        }
    }

    /** RBJ cookbook peaking EQ coefficients for every band. */
    private fun rebuild() {
        version = MicSettings.eqVersion
        preamp = 10.0.pow(MicSettings.eqPreampDb / 20.0)
        for (b in 0 until bands) {
            val db = MicSettings.eqBandsDb[b].toDouble()
            val freq = MicSettings.EQ_FREQS[b]
            active[b] = db != 0.0 && freq < rate / 2.0
            if (!active[b]) continue
            val gain = 10.0.pow(db / 40.0)
            val w = 2 * PI * freq / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha / gain
            b0[b] = (1 + alpha * gain) / a0
            b1[b] = -2 * c / a0
            b2[b] = (1 - alpha * gain) / a0
            a1[b] = -2 * c / a0
            a2[b] = (1 - alpha / gain) / a0
        }
    }

    private companion object {
        /** About one octave wide, like a typical graphic equalizer band. */
        const val Q = 1.4
    }
}
