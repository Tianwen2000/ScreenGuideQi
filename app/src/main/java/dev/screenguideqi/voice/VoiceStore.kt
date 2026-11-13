package dev.screenguideqi.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceState(val active: Boolean = false, val status: String = "麦克风未开启")

object VoiceStore {
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    private var stopRecording: (() -> Unit)? = null
    private var endSession: (() -> Unit)? = null

    fun start(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)) }
            .onFailure { stopped("无法启动语音通知，请检查权限后重试") }
    }
    fun active() { mutable.value = VoiceState(true, "麦克风已开启，对方可以听到你的指导") }
    fun bindStop(action: () -> Unit) { stopRecording = action }
    fun bindSessionEnd(action: () -> Unit) { endSession = action }
    fun taskRemoved() { val action = endSession; endSession = null; action?.invoke() }
    fun stopped(message: String = "麦克风已关闭") {
        val action = stopRecording; stopRecording = null
        runCatching { action?.invoke() }
        mutable.value = VoiceState(status = message)
    }
    fun stop(context: Context) {
        endSession = null
        stopped()
        context.stopService(Intent(context, VoiceService::class.java))
    }
}
