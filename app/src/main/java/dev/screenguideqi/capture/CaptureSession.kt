package dev.screenguideqi.capture

import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class CaptureSession(
    val capturer: ScreenCapturerAndroid,
    val textureHelper: SurfaceTextureHelper,
    val videoSource: VideoSource,
    val videoTrack: VideoTrack,
    val egl: EglBase,
    val factory: PeerConnectionFactory,
) {
    private var released = false
    private var closePeer: (() -> Unit)? = null

    @Synchronized
    fun closePeerBeforeRelease(action: () -> Unit) {
        if (released) action() else closePeer = action
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        // The PeerConnection must release its senders before the capture factory is disposed.
        runCatching { closePeer?.invoke() }; closePeer = null
        runCatching { capturer.stopCapture() }
        runCatching { capturer.dispose() }
        runCatching { videoTrack.dispose() }
        runCatching { videoSource.dispose() }
        runCatching { factory.dispose() }
        runCatching { textureHelper.dispose() }
        runCatching { egl.release() }
    }
}
