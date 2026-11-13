package dev.screenguideqi.rtc

import android.content.Context
import android.util.Log
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteOrder
import kotlin.math.*

/** Opt-in synthetic emulator test. Excluded from the delivered release APK. */
object AudioProbe {
    fun configure(builder: JavaAudioDeviceModule.Builder, context: Context): JavaAudioDeviceModule.Builder {
        if (context.filesDir.resolve("audio-test-tone").exists()) {
            var sample = 0L
            builder.setAudioBufferCallback { buffer, _, channels, sampleRate, _, timestamp ->
                val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                data.clear()
                while (data.remaining() >= channels * 2) {
                    val value = (7000 * sin(2 * PI * 440 * sample++ / sampleRate)).toInt().toShort()
                    repeat(channels) { data.putShort(value) }
                }
                timestamp
            }
            builder.setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { Log.d("SGQI.AudioProbe", "record-start") }
                override fun onWebRtcAudioRecordStop() { Log.d("SGQI.AudioProbe", "record-stop") }
            })
        }
        if (context.filesDir.resolve("audio-test-playback").exists()) {
            var frames = 0
            builder.setPlaybackSamplesReadyCallback { samples ->
                if (frames++ % 100 == 0) {
                    val shorts = java.nio.ByteBuffer.wrap(samples.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var energy = 0.0; var real = 0.0; var imaginary = 0.0; var count = 0
                    while (shorts.remaining() >= samples.channelCount) {
                        val value = shorts.get().toDouble()
                        repeat(samples.channelCount - 1) { shorts.get() }
                        val angle = 2 * PI * 440 * count / samples.sampleRate
                        real += value * cos(angle); imaginary += value * sin(angle)
                        energy += value * value; count++
                    }
                    val rms = if (count > 0) sqrt(energy / count) else 0.0
                    val tone = if (energy > 0) 2 * (real * real + imaginary * imaginary) / (count * energy) else 0.0
                    Log.d("SGQI.AudioProbe", "playback rms=%.1f tone440=%.3f".format(java.util.Locale.US, rms, tone))
                }
            }
        }
        return builder
    }
}
