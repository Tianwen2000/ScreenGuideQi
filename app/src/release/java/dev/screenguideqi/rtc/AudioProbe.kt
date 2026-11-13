package dev.screenguideqi.rtc

import android.content.Context
import org.webrtc.audio.JavaAudioDeviceModule

/** Release builds never replace, inspect or store audio samples. */
object AudioProbe {
    fun configure(builder: JavaAudioDeviceModule.Builder, context: Context) = builder
}
