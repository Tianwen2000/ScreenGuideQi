package dev.screenguideqi.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build

/** Restores the user's previous audio mode when the receiving session ends. */
class SpeakerRoute(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var active = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
        .setOnAudioFocusChangeListener { }.build()
    fun start() {
        if (active) return
        active = true; previousMode = manager.mode
        @Suppress("DEPRECATION")
        previousSpeaker = manager.isSpeakerphoneOn
        manager.requestAudioFocus(focus)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { manager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = true
        }
    }
    fun close() {
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
        else { @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = previousSpeaker }
        manager.mode = previousMode
        manager.abandonAudioFocusRequest(focus)
    }
}
