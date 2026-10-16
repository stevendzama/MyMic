package com.livemic.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var route: TextView
    private lateinit var warning: TextView
    private lateinit var meter: ProgressBar
    private lateinit var gainLabel: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var ticks = 0

    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audioManager = getSystemService(AudioManager::class.java)
        MicSettings.load(this)

        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        route = findViewById(R.id.route)
        warning = findViewById(R.id.warning)
        meter = findViewById(R.id.meter)
        gainLabel = findViewById(R.id.gain_label)

        toggle.setOnClickListener {
            if (MicService.activeLoop != null) stopMic() else startMicWithPermission()
        }

        // Slider runs 50%..400%.
        val gain = findViewById<SeekBar>(R.id.gain)
        gain.max = 350
        gain.progress = (MicSettings.gain * 100).roundToInt() - 50
        showGain()
        gain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                MicSettings.gain = (progress + 50) / 100f
                showGain()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {
                MicSettings.save(this@MainActivity)
            }
        })

        val noise = findViewById<Switch>(R.id.noise)
        noise.isChecked = MicSettings.noiseReduction
        noise.setOnCheckedChangeListener { _, on ->
            MicSettings.noiseReduction = on
            MicSettings.save(this)
        }
    }

    override fun onResume() {
        super.onResume()
        ticks = 0
        ui.post(refreshTick)
    }

    override fun onPause() {
        ui.removeCallbacks(refreshTick)
        super.onPause()
    }

    private fun startMicWithPermission() {
        val missing = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            // Only for the "mic is on" notification; the mic works without it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) startMic() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            startMic()
        } else {
            status.text = getString(R.string.need_mic_permission)
        }
    }

    private fun startMic() {
        startForegroundService(Intent(this, MicService::class.java).setAction(MicService.ACTION_START))
    }

    private fun stopMic() {
        stopService(Intent(this, MicService::class.java))
    }

    private fun refresh() {
        val loop = MicService.activeLoop
        val live = loop != null

        toggle.isActivated = live
        toggle.text = getString(if (live) R.string.stop else R.string.start)
        meter.progress = ((loop?.level ?: 0f) * 100).roundToInt()
        status.text = when {
            loop != null -> getString(R.string.status_live, loop.appDelayMs)
            MicService.lastError != null -> MicService.lastError
            else -> getString(R.string.status_idle)
        }

        // Device list is a system call, so check it a couple of times a second rather than every frame.
        if (ticks++ % 10 == 0) {
            val speaker = Routing.bluetoothOutput(audioManager)
            route.text = if (speaker != null) {
                getString(R.string.route_bluetooth, speaker.productName)
            } else {
                getString(R.string.route_phone)
            }
            warning.visibility = if (speaker == null) View.VISIBLE else View.GONE
        }
    }

    private fun showGain() {
        gainLabel.text = getString(R.string.gain_label, (MicSettings.gain * 100).roundToInt())
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
