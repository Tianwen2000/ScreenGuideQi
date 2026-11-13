package dev.screenguideqi.signaling

import dev.screenguideqi.BuildConfig
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

class SignalingClient(
    private val onOpen: () -> Unit,
    private val onMessage: (JSONObject) -> Unit,
    private val onEnded: (String) -> Unit,
) {
    private val ended = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Bounds TLS/HTTP upgrade; OkHttp removes the socket read timeout after WebSocket upgrade.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastPong = System.nanoTime()

    fun connect(address: String) {
        validateAddress(address)
        socket = http.newWebSocket(Request.Builder().url(address).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (BuildConfig.DEBUG) Log.d("SGQI.Signal", "opened")
                if (ended.get()) webSocket.close(1000, "cancelled") else {
                    lastPong = System.nanoTime()
                    webSocket.send("ping")
                    heartbeat.scheduleWithFixedDelay({
                        if (!ended.get()) {
                            if (System.nanoTime() - lastPong > TimeUnit.SECONDS.toNanos(45)) fail("网络连接已中断，请重新连接")
                            else {
                                if (BuildConfig.DEBUG) Log.d("SGQI.Signal", "heartbeat ping")
                                webSocket.send("ping")
                            }
                        }
                    }, 10, 10, TimeUnit.SECONDS)
                    onOpen()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get()) return
                if (text == "pong") {
                    lastPong = System.nanoTime()
                    if (BuildConfig.DEBUG) Log.d("SGQI.Signal", "heartbeat pong")
                    return
                }
                if (text.length > 65_536) { fail("服务器消息过大，已断开"); return }
                try {
                    val message = JSONObject(text)
                    require(message.getInt("v") == 1)
                    if (BuildConfig.DEBUG) Log.d("SGQI.Signal", "received " + message.optString("type").take(32))
                    onMessage(message)
                } catch (_: Exception) { fail("服务器协议不兼容，已断开") }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail("连接已结束")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(if (response != null) "服务连接失败（HTTP ${response.code}）" else "无法连接服务，请检查地址和网络")
            }
        })
    }

    fun send(type: String, roomId: String? = null, sessionId: String? = null, payload: JSONObject = JSONObject()): Boolean {
        if (ended.get()) return false
        if (BuildConfig.DEBUG) Log.d("SGQI.Signal", "sending $type")
        val message = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString())
            .put("type", type).put("payload", payload)
        if (roomId != null) message.put("roomId", roomId)
        if (sessionId != null) message.put("sessionId", sessionId)
        return socket?.send(message.toString()) == true
    }

    private fun fail(message: String) {
        if (ended.compareAndSet(false, true)) {
            heartbeat.shutdownNow()
            socket?.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            onEnded(message)
        }
    }
    fun close() {
        if (ended.compareAndSet(false, true)) {
            heartbeat.shutdownNow()
            socket?.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        fun validateAddress(address: String) {
            val secure = address.startsWith("wss://")
            val local = address.startsWith("ws://")
            val parsed = address.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrlOrNull()
            require(parsed != null && parsed.encodedPath == "/signal" && parsed.encodedQuery == null && parsed.fragment == null && parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "请填写以 /signal 结尾的完整 WebSocket 地址"
            }
            val host = parsed.host
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            val ipv4 = octets.size == 4 && octets.all { it in 0..255 }
            val privateHost = host == "localhost" || host == "::1" || (ipv4 && (
                octets[0] == 127 || octets[0] == 10 ||
                    (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31)))
            require(secure || (BuildConfig.DEBUG && local && privateHost)) { "公网连接必须使用 wss://；调试版仅允许局域网使用 ws://" }
        }
    }
}
