package com.livemic.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object Routing {
    private val bluetoothOutputTypes = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    /** The connected Bluetooth speaker (media profile), or null if none is connected. */
    fun bluetoothOutput(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in bluetoothOutputTypes }

    /** The phone's own microphone ("mouthpiece"), so the speaker's mic is never used. */
    fun builtInMic(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
}
