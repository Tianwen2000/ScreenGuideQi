package dev.screenguideqi.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.screenguideqi.BuildConfig
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.RtpSender
import org.webrtc.DataChannel
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import dev.screenguideqi.voice.SpeakerRoute
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class WebRtcEngine(
    context: Context,
    private val role: Role,
    private val events: Events,
    private val ice: IceConfiguration = IceConfiguration.direct(),
) {
    enum class Role { OWNER, VIEWER }
    interface Events {
        fun onLocalDescription(description: SessionDescription)
        fun onLocalIce(candidate: IceCandidate)
        fun onRemoteVideo(track: VideoTrack, eglContext: org.webrtc.EglBase.Context)
        fun onState(message: String)
        fun onStats(message: String)
        fun onVideoReceived()
        fun onFailure(message: String)
        fun onConnected(connected: Boolean)
        fun onVoiceStatus(message: String)
        fun onAudioStats(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var factory: PeerConnectionFactory? = null
    private var ownsFactory = false
    private var peer: PeerConnection? = null
    private val pendingCandidates = ArrayDeque<IceCandidate>()
    private var remoteDescriptionSet = false
    private val remoteEgl = if (role == Role.VIEWER) org.webrtc.EglBase.create() else null
    private val statsExecutor = Executors.newSingleThreadScheduledExecutor()
    private val statsLock = Any()
    private val remoteVideoPublished = AtomicBoolean(false)
    private val decodedVideoReported = AtomicBoolean(false)
    private var microphoneTrack: AudioTrack? = null
    private var microphoneSource: AudioSource? = null
    private var audioSender: RtpSender? = null
    private var controls: DataChannel? = null
    private var voiceEnabled = false
    private var voiceNegotiated = false
    private var voiceOfferPending = false
    private val voiceTimeout = Runnable { if (voiceEnabled && !voiceNegotiated) events.onVoiceStatus("语音连接超时，请关闭后重试") }
    private val speaker = SpeakerRoute(appContext)

    fun startOwner(track: VideoTrack, ownerFactory: PeerConnectionFactory) {
        if (closed.get()) return
        factory = ownerFactory
        createPeer().also { connection ->
            connection.setAudioRecording(false)
            connection.addTrack(track, listOf("screen-stream"))
            bindControls(connection.createDataChannel("session", DataChannel.Init()))
            connection.createOffer(sdpObserver(onCreate = { description ->
                connection.setLocalDescription(sdpObserver(onSet = {
                    events.onLocalDescription(description)
                }), description)
            }), MediaConstraints())
        }
    }

    fun startViewer() {
        if (closed.get()) return
        ensureFactory()
        val connection = createPeer()
        connection.setAudioRecording(false)
        connection.setAudioPlayout(false)
        // Start with video only. Even an inactive pre-negotiated audio sender can
        // initialize AudioRecord; add voice only after permission and its service.
        events.onState("等待共享端发送屏幕")
    }

    fun setRemoteDescription(type: String, sdp: String, voice: Boolean = false) {
        val connection = peer ?: return fail("WebRTC 连接尚未创建")
        val descriptionType = when (type) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return fail("收到未知的 SDP 类型")
        }
        if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", "remote-description ${descriptionType.name}")
        connection.setRemoteDescription(sdpObserver(onSet = {
            remoteDescriptionSet = true
            while (pendingCandidates.isNotEmpty()) connection.addIceCandidate(pendingCandidates.removeFirst())
            if (descriptionType == SessionDescription.Type.OFFER) {
                if (role == Role.VIEWER) {
                    connection.transceivers.forEach { transceiver ->
                        if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                            check(transceiver.setDirection(if (voiceEnabled) RtpTransceiver.RtpTransceiverDirection.SEND_ONLY else RtpTransceiver.RtpTransceiverDirection.INACTIVE))
                            audioSender = transceiver.sender
                            if (voiceEnabled) check(transceiver.sender.setTrack(microphoneTrack, false))
                        } else if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                        }
                    }
                }
                connection.createAnswer(sdpObserver(onCreate = { description ->
                    connection.setLocalDescription(sdpObserver(onSet = {
                        if (voice) {
                            sendControl(JSONObject().put("type", "voice.answer").put("sdp", description.description).toString())
                            voiceNegotiated = voiceEnabled
                            if (voiceEnabled) connection.setAudioRecording(true)
                            main.removeCallbacks(voiceTimeout)
                            sendVoiceState()
                        } else events.onLocalDescription(description)
                    }), description)
                }), MediaConstraints())
            } else if (voice) voiceOfferPending = false else events.onState("已设置远端 SDP，等待画面")
        }), SessionDescription(descriptionType, sdp))
    }

    fun addRemoteIce(candidate: IceCandidate) {
        if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", "remote-ice")
        val connection = peer ?: return fail("收到 ICE 时 WebRTC 连接尚未创建")
        if (!remoteDescriptionSet) pendingCandidates.addLast(candidate) else connection.addIceCandidate(candidate)
    }

    private fun createPeer(): PeerConnection {
        if (peer != null) return peer!!
        val rtcConfig = PeerConnection.RTCConfiguration(ice.servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val created = factory!!.createPeerConnection(rtcConfig, observer())
            ?: error("PeerConnection creation failed")
        peer = created
        statsExecutor.scheduleWithFixedDelay({ collectStats() }, 3, 3, TimeUnit.SECONDS)
        return created
    }

    private fun ensureFactory() {
        if (factory != null) return
        synchronized(LOCK) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
                initialized = true
            }
        }
        val audio = AudioProbe.configure(JavaAudioDeviceModule.builder(appContext), appContext)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) { events.onVoiceStatus("麦克风启动失败，请关闭语音后重试") }
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) {
                    events.onVoiceStatus("麦克风被系统限制，请关闭语音后重试")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String) { events.onVoiceStatus("麦克风出现异常，请关闭语音后重试") }
            }).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(remoteEgl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(remoteEgl!!.eglBaseContext))
            .createPeerConnectionFactory()
        ownsFactory = true
        audio.release()
    }

    private fun observer(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", "ice-state ${state.name}")
            if (state == PeerConnection.IceConnectionState.FAILED) fail("WebRTC ICE 连接失败")
            else events.onState("WebRTC ICE：${state.name}")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", "gathering-state ${state.name}")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", "local-ice")
            events.onLocalIce(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) { stream.videoTracks.firstOrNull()?.let { publishRemote(it) } }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) { bindControls(channel) }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.let { publishRemote(it) }
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let { publishRemote(it) }
            (transceiver.receiver.track() as? AudioTrack)?.let {
                if (role == Role.OWNER) it.setEnabled(true) else it.setEnabled(false)
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", "peer-state ${state.name}")
            if (state == PeerConnection.PeerConnectionState.FAILED) fail("WebRTC 连接失败")
            else events.onState("WebRTC：${state.name}")
            events.onConnected(state == PeerConnection.PeerConnectionState.CONNECTED)
        }
    }

    private fun publishRemote(track: VideoTrack) {
        if (closed.get() || !remoteVideoPublished.compareAndSet(false, true)) return
        val egl = remoteEgl ?: return
        events.onRemoteVideo(track, egl.eglBaseContext)
    }

    private fun sdpObserver(onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            if (BuildConfig.DEBUG) {
                val lines = description.description.lineSequence().toList()
                val videos = lines.count { it.startsWith("m=video ") }
                val rejected = lines.count { it.startsWith("m=video 0 ") }
                val codecs = lines.count { it.startsWith("a=rtpmap:") }
                Log.d("SGQI.Rtc", "local-description ${description.type.name} videoSections=$videos rejectedSections=$rejected codecCount=$codecs")
            }
            if (BuildConfig.DEBUG) {
                val audio = description.description.substringAfter("m=audio ", "").substringBefore("\nm=")
                val direction = listOf("sendonly", "recvonly", "inactive", "sendrecv").firstOrNull { audio.contains("a=$it") }
                Log.d("SGQI.Rtc", "audio-section present=${audio.isNotEmpty()} direction=$direction")
            }
            main.post { if (!closed.get()) runCatching { onCreate(description) }.onFailure { fail("媒体协商失败，请重新配对") } }
        }
        override fun onSetSuccess() { main.post { if (!closed.get()) runCatching(onSet).onFailure { fail("媒体协商失败，请重新配对") } } }
        override fun onCreateFailure(error: String) { fail("SDP 创建失败") }
        override fun onSetFailure(error: String) { fail("SDP 设置失败") }
    }

    private fun fail(message: String) { if (!closed.get()) events.onFailure(message) }

    private fun collectStats() {
        synchronized(statsLock) {
            if (closed.get()) return
            val connection = peer ?: return
            runCatching {
                connection.getStats { report ->
                    if (closed.get()) return@getStats
                    val summary = VideoStats.summarize(report.statsMap, sending = role == Role.OWNER)
                    if (BuildConfig.DEBUG) Log.d("SGQI.Rtc", summary.message)
                    events.onStats(summary.message)
                    if (summary.decodedVideo && decodedVideoReported.compareAndSet(false, true)) events.onVideoReceived()
                    val audio = report.statsMap.values.filter { it.type == (if (role == Role.VIEWER) "outbound-rtp" else "inbound-rtp") && it.members["kind"] == "audio" }
                    if (audio.isNotEmpty()) {
                        val key = if (role == Role.VIEWER) "bytesSent" else "bytesReceived"
                        val bytes = audio.sumOf { (it.members[key] as? Number)?.toLong() ?: 0L }
                        val energy = audio.sumOf { (it.members["totalAudioEnergy"] as? Number)?.toDouble() ?: 0.0 }
                        val samples = audio.sumOf { (it.members["totalSamplesReceived"] as? Number)?.toLong() ?: 0L }
                        events.onAudioStats("语音统计：${if (role == Role.VIEWER) "发送" else "收到"} $bytes 字节；采样 $samples；能量 %.5f".format(java.util.Locale.US, energy))
                    }
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacks(voiceTimeout)
        stopMicrophone()
        statsExecutor.shutdownNow()
        RemoteVideoStore.clear()
        runCatching { controls?.unregisterObserver(); controls?.close(); controls?.dispose() }; controls = null
        synchronized(statsLock) {
            runCatching { peer?.close() }; runCatching { peer?.dispose() }; peer = null
        }
        pendingCandidates.clear()
        runCatching { microphoneTrack?.dispose() }; microphoneTrack = null
        runCatching { microphoneSource?.dispose() }; microphoneSource = null
        speaker.close()
        if (ownsFactory) runCatching { factory?.dispose() }
        factory = null
        runCatching { remoteEgl?.release() }
    }

    fun startMicrophone(): Boolean {
        if (closed.get() || role != Role.VIEWER) return false
        return runCatching {
            val connection = peer ?: return false
            if (microphoneTrack == null) {
                microphoneSource = factory!!.createAudioSource(MediaConstraints())
                microphoneTrack = factory!!.createAudioTrack("guide-voice", microphoneSource)
            }
            microphoneTrack!!.setEnabled(true)
            voiceEnabled = true
            if (voiceNegotiated) {
                check(audioSender?.setTrack(microphoneTrack, false) == true)
                connection.setAudioRecording(true)
            } else {
                main.removeCallbacks(voiceTimeout)
                main.postDelayed(voiceTimeout, 20_000)
            }
            sendVoiceState()
            true
        }.getOrElse { stopMicrophone(); false }
    }

    fun stopMicrophone() {
        voiceEnabled = false
        main.removeCallbacks(voiceTimeout)
        runCatching { peer?.setAudioRecording(false) }
        runCatching { audioSender?.setTrack(null, false) }
        runCatching { microphoneTrack?.setEnabled(false) }
        sendVoiceState()
    }

    private fun sendVoiceState() {
        if (role == Role.VIEWER) sendControl(if (!voiceEnabled) "voice:off" else if (voiceNegotiated) "voice:on" else "voice:prepare")
    }

    private fun sendControl(message: String) {
        val channel = controls ?: return
        if (!closed.get() && channel.state() == DataChannel.State.OPEN)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false))
    }

    private fun offerVoice() {
        if (closed.get() || role != Role.OWNER || voiceOfferPending) return
        val connection = peer ?: return
        voiceOfferPending = true
        runCatching {
            val audio = connection.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
                ?: connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            check(audio.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            connection.createOffer(sdpObserver(onCreate = { description ->
                connection.setLocalDescription(sdpObserver(onSet = {
                    sendControl(JSONObject().put("type", "voice.offer").put("sdp", description.description).toString())
                }), description)
            }), MediaConstraints())
        }.onFailure { voiceOfferPending = false; sendControl("voice:error") }
    }

    private fun bindControls(channel: DataChannel) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { bindControls(channel) }; return }
        if (closed.get()) { channel.dispose(); return }
        if (channel.label() != "session") { channel.close(); channel.dispose(); return }
        controls = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() { main.post { if (!closed.get()) sendVoiceState() } }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed.get() || buffer.binary || buffer.data.remaining() > 65_536) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val message = String(bytes, Charsets.UTF_8)
                main.post {
                    if (closed.get()) return@post
                    runCatching {
                        when {
                            message == "voice:prepare" && role == Role.OWNER -> offerVoice()
                            message == "voice:on" && role == Role.OWNER -> {
                                events.onVoiceStatus("指导端麦克风已开启"); speaker.start(); peer?.setAudioPlayout(true)
                            }
                            message == "voice:off" && role == Role.OWNER -> {
                                events.onVoiceStatus("指导端麦克风已关闭"); peer?.setAudioPlayout(false); speaker.close()
                            }
                            message == "voice:error" && role == Role.VIEWER -> events.onVoiceStatus("语音连接失败，请关闭后重试")
                            message.startsWith("{") -> {
                                val json = JSONObject(message)
                                val sdp = json.getString("sdp")
                                require(sdp.length <= 60_000)
                                when (json.getString("type")) {
                                    "voice.offer" -> if (role == Role.VIEWER) setRemoteDescription("offer", sdp, true)
                                    "voice.answer" -> if (role == Role.OWNER) setRemoteDescription("answer", sdp, true)
                                }
                            }
                        }
                    }.onFailure { fail("语音协商异常，会话已结束") }
                }
            }
        })
    }

    companion object {
        private val LOCK = Any()
        private var initialized = false
    }
}
