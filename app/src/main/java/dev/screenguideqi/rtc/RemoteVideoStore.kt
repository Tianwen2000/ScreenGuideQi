package dev.screenguideqi.rtc

import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicLong

data class RemoteVideoState(
    val active: Boolean = false,
    val status: String = "等待共享画面",
    val width: Int = 9,
    val height: Int = 16,
)

object RemoteVideoStore {
    private val mutable = MutableStateFlow(RemoteVideoState())
    val state = mutable.asStateFlow()
    private var track: VideoTrack? = null
    private var eglContext: EglBase.Context? = null
    @Volatile private var attached: SurfaceViewRenderer? = null
    private var rendererInitialized = false
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private val generation = AtomicLong()
    private val frameLock = Any()
    private var latestFrame: VideoFrame? = null
    private val frameSink = VideoSink { frame ->
        val attempt = generation.get()
        // Keep one display frame in memory for a new Surface after fullscreen or
        // rotation. Convert textures so retaining it never stalls the decoder.
        val buffer = runCatching { frame.buffer.toI420() }.getOrNull()
        if (buffer != null) {
            val snapshot = VideoFrame(buffer, frame.rotation, frame.timestampNs)
            synchronized(frameLock) {
                if (attempt == generation.get()) {
                    latestFrame?.release(); latestFrame = snapshot
                    attached?.onFrame(frame)
                } else snapshot.release()
            }
        } else attached?.onFrame(frame)
    }

    @Synchronized
    fun publish(value: VideoTrack, context: EglBase.Context) {
        if (track === value) {
            return
        }
        clearLocked()
        track = value
        eglContext = context
        mutable.value = RemoteVideoState(true, "已收到视频轨道，等待实际画面")
        value.addSink(frameSink)
        attached?.let { attachLocked(it) }
    }

    @Synchronized
    fun attach(renderer: SurfaceViewRenderer) {
        attached?.let { detachLocked(it) }
        attached = renderer
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = redrawAfterSurfaceReady(renderer)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = redrawAfterSurfaceReady(renderer)
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }.also { renderer.holder.addCallback(it) }
        attachLocked(renderer)
    }

    @Synchronized
    fun detach(renderer: SurfaceViewRenderer) {
        if (attached === renderer) {
            detachLocked(renderer)
            attached = null
        }
    }

    @Synchronized
    fun clear(message: String = "共享画面已结束") {
        clearLocked()
        mutable.value = RemoteVideoState(false, message)
    }

    private fun attachLocked(renderer: SurfaceViewRenderer) {
        if (track == null) return
        val currentContext = eglContext ?: return
        runCatching {
            if (!rendererInitialized) {
                val attempt = generation.get()
                renderer.init(currentContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        if (attempt == generation.get()) mutable.update {
                            if (it.active) it.copy(status = "已显示共享画面") else it
                        }
                    }
                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        if (width <= 0 || height <= 0 || attempt != generation.get()) return
                        val rotated = rotation % 180 != 0
                        mutable.update {
                            if (it.active) it.copy(width = if (rotated) height else width,
                                height = if (rotated) width else height) else it
                        }
                    }
                })
                rendererInitialized = true
            }
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            synchronized(frameLock) { latestFrame?.let { renderer.onFrame(it) } }
        }.onFailure { mutable.value = RemoteVideoState(false, "远端画面渲染失败") }
    }

    private fun detachLocked(renderer: SurfaceViewRenderer) {
        surfaceCallback?.let { renderer.holder.removeCallback(it) }; surfaceCallback = null
        runCatching { renderer.clearImage() }
        if (rendererInitialized) {
            runCatching { renderer.release() }
            rendererInitialized = false
        }
    }

    private fun redrawAfterSurfaceReady(renderer: SurfaceViewRenderer) {
        // EglRenderer may drop a frame submitted before Surface creation. Submit
        // again after its own SurfaceHolder callback has created the EGL surface.
        renderer.post { redrawLatest(renderer) }
        renderer.postDelayed({ redrawLatest(renderer) }, 100)
    }

    @Synchronized
    private fun redrawLatest(renderer: SurfaceViewRenderer) {
        if (attached === renderer && rendererInitialized && renderer.holder.surface.isValid) {
            synchronized(frameLock) { latestFrame?.let { renderer.onFrame(it) } }
        }
    }

    private fun clearLocked() {
        generation.incrementAndGet()
        track?.removeSink(frameSink)
        synchronized(frameLock) { latestFrame?.release(); latestFrame = null }
        attached?.let { detachLocked(it) }
        // RtpReceiver / PeerConnection own the remote VideoTrack.
        track = null
        eglContext = null
    }
}
