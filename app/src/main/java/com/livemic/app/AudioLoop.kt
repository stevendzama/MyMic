package com.livemic.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlin.math.abs
import kotlin.math.max

/**
 * Reads the phone's built-in microphone and plays it straight out of the
 * Bluetooth speaker, on its own high-priority thread.
 */
class AudioLoop(
    private val audioManager: AudioManager,
    private val onError: (String) -> Unit,
) {
    /** Peak input level for the on-screen meter, 0..1. */
    @Volatile
    var level = 0f
        private set

    /** Delay added by this app's own buffers, in ms. Bluetooth adds its own on top. */
    @Volatile
    var appDelayMs = 0f
        private set

    /** Squeal pitches the anti-squeal filter is currently blocking. */
    @Volatile
    var squealsBlocked = 0
        private set

    /** Pitch of the most recent squeal in Hz, or 0 if none yet. */
    @Volatile
    var lastSquealHz = 0.0
        private set

    @Volatile private var running = false
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ pump() }, "LiveMic-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        level = 0f
    }

    /** Points input at the built-in mic and output at the Bluetooth speaker, e.g. after one connects. */
    fun applyRouting() {
        record?.setPreferredDevice(Routing.builtInMic(audioManager))
        track?.setPreferredDevice(Routing.bluetoothOutput(audioManager))
    }

    @SuppressLint("MissingPermission") // MicService is only started once RECORD_AUDIO is granted
    private fun pump() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val inMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
        val outMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, encoding)
        if (inMin <= 0 || outMin <= 0) {
            fail("This phone can't record and play at $rate Hz.")
            return
        }

        // 1.25 ms chunks keep the delay low; Bluetooth itself adds roughly 150-250 ms on top.
        val chunk = rate / 800

        val rec = try {
            AudioRecord.Builder()
                // MIC (not VOICE_COMMUNICATION) so Android doesn't switch the speaker into call mode.
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format(rate, encoding, AudioFormat.CHANNEL_IN_MONO))
                // Small on purpose: if playback stalls, old audio overflows here instead of piling up as delay.
                .setBufferSizeInBytes(max(inMin, chunk * 2 * 2))
                .build()
        } catch (e: Exception) {
            fail("Couldn't open the microphone: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Couldn't open the microphone. Is another app using it?")
            return
        }

        val out = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format(rate, encoding, AudioFormat.CHANNEL_OUT_MONO))
                // Room for up to 250 ms; only as much as needed is used (see resizeBuffer below).
                .setBufferSizeInBytes(max(outMin * 2, rate / 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            rec.release()
            fail("Couldn't open the speaker output: ${e.message}")
            return
        }

        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
        var noiseOn: Boolean? = null

        record = rec
        track = out
        applyRouting()

        // The speaker buffer is where most of the app's delay sits. Start it at 1.25 ms (2.5 ms with
        // the chunk) and grow it by half only when the speaker runs dry (an underrun), so within a
        // few clicks it settles at a size this phone and speaker can play smoothly.
        // Android may refuse to go that small; resizeBuffer then reports the size it really got.
        val maxFrames = out.bufferCapacityInFrames
        val step = rate / 800
        var bufferFrames = step
        fun resizeBuffer(frames: Int) {
            val actual = out.setBufferSizeInFrames(frames.coerceAtMost(maxFrames))
            if (actual > 0) bufferFrames = actual
            appDelayMs = (bufferFrames + chunk) * 1000f / rate
        }
        resizeBuffer(bufferFrames)

        val buf = ShortArray(chunk)
        val samples = FloatArray(chunk)
        val squeal = FeedbackSuppressor(rate)
        val equalizer = Equalizer(rate)
        try {
            // Pre-fill with silence so starting up doesn't count as an underrun.
            out.write(ShortArray(bufferFrames), 0, bufferFrames)
            rec.startRecording()
            out.play()
            var lastUnderruns = out.underrunCount
            while (running) {
                val underruns = out.underrunCount
                if (underruns > lastUnderruns && bufferFrames < maxFrames) resizeBuffer(bufferFrames + max(step, bufferFrames / 2))
                lastUnderruns = underruns

                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    fail("The microphone stopped working (error $n).")
                    break
                }

                val wantNoise = MicSettings.noiseReduction
                if (noise != null && wantNoise != noiseOn) {
                    noise.setEnabled(wantNoise)
                    noiseOn = wantNoise
                }

                for (i in 0 until n) samples[i] = buf[i] / 32768f
                if (MicSettings.antiSqueal) {
                    squeal.process(samples, n)
                    squealsBlocked = squeal.notchCount
                    lastSquealHz = squeal.lastSquealHz
                }
                if (MicSettings.eqEnabled) equalizer.process(samples, n)

                val gain = MicSettings.gain * 32768f
                var peak = 0
                for (i in 0 until n) {
                    val s = (samples[i] * gain).toInt().coerceIn(-32768, 32767)
                    buf[i] = s.toShort()
                    peak = max(peak, abs(s))
                }
                // Fast attack, slow release so the meter is readable.
                level = max(peak / 32767f, level * 0.9f)

                out.write(buf, 0, n)
            }
        } catch (e: Exception) {
            fail("Audio stopped: ${e.message}")
        } finally {
            record = null
            track = null
            noise?.release()
            runCatching { rec.stop() }
            rec.release()
            runCatching { out.stop() }
            out.release()
            level = 0f
        }
    }

    private fun fail(message: String) {
        if (!running) return
        running = false
        onError(message)
    }

    private fun format(rate: Int, encoding: Int, channelMask: Int): AudioFormat =
        AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
}
