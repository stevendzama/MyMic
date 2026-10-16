package com.livemic.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Stops the speaker from squealing (acoustic feedback: speaker -> mic -> speaker...).
 *
 * - A 120 Hz high-pass removes boomy lows that feed back easily.
 * - Detected squeals get a narrow notch filter on exactly that pitch, and the volume
 *   dips 6 dB and recovers over 1.5 s so the squeal dies out.
 * - A 5 Hz frequency shift, inaudible on speech, keeps the loop from building up on
 *   any single pitch in the first place.
 *
 * Samples are floats in -1..1. Not thread-safe: call only from the audio thread.
 */
class FeedbackSuppressor(private val rate: Int) {

    /** How many squeal pitches are filtered out right now. */
    var notchCount = 0
        private set

    private val highPass = Biquad.highPass(rate, 120.0)
    private val notches = ArrayList<Biquad>()
    private val notchFreqs = ArrayList<Double>()
    private val shifter = FrequencyShifter(rate, 5.0)

    private var duck = 1.0
    private val recoverPerSample = 2.0.pow(1.0 / (1.5 * rate))

    private val history = FloatArray(FFT_SIZE)
    private var historyPos = 0
    private var sinceAnalysis = 0
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val mag = DoubleArray(FFT_SIZE / 2)
    private var candidateBin = -1
    private var candidateHits = 0

    fun process(x: FloatArray, n: Int) {
        for (i in 0 until n) {
            var s = highPass.process(x[i].toDouble())
            for (notch in notches) s = notch.process(s)

            // The detector listens after the notches, so squeals already handled don't re-trigger it.
            history[historyPos] = s.toFloat()
            historyPos = (historyPos + 1) % FFT_SIZE

            s = shifter.process(s)
            duck = (duck * recoverPerSample).coerceAtMost(1.0)
            x[i] = (s * duck).toFloat()
        }

        sinceAnalysis += n
        if (sinceAnalysis >= FFT_SIZE / 2) {
            sinceAnalysis = 0
            detectSqueal()
        }
    }

    /** A squeal is a loud, steady tone standing far above everything else, unlike a voice. */
    private fun detectSqueal() {
        for (k in 0 until FFT_SIZE) {
            re[k] = history[(historyPos + k) % FFT_SIZE] * window[k]
            im[k] = 0.0
        }
        fft(re, im)

        val lo = (200.0 * FFT_SIZE / rate).toInt()
        val hi = (8000.0 * FFT_SIZE / rate).toInt().coerceAtMost(FFT_SIZE / 2 - 2)
        for (k in mag.indices) mag[k] = hypot(re[k], im[k])
        var sum = 0.0
        var peakBin = lo
        for (k in lo..hi) {
            sum += mag[k]
            if (mag[k] > mag[peakBin]) peakBin = k
        }
        val mean = sum / (hi - lo + 1)
        val peak = mag[peakBin]

        val isWhistle = peak > LEVEL_THRESHOLD && peak > mean * PEAK_TO_MEAN && !hasHarmonicPartners(peakBin, peak)
        if (!isWhistle) {
            candidateBin = -1
            candidateHits = 0
            return
        }

        // The 5 Hz shift makes a squeal drift slowly, so allow it to move one bin between checks.
        if (candidateBin >= 0 && abs(peakBin - candidateBin) <= 1) candidateHits++ else candidateHits = 1
        candidateBin = peakBin
        if (candidateHits >= HITS_TO_CONFIRM) {
            addNotch(interpolatedFrequency(peakBin))
            candidateBin = -1
            candidateHits = 0
        }
    }

    /**
     * A voice is a stack of harmonics, so its loudest tone has strong neighbours at simple pitch
     * ratios; a squeal stands alone. Odd multiples (3x) are left out because a clipped squeal has them.
     */
    private fun hasHarmonicPartners(k: Int, peak: Double): Boolean =
        HARMONIC_RATIOS.any { ratio ->
            val center = Math.round(k * ratio).toInt()
            if (center < 2 || center + 1 >= mag.size) return@any false
            maxOf(mag[center - 1], mag[center], mag[center + 1]) > peak * 0.1
        }

    private fun addNotch(freq: Double) {
        duck = 0.5
        if (notchFreqs.any { abs(it - freq) < freq * 0.03 }) return
        if (notches.size >= MAX_NOTCHES) {
            notches.removeAt(0)
            notchFreqs.removeAt(0)
        }
        notches.add(Biquad.notch(rate, freq, 30.0))
        notchFreqs.add(freq)
        notchCount = notches.size
    }

    /** Refines the peak between FFT bins by fitting a parabola to the log magnitudes. */
    private fun interpolatedFrequency(k: Int): Double {
        val a = ln(mag[k - 1] + 1e-12)
        val b = ln(mag[k] + 1e-12)
        val c = ln(mag[k + 1] + 1e-12)
        val denom = a - 2 * b + c
        val offset = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        return (k + offset.coerceIn(-0.5, 0.5)) * rate / FFT_SIZE
    }

    private companion object {
        const val FFT_SIZE = 2048
        const val MAX_NOTCHES = 8
        /** About -45 dBFS for a pure tone (a full-scale tone peaks at FFT_SIZE / 4 with a Hann window). */
        const val LEVEL_THRESHOLD = 3.0
        /** 20 dB above the average of the voice band. */
        const val PEAK_TO_MEAN = 10.0
        /** Six checks in a row, about 130 ms: longer than a voice holds one pure pitch. */
        const val HITS_TO_CONFIRM = 6
        /** Neighbouring-harmonic ratios for a loudest tone that is harmonic 1 to 6 of a voice. */
        val HARMONIC_RATIOS = doubleArrayOf(
            1.0 / 2, 1.0 / 3, 2.0 / 3, 3.0 / 4, 4.0 / 5, 5.0 / 6, 6.0 / 5, 5.0 / 4, 4.0 / 3, 3.0 / 2, 2.0,
        )

        /** In-place iterative radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wr = cos(angle)
                val wi = sin(angle)
                val half = len / 2
                var start = 0
                while (start < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until half) {
                        val a = start + k
                        val b = a + half
                        val vr = re[b] * cr - im[b] * ci
                        val vi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - vr
                        im[b] = im[a] - vi
                        re[a] += vr
                        im[a] += vi
                        val next = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = next
                    }
                    start += len
                }
                len = len shl 1
            }
        }
    }
}

/** Standard second-order filter (RBJ audio EQ cookbook), coefficients already divided by a0. */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    companion object {
        fun highPass(rate: Int, freq: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * freq / rate
            val c = cos(w)
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun notch(rate: Int, freq: Double, q: Double): Biquad {
            val w = 2 * PI * freq / rate
            val c = cos(w)
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(1 / a0, -2 * c / a0, 1 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * Shifts every frequency up by a few Hz. Two all-pass chains give a 90-degree phase pair
 * across the audio band (Olli Niemitalo's design), which is then mixed with a slow sine and cosine.
 */
class FrequencyShifter(rate: Int, shiftHz: Double) {
    private val chainA = AllpassChain(doubleArrayOf(0.6923878, 0.9360654322959, 0.9882295226860, 0.9987488452737))
    private val chainB = AllpassChain(doubleArrayOf(0.4021921162426, 0.8561710882420, 0.9722909545651, 0.9952884791278))
    private var aDelayed = 0.0
    private var phase = 0.0
    private val step = 2 * PI * shiftHz / rate

    fun process(x: Double): Double {
        val real = aDelayed
        aDelayed = chainA.process(x)
        val imag = chainB.process(x)
        phase += step
        if (phase > 2 * PI) phase -= 2 * PI
        return real * cos(phase) - imag * sin(phase)
    }

    private class AllpassChain(coefficients: DoubleArray) {
        private val a2 = DoubleArray(coefficients.size) { coefficients[it] * coefficients[it] }
        private val x1 = DoubleArray(coefficients.size)
        private val x2 = DoubleArray(coefficients.size)
        private val y1 = DoubleArray(coefficients.size)
        private val y2 = DoubleArray(coefficients.size)

        fun process(input: Double): Double {
            var x = input
            for (i in a2.indices) {
                val y = a2[i] * (x + y2[i]) - x2[i]
                x2[i] = x1[i]
                x1[i] = x
                y2[i] = y1[i]
                y1[i] = y
                x = y
            }
            return x
        }
    }
}
