package com.livemic.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** VLC-style equalizer: a preamp and 10 vertical band sliders, each +/-20 dB. */
class EqualizerActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var enable: Switch
    private lateinit var hint: TextView
    private lateinit var preamp: Slider
    private val bands = ArrayList<Slider>()

    private val hintTick = object : Runnable {
        override fun run() {
            showSquealHint()
            ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MicSettings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            fitsSystemWindows = true
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(text(getString(R.string.eq_title), 24f, bold = true))

        enable = Switch(this).apply {
            text = getString(R.string.eq_enable)
            setTextColor(getColor(R.color.text))
            textSize = 15f
            minHeight = dp(48)
            isChecked = MicSettings.eqEnabled
            setOnCheckedChangeListener { _, on ->
                MicSettings.eqEnabled = on
                MicSettings.save(this@EqualizerActivity)
            }
        }
        root.addView(enable, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.addView(presetButton(R.string.eq_preset_flat, FLAT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        presets.addView(presetButton(R.string.eq_preset_anti_squeal, ANTI_SQUEAL), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        presets.addView(presetButton(R.string.eq_preset_voice, VOICE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(presets, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        hint = text("", 14f).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val sliders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(sliders, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        preamp = Slider(sliders, getString(R.string.eq_preamp), MicSettings.eqPreampDb) { db ->
            MicSettings.eqPreampDb = db
        }
        MicSettings.EQ_FREQS.forEachIndexed { i, freq ->
            bands += Slider(sliders, bandLabel(freq), MicSettings.eqBandsDb[i]) { db ->
                MicSettings.eqBandsDb[i] = db
            }
        }

        root.addView(text(getString(R.string.eq_tip), 13f, dim = true).apply { setPadding(0, dp(8), 0, 0) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(hintTick)
    }

    override fun onPause() {
        ui.removeCallbacks(hintTick)
        MicSettings.save(this)
        super.onPause()
    }

    /** Points at the slider nearest the last squeal the anti-squeal filter caught. */
    private fun showSquealHint() {
        val squealHz = MicService.activeLoop?.lastSquealHz ?: 0.0
        bands.forEach { it.highlight(false) }
        if (squealHz <= 0.0) {
            hint.text = getString(R.string.eq_hint_idle)
            hint.setTextColor(getColor(R.color.text_dim))
            return
        }
        val nearest = MicSettings.EQ_FREQS.indices.minBy { abs(ln(MicSettings.EQ_FREQS[it] / squealHz)) }
        bands[nearest].highlight(true)
        hint.text = getString(R.string.eq_hint_squeal, formatHz(squealHz), bandLabel(MicSettings.EQ_FREQS[nearest]))
        hint.setTextColor(getColor(R.color.warning))
    }

    private fun presetButton(label: Int, preset: FloatArray) = Button(this).apply {
        text = getString(label)
        isAllCaps = false
        textSize = 13f
        setOnClickListener {
            preamp.set(0f)
            bands.forEachIndexed { i, slider -> slider.set(preset[i]) }
            enable.isChecked = true
            MicSettings.save(this@EqualizerActivity)
        }
    }

    /** One vertical slider column: value on top, slider, band name underneath. */
    private inner class Slider(parent: LinearLayout, label: String, initialDb: Float, onChange: (Float) -> Unit) {
        private val value = text(formatDb(initialDb), 10f)
        private val name = text(label, 11f, dim = true)
        private val bar = SeekBar(this@EqualizerActivity)

        init {
            val column = LinearLayout(this@EqualizerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            parent.addView(column, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            column.addView(value)

            // A SeekBar turned 270 degrees. It is sized to the holder's swapped dimensions after layout.
            val holder = FrameLayout(this@EqualizerActivity)
            column.addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            bar.max = (MicSettings.EQ_MAX_DB * 20).toInt()
            bar.progress = toProgress(initialDb)
            bar.rotation = 270f
            holder.addView(bar, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
            holder.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val lp = bar.layoutParams as FrameLayout.LayoutParams
                val width = bottom - top
                val height = right - left
                if (lp.width != width || lp.height != height) {
                    holder.post {
                        lp.width = width
                        lp.height = height
                        bar.layoutParams = lp
                    }
                }
            }

            column.addView(name)

            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val db = toDb(progress)
                    value.text = formatDb(db)
                    onChange(db)
                    MicSettings.eqChanged()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    MicSettings.save(this@EqualizerActivity)
                }
            })
        }

        fun set(db: Float) {
            bar.progress = toProgress(db)
        }

        fun highlight(on: Boolean) {
            name.setTextColor(getColor(if (on) R.color.warning else R.color.text_dim))
        }

        /** 0.1 dB steps from -20 to +20 dB. */
        private fun toProgress(db: Float) = ((db + MicSettings.EQ_MAX_DB) * 10).roundToInt()

        private fun toDb(progress: Int) = progress / 10f - MicSettings.EQ_MAX_DB
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, dim: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(getColor(if (dim) R.color.text_dim else R.color.text))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    private fun formatDb(db: Float) = String.format(Locale.US, "%+.1f", db)

    private fun bandLabel(freq: Double) =
        if (freq >= 1000) "${(freq / 1000).roundToInt()}K" else freq.roundToInt().toString()

    private fun formatHz(hz: Double) =
        if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000) else "${hz.roundToInt()} Hz"

    private companion object {
        val FLAT = FloatArray(10)

        /** Cuts boom and the 1-6 kHz range where small speakers usually ring. */
        val ANTI_SQUEAL = floatArrayOf(-6f, -3f, 0f, -2f, -4f, -6f, -4f, -2f, -2f, -2f)

        /** Removes rumble and lifts the 3-6 kHz range that makes speech clear. */
        val VOICE = floatArrayOf(-12f, -4f, -1f, 0f, 1f, 3f, 2f, 0f, -2f, -4f)
    }
}
