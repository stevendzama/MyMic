package com.livemic.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Noise gate that cuts the echo tails: the speaker's sound picked up again by the mic and
 * repeated, roughly every Bluetooth delay (about 0.2 s). Close to the phone your voice is
 * much louder than those repeats, so whenever the mic level drops below the threshold
 * (you paused), the gate turns the signal down by 20 dB until you speak again.
 *
 * Samples are floats in -1..1. Call only from the audio thread.
 */
class EchoGate(rate: Int) {
    private var envelope = 0.0
    private var gain = 1.0
    private var holdLeft = 0

    private val holdSamples = (0.08 * rate).toInt()
    private val envelopeRelease = exp(-1.0 / (0.03 * rate))
    private val openCoefficient = exp(-1.0 / (0.002 * rate))
    private val closeCoefficient = exp(-1.0 / (0.06 * rate))

    /** [thresholdDb] is in dBFS; quieter than this counts as a pause. */
    fun process(x: FloatArray, n: Int, thresholdDb: Float) {
        val threshold = 10.0.pow(thresholdDb / 20.0)
        for (i in 0 until n) {
            val level = abs(x[i].toDouble())
            envelope = if (level > envelope) level else envelope * envelopeRelease

            val target = when {
                envelope > threshold -> {
                    holdLeft = holdSamples
                    1.0
                }
                holdLeft > 0 -> {
                    holdLeft--
                    1.0
                }
                else -> CLOSED_GAIN
            }
            // Opens in about 2 ms so first syllables aren't cut; closes over about 60 ms so it sounds smooth.
            val coefficient = if (target > gain) openCoefficient else closeCoefficient
            gain = target + (gain - target) * coefficient
            x[i] = (x[i] * gain).toFloat()
        }
    }

    private companion object {
        /** -20 dB rather than silence, so pauses sound natural. */
        const val CLOSED_GAIN = 0.1
    }
}
