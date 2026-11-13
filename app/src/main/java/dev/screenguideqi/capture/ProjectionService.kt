package dev.screenguideqi.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.VideoSource
import org.webrtc.audio.JavaAudioDeviceModule
import dev.screenguideqi.rtc.AudioProbe
import java.util.concurrent.atomic.AtomicBoolean

class ProjectionService : Service() {
    private val stopped = AtomicBoolean(false)
    private var current: CaptureSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finish("锁屏后已停止共享，请重新授权") }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish("已停止屏幕共享")
            ACTION_START -> startProjection(intent)
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        if (current != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            finish("录屏授权无效，请重新发起共享", true)
            return
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            val callback = object : MediaProjection.Callback() {
                override fun onStop() { finish("系统已停止屏幕共享", true) }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    mainHandler.post { resizeCapture(width, height) }
                }
            }
            // ScreenCapturerAndroid alone consumes the one-shot permission result.
            val session = createSession(resultData, callback)
            current = session
            session.capturer.initialize(session.textureHelper, applicationContext, session.videoSource.capturerObserver)
            val bounds = captureBounds()
            session.capturer.startCapture(bounds.width(), bounds.height(), 15)
            session.videoSource.adaptOutputFormat(bounds.width(), bounds.height(), 15)
            CaptureStore.publishActive(session)
        } catch (security: SecurityException) {
            finish("录屏授权已失效，请重新授权", true)
        } catch (error: Exception) {
            finish("屏幕采集启动失败，请重试", true)
        }
    }

    private fun createSession(resultData: Intent, callback: MediaProjection.Callback): CaptureSession {
        ensureWebRtcInitialized()
        val egl = EglBase.create()
        var factory: PeerConnectionFactory? = null
        var helper: SurfaceTextureHelper? = null
        var source: VideoSource? = null
        try {
            val audio = AudioProbe.configure(JavaAudioDeviceModule.builder(applicationContext), applicationContext).createAudioDeviceModule()
            try { factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audio)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory() } finally { audio.release() }
            helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext)
                ?: throw IllegalStateException("Capture texture unavailable")
            source = factory!!.createVideoSource(true)
            source.setIsScreencast(true)
            val track = factory.createVideoTrack("screen-track", source)
            return CaptureSession(ScreenCapturerAndroid(resultData, callback), helper, source, track, egl, factory)
        } catch (error: Exception) {
            runCatching { source?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { helper?.dispose() }
            runCatching { egl.release() }
            throw error
        }
    }

    private fun captureBounds(): Rect {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.maximumWindowMetrics.bounds else {
            val metrics = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android 14+ reports the actual selected content size, including one-app sharing.
        if (Build.VERSION.SDK_INT < 34) {
            val bounds = captureBounds()
            resizeCapture(bounds.width(), bounds.height())
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        if (stopped.get() || width <= 0 || height <= 0) return
        val session = current ?: return
        try {
            session.capturer.changeCaptureFormat(width, height, 15)
            session.videoSource.adaptOutputFormat(width, height, 15)
        } catch (_: Exception) { finish("屏幕尺寸调整失败，请重新授权共享", true) }
    }

    private fun ensureWebRtcInitialized() {
        synchronized(WEBRTC_LOCK) {
            if (!webrtcInitialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
                webrtcInitialized = true
            }
        }
    }

    private fun finish(message: String, error: Boolean = false) {
        // MediaProjection callbacks run on the capture thread. Release on main to
        // avoid disposing SurfaceTextureHelper from its own callback thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finish(message, error) }
            return
        }
        if (!stopped.compareAndSet(false, true)) return
        current?.release(); current = null
        CaptureStore.publishStopped(message, error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(dev.screenguideqi.R.drawable.ic_launcher)
        .setContentTitle("正在共享屏幕")
        .setContentText("屏幕共享进行中，点击通知可返回应用停止共享")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 1003, Intent(this, dev.screenguideqi.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(
            NotificationCompat.Action.Builder(
                dev.screenguideqi.R.drawable.ic_launcher,
                "停止共享",
                PendingIntent.getService(
                    this, 1002, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )
        .build()

    private fun createNotificationChannel() {
        val channels = getSystemService(NotificationManager::class.java)
        channels.createNotificationChannel(NotificationChannel(CHANNEL_ID, "屏幕共享", NotificationManager.IMPORTANCE_LOW).apply {
            description = "屏幕共享进行期间显示状态"
        })
    }

    override fun onDestroy() { unregisterReceiver(screenOff); finish("已停止屏幕共享"); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { finish("应用已退出，屏幕共享已结束") }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun Intent.parcelableIntent(key: String): Intent? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)

    companion object {
        const val ACTION_START = "dev.screenguideqi.capture.START"
        const val ACTION_STOP = "dev.screenguideqi.capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1001
        private val WEBRTC_LOCK = Any()
        private var webrtcInitialized = false
    }
}
