package dev.screenguideqi.rtc

import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Gathers ICE only. Never opens a microphone, camera, or screen capture. */
class StunProbe(private val context: Context, private val report: (String, Boolean) -> Unit) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val done = AtomicBoolean(false)
    private val host = AtomicInteger()
    private val srflx = AtomicInteger()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null

    fun start() {
        worker.schedule({ finish(false) }, 15, TimeUnit.SECONDS)
        worker.execute {
            if (done.get()) return@execute
            try {
                synchronized(StunProbe::class.java) {
                    if (!initialized) {
                        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                        initialized = true
                    }
                }
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                val config = PeerConnection.RTCConfiguration(listOf(
                    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                )).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
                peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        if (done.get()) return
                        when (candidate.sdp.substringAfter(" typ ", "").substringBefore(' ')) {
                            "host" -> host.incrementAndGet()
                            "srflx" -> srflx.incrementAndGet()
                        }
                        report("本机候选 ${host.get()} · 公网映射候选 ${srflx.get()}（尚未验证直连）", false)
                    }
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) finish(false)
                    }
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                    override fun onAddStream(stream: MediaStream) {}
                    override fun onRemoveStream(stream: MediaStream) {}
                    override fun onDataChannel(channel: DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                }) ?: error("PeerConnection creation failed")
                channel = peer!!.createDataChannel("connectivity-probe", DataChannel.Init())
                peer!!.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (!done.get()) peer?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String) = finish(true)
                            override fun onCreateSuccess(description: SessionDescription) {}
                            override fun onCreateFailure(error: String) {}
                        }, description)
                    }
                    override fun onCreateFailure(error: String) = finish(true)
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, MediaConstraints())
            } catch (_: Exception) { finish(true) }
            catch (_: LinkageError) { finish(true) }
        }
    }

    private fun finish(failed: Boolean, cancelled: Boolean = false) {
        if (!done.compareAndSet(false, true)) return
        worker.execute {
            runCatching { channel?.close() }; runCatching { channel?.dispose() }; channel = null
            runCatching { peer?.close() }; runCatching { peer?.dispose() }; peer = null
            runCatching { factory?.dispose() }; factory = null
            if (!cancelled) report(
                when {
                    failed -> "WebRTC 检测失败，请核对设备 ABI 和网络"
                    srflx.get() > 0 -> "已获得 ${srflx.get()} 个公网映射候选；这不代表跨网直连成功"
                    host.get() > 0 -> "仅获得本机候选；公网直连能力仍待实际测试"
                    else -> "未获得 ICE 候选，请检查网络后重试"
                }, true,
            )
            worker.shutdownNow()
        }
    }
    fun close() = finish(false, true)
    companion object { @Volatile private var initialized = false }
}
