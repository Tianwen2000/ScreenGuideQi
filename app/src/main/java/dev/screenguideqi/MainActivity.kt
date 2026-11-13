package dev.screenguideqi

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.ContextCompat
import dev.screenguideqi.capture.CaptureStore
import dev.screenguideqi.rtc.RemoteVideoStore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import dev.screenguideqi.voice.VoiceStore
import dev.screenguideqi.rtc.RemoteVideoState
import android.view.WindowManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class MainActivity : ComponentActivity() {
    private val model: GuideViewModel get() = ViewModelProvider(this)[GuideViewModel::class.java]
    private var captureAfterNotification = false
    private var captureEpoch = -1
    private var voiceEpoch = -1
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { model.stop() }
    }
    private val voicePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (voiceEpoch == model.authorizationEpoch()) {
            if (grants.values.all { it }) model.voiceAllowed() else model.voiceDenied()
        }
    }
    private fun requestVoice() {
        voiceEpoch = model.authorizationEpoch()
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) model.voiceAllowed() else voicePermissionLauncher.launch(missing.toTypedArray())
    }
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val state = ViewModelProvider(this)[GuideViewModel::class.java].state.value
            if (state.paired && state.role == "owner" && captureEpoch == model.authorizationEpoch()) CaptureStore.start(this, result.resultCode, result.data!!)
            else CaptureStore.publishStopped("协助已结束，请重新配对并授权共享")
        } else {
            CaptureStore.publishStopped("你取消了录屏授权；屏幕没有被采集")
        }
    }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (captureAfterNotification) {
            captureAfterNotification = false
            if (granted) launchScreenCapture() else CaptureStore.publishStopped("需要允许通知，才能显示正在共享屏幕的状态", true)
        }
    }

    private fun requestScreenCapture() {
        captureEpoch = model.authorizationEpoch()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            captureAfterNotification = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureEpoch = savedInstanceState?.getInt("captureEpoch", -1) ?: -1
        voiceEpoch = savedInstanceState?.getInt("voiceEpoch", -1) ?: -1
        captureAfterNotification = savedInstanceState?.getBoolean("captureAfterNotification") ?: false
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF166A64), background = Color(0xFFF3F6F5))) {
                val model: GuideViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val capture by CaptureStore.state.collectAsStateWithLifecycle()
                val remote by RemoteVideoStore.state.collectAsStateWithLifecycle()
                val voice by VoiceStore.state.collectAsStateWithLifecycle()
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(remote.active) { if (!remote.active) fullscreen = false }
                DisposableEffect(state.busy) {
                    if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                BackHandler(state.busy || fullscreen) { if (fullscreen) fullscreen = false else model.stop() }
                Scaffold { insets ->
                    if (fullscreen && remote.active) {
                        Column(Modifier.fillMaxSize().background(Color.Black).padding(insets)) {
                            RemoteDisplay(remote, Modifier.weight(1f).fillMaxWidth())
                            Text(state.voiceStatus, color = Color.White, modifier = Modifier.padding(12.dp))
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                OutlinedButton({ fullscreen = false }) { Text("退出全屏") }
                                Button({ if (voice.active) model.stopVoice() else requestVoice() }, enabled = state.connected) {
                                    Text(if (voice.active) "关闭麦克风" else "开启语音指导")
                                }
                                OutlinedButton(model::stop) { Text("断开") }
                            }
                        }
                    } else {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .padding(insets).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("屏幕指导", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("让家人看见帮助，让每一步更清楚。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3EEEB))) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("公网直连测试版", fontWeight = FontWeight.Bold)
                                Text("共享端主动授权，指导端查看画面并发送语音指导。部分网络可能无法直连，可尝试切换网络。")
                            }
                        }
                        OutlinedTextField(state.address, model::address, Modifier.fillMaxWidth(), enabled = !state.busy,
                            label = { Text("信令服务地址") }, placeholder = { Text("wss://你的服务地址/signal") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Text("公网使用 WSS。调试版本允许局域网 WS；请勿填写密钥或协助码到地址中。", style = MaterialTheme.typography.bodySmall)
                        if (!state.busy) {
                            Button({ model.start("owner") }, Modifier.fillMaxWidth()) { Text("我是共享端 · 创建协助码", Modifier.padding(6.dp)) }
                            HorizontalDivider()
                            OutlinedTextField(state.codeInput, model::code, Modifier.fillMaxWidth(), label = { Text("对方的八位协助码") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedButton({ model.start("viewer") }, Modifier.fillMaxWidth()) { Text("我是指导端 · 申请加入", Modifier.padding(6.dp)) }
                        }
                        if (state.code.isNotEmpty()) {
                            Text("把协助码告诉你信任的人", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Text(state.code.chunked(4).joinToString("  "), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                        }
                        if (state.role == "owner" && state.paired && !capture.active) {
                            Button({ requestScreenCapture() }, Modifier.fillMaxWidth(), enabled = !capture.starting) {
                                Text("开始共享屏幕（需要系统授权）", Modifier.padding(6.dp))
                            }
                        }
                        if (capture.active) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3EEEB))) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("屏幕采集已启动", fontWeight = FontWeight.Bold)
                                    Text("请切换到需要指导的页面，由对方确认是否看到了持续变化的画面。")
                                    Text("系统通知栏显示“正在共享屏幕”，即可确认采集仍在运行。", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (state.role == "viewer" && remote.active) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("对方屏幕", color = Color.White, fontWeight = FontWeight.Bold)
                                    RemoteDisplay(remote, Modifier.fillMaxWidth().height(360.dp))
                                }
                            }
                            Button({ fullscreen = true }, Modifier.fillMaxWidth()) { Text("全屏查看") }
                        }
                        if (state.paired) {
                            Text(state.voiceStatus)
                            if (state.role == "viewer") {
                                Button({ if (voice.active) model.stopVoice() else requestVoice() }, Modifier.fillMaxWidth(), enabled = state.connected) {
                                    Text(if (voice.active) "关闭麦克风" else "开启语音指导")
                                }
                                Text("只发送你的麦克风声音；可在通知栏随时关闭。", style = MaterialTheme.typography.bodySmall)
                            } else Text("这里只播放指导端的声音，不采集你的麦克风。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (capture.status != "尚未开始屏幕共享") {
                            Text(capture.status, color = if (capture.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(state.status, style = MaterialTheme.typography.bodyLarge)
                        if (state.rtcDiagnostic.isNotEmpty()) {
                            Text(state.rtcDiagnostic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.audioDiagnostic.isNotEmpty()) Text(state.audioDiagnostic, style = MaterialTheme.typography.bodySmall)
                        if (state.joinRequestId != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button({ model.decide(true) }) { Text("接受加入") }
                                OutlinedButton({ model.decide(false) }) { Text("拒绝") }
                            }
                        }
                        if (capture.active) OutlinedButton({ CaptureStore.stop(this@MainActivity) }, Modifier.fillMaxWidth()) { Text("停止屏幕共享") }
                        if (state.busy) OutlinedButton(model::stop, Modifier.fillMaxWidth()) { Text("断开并结束本次协助") }
                        HorizontalDivider()
                        Text("网络检查", style = MaterialTheme.typography.titleLarge)
                        Text("不采集任何内容，不使用 TURN 中继。获得候选地址并不代表公网连接一定成功。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(model::probe, enabled = !state.probeBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.probeBusy) "正在检测…" else "检测 WebRTC / STUN")
                        }
                        Text(state.probeStatus, style = MaterialTheme.typography.bodyMedium)
                        Text("自用测试版 ${BuildConfig.VERSION_NAME} · 两端安装相同 APK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                }
            }
        }
    }
    override fun onDestroy() {
        unregisterReceiver(screenOff)
        if (isFinishing) model.stop()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("captureEpoch", captureEpoch)
        outState.putInt("voiceEpoch", voiceEpoch)
        outState.putBoolean("captureAfterNotification", captureAfterNotification)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun RemoteDisplay(remote: RemoteVideoState, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = remote.width.toFloat() / remote.height
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        AndroidView(
            factory = { context -> SurfaceViewRenderer(context).also { RemoteVideoStore.attach(it) } },
            modifier = Modifier.size(width, height),
            onRelease = { RemoteVideoStore.detach(it) },
        )
    }
}
