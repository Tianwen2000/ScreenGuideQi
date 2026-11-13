package dev.screenguideqi.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.SurfaceViewRenderer

data class CaptureUiState(
    val active: Boolean = false,
    val status: String = "尚未开始屏幕共享",
    val error: Boolean = false,
    val starting: Boolean = false,
)

/** Process-local bridge between the foreground service and the visible preview Activity. */
object CaptureStore {
    private val mutable = MutableStateFlow(CaptureUiState())
    val state = mutable.asStateFlow()
    private var session: CaptureSession? = null
    private var attachedRenderer: SurfaceViewRenderer? = null

    @Synchronized
    fun publishStarting() { mutable.value = CaptureUiState(status = "正在启动屏幕采集…", starting = true) }

    @Synchronized
    fun publishActive(value: CaptureSession) {
        session = value
        mutable.value = CaptureUiState(active = true, status = "正在共享屏幕；系统通知栏可以停止")
        attachedRenderer?.let { attachLocked(it) }
    }

    @Synchronized
    fun publishStopped(message: String, error: Boolean = false) {
        session = null
        mutable.value = CaptureUiState(status = message, error = error)
    }

    @Synchronized
    fun activeSession(): CaptureSession? = session

    @Synchronized
    fun attach(renderer: SurfaceViewRenderer) {
        attachedRenderer?.let { detachLocked(it) }
        attachedRenderer = renderer
        attachLocked(renderer)
    }

    @Synchronized
    fun detach(renderer: SurfaceViewRenderer) {
        if (attachedRenderer === renderer) {
            detachLocked(renderer)
            attachedRenderer = null
        }
    }

    private fun attachLocked(renderer: SurfaceViewRenderer) {
        val current = session ?: return
        runCatching {
            renderer.init(current.egl.getEglBaseContext(), null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(false)
            current.videoTrack.addSink(renderer)
        }.onFailure { mutable.value = CaptureUiState(status = "本地预览启动失败，请停止后重试", error = true) }
    }

    private fun detachLocked(renderer: SurfaceViewRenderer) {
        session?.videoTrack?.removeSink(renderer)
        runCatching { renderer.clearImage() }
    }

    fun start(context: Context, resultCode: Int, resultData: Intent): Boolean {
        if (state.value.active || state.value.starting) return false
        publishStarting()
        val intent = Intent(context, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_START
            putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_RESULT_DATA, resultData)
        }
        return runCatching {
            ContextCompat.startForegroundService(context.applicationContext, intent)
            true
        }.getOrElse {
            publishStopped("无法启动前台服务，请检查系统限制", error = true)
            false
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ProjectionService::class.java))
    }
}
