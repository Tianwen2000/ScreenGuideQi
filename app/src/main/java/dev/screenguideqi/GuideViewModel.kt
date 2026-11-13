package dev.screenguideqi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import dev.screenguideqi.rtc.StunProbe
import dev.screenguideqi.signaling.SignalingClient
import dev.screenguideqi.capture.CaptureStore
import dev.screenguideqi.rtc.IceConfiguration
import dev.screenguideqi.rtc.WebRtcEngine
import dev.screenguideqi.rtc.RemoteVideoStore
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import dev.screenguideqi.voice.VoiceStore

data class GuideState(
    val address: String = "", val role: String = "", val codeInput: String = "", val code: String = "",
    val status: String = "尚未连接", val busy: Boolean = false, val paired: Boolean = false,
    val joinRequestId: String? = null, val probeBusy: Boolean = false, val probeStatus: String = "尚未检测",
    val rtcDiagnostic: String = "",
    val connected: Boolean = false, val voiceStatus: String = "麦克风未开启", val audioDiagnostic: String = "",
)

class GuideViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("settings", 0)
    private val mutable = MutableStateFlow(GuideState(address = preferences.getString("signalAddress", null)
        ?: application.getString(R.string.default_signal_url)))
    val state = mutable.asStateFlow()
    private var connection: SignalingClient? = null
    private var probe: StunProbe? = null
    private var responseTimeout: Job? = null
    private var generation = 0
    private var probeGeneration = 0
    private var roomId: String? = null
    private var sessionId: String? = null
    private var ownerToken: String? = null
    private var rtc: WebRtcEngine? = null
    private var iceConfiguration: IceConfiguration = IceConfiguration.direct()
    private var mediaReadySent = false
    private var mediaTimeout: Job? = null
    private var disconnectTimeout: Job? = null
    fun authorizationEpoch(): Int = generation

    init {
        viewModelScope.launch {
            var wasCapturing = false
            CaptureStore.state.collect { capture ->
                if (capture.active && state.value.paired && state.value.role == "owner") startCaptureNegotiation()
                if (wasCapturing && !capture.active && state.value.role == "owner" && rtc != null) {
                    if (state.value.busy) {
                        connection?.send("session.end", roomId, sessionId)
                        end("屏幕采集已停止，会话已结束")
                    }
                }
                wasCapturing = capture.active
            }
        }
        viewModelScope.launch {
            VoiceStore.state.collect { voice ->
                if (voice.active && state.value.role == "viewer" && state.value.connected) {
                    val engine = rtc
                    if (engine != null && engine.startMicrophone()) VoiceStore.bindStop { engine.stopMicrophone() }
                    else VoiceStore.stop(getApplication())
                } else if (state.value.role == "viewer") rtc?.stopMicrophone()
                if (state.value.role == "viewer") mutable.update { it.copy(voiceStatus = voice.status) }
            }
        }
    }

    fun address(value: String) { if (!state.value.busy) mutable.update { it.copy(address = value) } }
    fun code(value: String) { mutable.update { it.copy(codeInput = value.filter(Char::isDigit).take(8)) } }
    fun start(role: String) {
        if (state.value.busy) return
        val address = state.value.address.trim()
        try { SignalingClient.validateAddress(address) } catch (error: IllegalArgumentException) {
            mutable.update { it.copy(status = error.message ?: "服务地址无效") }; return
        }
        if (role == "viewer" && !Regex("[0-9]{8}").matches(state.value.codeInput)) {
            mutable.update { it.copy(status = "请输入八位数字协助码") }; return
        }
        stopConnection()
        val attempt = generation
        preferences.edit { putString("signalAddress", address) }
        mutable.update { it.copy(role = role, busy = true, paired = false, code = "", joinRequestId = null, rtcDiagnostic = "", status = "正在连接服务…") }
        val client = SignalingClient(
            onOpen = { viewModelScope.launch {
                if (attempt != generation) return@launch
                mutable.update { it.copy(status = if (role == "owner") "正在生成协助码…" else "正在申请加入…") }
                connection?.send(if (role == "owner") "room.create" else "room.join", payload = JSONObject().apply {
                    if (role == "viewer") put("code", state.value.codeInput)
                })
            } },
            onMessage = { message -> viewModelScope.launch { if (attempt == generation) receive(message) } },
            onEnded = { message -> viewModelScope.launch { if (attempt == generation) end(message) } },
        )
        connection = client
        try { client.connect(address) } catch (_: Exception) { end("无法创建连接，请检查地址") }
        if (attempt == generation) responseTimeout = viewModelScope.launch {
            delay(45_000)
            if (attempt == generation) end("服务响应超时，请检查网络后重新连接")
        }
    }

    private fun receive(message: JSONObject) {
        val payload = message.optJSONObject("payload") ?: JSONObject()
        when (message.optString("type")) {
            "room.created" -> {
                responseTimeout?.cancel(); responseTimeout = null
                roomId = message.getString("roomId"); ownerToken = payload.getString("ownerToken")
                mutable.update { it.copy(code = payload.getString("code"), status = "等待对方输入协助码（五分钟内有效）") }
            }
            "join.waiting" -> {
                responseTimeout?.cancel(); responseTimeout = null
                roomId = message.getString("roomId"); mutable.update { it.copy(status = "等待共享端确认…") }
            }
            "join.requested" -> mutable.update { it.copy(joinRequestId = payload.getString("joinRequestId"), status = "有设备申请查看，请确认是你认识的人") }
            "room.waiting" -> mutable.update { it.copy(joinRequestId = null, status = "已返回等待状态，原协助码有效期不变") }
            "session.accepted" -> {
                responseTimeout?.cancel(); responseTimeout = null
                roomId = message.getString("roomId"); sessionId = message.getString("sessionId")
                // Stage 1 servers attach short-lived TURN credentials; stage 0 keeps direct().
                iceConfiguration = IceConfiguration.fromSignaling(payload.optJSONObject("ice"))
                    ?: IceConfiguration.direct()
                mutable.update { it.copy(paired = true, connected = false, code = "", joinRequestId = null, rtcDiagnostic = "", audioDiagnostic = "", voiceStatus = "麦克风未开启", status = "两端配对已确认") }
            }
            "capture.ready" -> {
                mutable.update { it.copy(status = "共享端已授权，正在建立视频连接…") }
                if (state.value.role == "viewer") startViewerRtc()
            }
            "sdp.offer" -> {
                if (state.value.role == "viewer") {
                    startViewerRtc()
                    rtc?.setRemoteDescription(payload.optString("type"), payload.optString("sdp"))
                }
            }
            "sdp.answer" -> {
                if (state.value.role == "owner") rtc?.setRemoteDescription(payload.optString("type"), payload.optString("sdp"))
            }
            "ice.candidate" -> {
                val candidate = IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex", -1), payload.optString("candidate"))
                if (candidate.sdpMid.isNullOrBlank() || candidate.sdpMLineIndex < 0 || candidate.sdp.isBlank()) {
                    end("收到无效的连接候选，连接已结束")
                } else rtc?.addRemoteIce(candidate)
            }
            "join.rejected" -> end("共享端拒绝了请求，或请求已超时")
            "session.ended" -> end(if (payload.optString("reason") == "timeout") "等待超时，会话已结束" else "对方已结束会话")
            "error" -> end(when (payload.optString("code")) {
                "ROOM_UNAVAILABLE" -> "协助码无效、已过期或暂不可用"
                "RATE_LIMITED" -> "请求过于频繁，请稍后重试"
                "CAPACITY_REACHED" -> "服务暂时繁忙，请稍后重试"
                "NOT_AUTHORIZED" -> "操作未获授权，连接已结束"
                else -> "服务未能完成请求，连接已结束"
            })
        }
    }

    fun decide(accept: Boolean) {
        val request = state.value.joinRequestId ?: return
        connection?.send(if (accept) "join.accept" else "join.reject", roomId, payload = JSONObject()
            .put("joinRequestId", request).put("ownerToken", ownerToken))
        mutable.update { it.copy(joinRequestId = null, status = "正在提交确认…") }
    }
    fun stop() {
        rtc?.close(); rtc = null
        connection?.send("session.end", roomId, sessionId)
        // Local cleanup never waits for delivery; the server also handles socket closure.
        end("已断开，协助码和会话凭据已清除")
    }
    private fun end(message: String) {
        stopConnection()
        mutable.update { it.copy(busy = false, paired = false, connected = false, code = "", codeInput = "", joinRequestId = null, status = message, voiceStatus = "麦克风未开启") }
    }
    private fun stopConnection() {
        responseTimeout?.cancel(); responseTimeout = null
        mediaReadySent = false
        mediaTimeout?.cancel(); mediaTimeout = null
        disconnectTimeout?.cancel(); disconnectTimeout = null
        VoiceStore.stop(getApplication())
        CaptureStore.stop(getApplication())
        rtc?.close(); rtc = null
        RemoteVideoStore.clear()
        iceConfiguration = IceConfiguration.direct()
        generation++; connection?.close(); connection = null
        roomId = null; sessionId = null; ownerToken = null
    }
    fun voiceAllowed() {
        if (state.value.role == "viewer" && state.value.connected) {
            VoiceStore.bindSessionEnd(::stop)
            VoiceStore.start(getApplication())
        }
    }
    fun stopVoice() { VoiceStore.stop(getApplication()) }
    fun voiceDenied() { mutable.update { it.copy(voiceStatus = "未允许麦克风或通知权限，仍可查看屏幕；可在系统设置中开启权限") } }
    fun probe() {
        if (state.value.probeBusy) return
        val attempt = ++probeGeneration
        mutable.update { it.copy(probeBusy = true, probeStatus = "正在收集 ICE 候选（约十五秒）…") }
        probe = StunProbe(getApplication()) { message, complete -> viewModelScope.launch {
            if (attempt == probeGeneration && (state.value.probeBusy || complete)) {
                mutable.update { it.copy(probeStatus = message, probeBusy = !complete) }
            }
        } }.also { it.start() }
    }

    fun startCaptureNegotiation() {
        if (state.value.role != "owner" || !state.value.paired || rtc != null) return
        val capture = CaptureStore.activeSession() ?: run {
            mutable.update { it.copy(status = "屏幕采集尚未准备好") }
            return
        }
        mediaReadySent = false
        val engine = createRtc(WebRtcEngine.Role.OWNER)
        rtc = engine
        armMediaTimeout()
        capture.closePeerBeforeRelease(engine::close)
        if (!connection!!.send("capture.ready", roomId, sessionId)) {
            end("无法发送屏幕就绪状态")
            return
        }
        try { engine.startOwner(capture.videoTrack, capture.factory) } catch (_: Exception) {
            end("无法启动视频编码或建立连接，请停止后重试")
            return
        }
        mutable.update { it.copy(status = "正在建立屏幕连接…") }
    }

    private fun startViewerRtc() {
        if (rtc != null) return
        rtc = createRtc(WebRtcEngine.Role.VIEWER)
        armMediaTimeout()
        try { rtc?.startViewer() } catch (_: Exception) { end("无法启动视频解码或建立连接，请重试") }
    }
    private fun armMediaTimeout() {
        mediaTimeout?.cancel()
        val attempt = generation
        mediaTimeout = viewModelScope.launch { delay(90_000); if (attempt == generation) end("屏幕连接超时：当前网络可能无法直连，请换网后重新配对") }
    }

    private fun createRtc(role: WebRtcEngine.Role): WebRtcEngine {
        val attempt = generation
        return WebRtcEngine(getApplication(), role, object : WebRtcEngine.Events {        override fun onLocalDescription(description: SessionDescription) {
            viewModelScope.launch {
                if (attempt != generation) return@launch
                val type = if (description.type == SessionDescription.Type.OFFER) "sdp.offer" else "sdp.answer"
                val sdpType = if (description.type == SessionDescription.Type.OFFER) "offer" else "answer"
                connection?.send(type, roomId, sessionId, JSONObject().put("type", sdpType).put("sdp", description.description))
            }
        }
        override fun onLocalIce(candidate: IceCandidate) {
            viewModelScope.launch {
                if (attempt != generation) return@launch
                connection?.send("ice.candidate", roomId, sessionId, JSONObject()
                    .put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid ?: "0").put("sdpMLineIndex", candidate.sdpMLineIndex))
            }
        }
        override fun onRemoteVideo(track: VideoTrack, eglContext: org.webrtc.EglBase.Context) {
            viewModelScope.launch {
                if (attempt != generation) return@launch
                RemoteVideoStore.publish(track, eglContext)
                mutable.update { it.copy(status = "已收到视频轨道，等待实际画面…") }
            }
        }
        override fun onVideoReceived() {
            viewModelScope.launch {
                if (attempt != generation) return@launch
                if (!mediaReadySent && state.value.role == "viewer") {
                    mediaTimeout?.cancel(); mediaTimeout = null
                    mediaReadySent = true
                    connection?.send("media.ready", roomId, sessionId)
                }
                mutable.update { it.copy(status = "已解码共享画面") }
            }
        }
        override fun onState(message: String) { viewModelScope.launch {
            if (attempt == generation) mutable.update { it.copy(status = message) }
        } }
        override fun onStats(message: String) { viewModelScope.launch {
            if (attempt == generation) mutable.update { it.copy(rtcDiagnostic = message) }
        } }
        override fun onFailure(message: String) { viewModelScope.launch { if (attempt == generation) end(message) } }
        override fun onConnected(connected: Boolean) { viewModelScope.launch {
            if (attempt != generation) return@launch
            val wasConnected = state.value.connected
            mutable.update { it.copy(connected = connected) }
            if (connected) {
                disconnectTimeout?.cancel(); disconnectTimeout = null
                if (role == WebRtcEngine.Role.OWNER) { mediaTimeout?.cancel(); mediaTimeout = null }
            } else if (wasConnected && disconnectTimeout == null) {
                stopVoice()
                disconnectTimeout = viewModelScope.launch { delay(10_000); if (attempt == generation) end("媒体连接已中断，请重新配对并授权") }
            }
        } }
        override fun onVoiceStatus(message: String) { viewModelScope.launch {
            if (attempt == generation) {
                if (role == WebRtcEngine.Role.VIEWER) stopVoice()
                mutable.update { it.copy(voiceStatus = message) }
            }
        } }
        override fun onAudioStats(message: String) { viewModelScope.launch {
            if (attempt == generation) mutable.update { it.copy(audioDiagnostic = message) }
        } }
        }, iceConfiguration)
    }
    override fun onCleared() { stopConnection(); probeGeneration++; probe?.close(); super.onCleared() }
}
