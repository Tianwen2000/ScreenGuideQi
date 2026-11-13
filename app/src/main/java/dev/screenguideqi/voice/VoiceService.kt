package dev.screenguideqi.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.screenguideqi.MainActivity
import dev.screenguideqi.R

class VoiceService : Service() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finishVoice() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("voice", "语音指导", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finishVoice(); return START_NOT_STICKY }
        try {
            val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 22, Intent(this, VoiceService::class.java).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "voice").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("正在发送语音指导").setContentText("麦克风已开启，点击可返回应用")
                .setContentIntent(open).setOngoing(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_launcher, "关闭麦克风", stop).build()
            ServiceCompat.startForeground(this, 1002, notification,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            VoiceStore.active()
        } catch (_: Exception) { VoiceStore.stopped("无法启用麦克风，请允许麦克风与通知权限"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun finishVoice() { VoiceStore.stopped(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { unregisterReceiver(screenOff); VoiceStore.stopped(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { VoiceStore.taskRemoved(); finishVoice() }
    override fun onBind(intent: Intent?): IBinder? = null
}
