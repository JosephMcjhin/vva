package com.example.vva

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import android.util.Base64
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the Indoor Navigation Backend.
 * Strictly separated from the LLM backend.
 *
 * 支持多个候选服务器地址：连接时依次尝试，直到某一个连上为止
 * （单地址超时 [CONNECT_TIMEOUT_SECONDS] 秒即视为失败并尝试下一个）。
 */
class NavigationBackendClient(
    private val backendWsUrls: List<String>,
    private val onStatusChanged: (String) -> Unit,
    private val onGuidanceText: (String) -> Unit,
    private val onGuidanceAudioStart: (Long) -> Unit,
    private val onGuidanceAudio: (ByteArray, Long) -> Unit,
    private val onGuidanceAudioEnd: (Long) -> Unit,
    // 蜂鸣引导：active=false 表示停止；active=true 时
    //   - freqHz 为目标频率（Hz），越接近目标频率越高（由 UE 端计算后下发）
    //   - pan 为左右平衡（-1=全左, 0=居中, +1=全右），由 UE 根据 AngleError 符号决定
    //   - volume 为总音量缩放（0..1），UE 可在接近目标时降低音量避免刺耳
    //   - intervalMs 为蜂鸣间隔（毫秒），由 UE 根据 Progress 动态下发
    //   - beepType 为蜂鸣模式："turn_calibrate"=高频警报
    private val onBeep: (active: Boolean, freqHz: Int, pan: Float, volume: Float,
                         intervalMs: Float, beepType: String) -> Unit = { _, _, _, _, _, _ -> },
    private val onDrip: (active: Boolean, intervalMs: Float) -> Unit = { _, _ -> },
    // 离散音效事件回调：soundId = "waypoint" | "deviation" | "arrival"
    private val onSound: (soundId: String) -> Unit = {},
    private val onWarning: (message: String) -> Unit = {},
    private val onImuStatus: (status: String) -> Unit = {}
) {

    private companion object {
        private const val TAG = "NavBackendClient"
        private const val CONNECT_TIMEOUT_SECONDS = 3L  // 单地址握手超时
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    /** 当前实际连上的服务器地址，便于日志/UI 展示。 */
    @Volatile
    private var connectedUrl: String? = null
    private val connectLock = Any()

    fun connectIfNeeded(): Boolean = synchronized(connectLock) {
        if (isConnected && webSocket != null) return@synchronized true
        if (!isConnected && webSocket != null) {
            try {
                webSocket?.cancel()
            } catch (_: Exception) {
            }
            webSocket = null
            connectedUrl = null
        }
        val urls = backendWsUrls.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            Timber.tag(TAG).w("Navigation backend URL list is empty")
            return@synchronized false
        }

        // 依次尝试每个候选地址；连上一个就停。
        for (url in urls) {
            if (tryConnect(url)) {
                return@synchronized true
            }
        }
        onStatusChanged("All backends unreachable")
        Timber.tag(TAG).w("All ${urls.size} candidate backends failed")
        false
    }

    /**
     * 检查当前导航后端是否真的可达。
     * WebSocket 保持连接不代表 Flask/HTTP 服务仍能正常响应，因此 UI 状态使用这个结果。
     * 未建立 WebSocket 时按候选地址顺序探测，便于连接循环继续发现后端。
     */
    fun pingCurrentBackend(): Boolean {
        val urls = connectedUrl?.let { listOf(it) }
            ?: backendWsUrls.filter { it.isNotBlank() }

        for (wsUrl in urls) {
            val healthUrl = toHealthUrl(wsUrl) ?: continue
            try {
                val request = Request.Builder().url(healthUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Timber.tag(TAG).d("Backend ping OK: %s", healthUrl)
                        return true
                    }
                    Timber.tag(TAG).w(
                        "Backend ping failed: %s (HTTP %d)",
                        healthUrl,
                        response.code
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Backend ping failed for %s: %s", healthUrl, e.message)
            }
        }
        return false
    }

    private fun toHealthUrl(wsUrl: String): String? {
        return try {
            val parsed = URI(wsUrl)
            val httpScheme = when (parsed.scheme?.lowercase()) {
                "wss" -> "https"
                "ws" -> "http"
                else -> return null
            }
            URI(
                httpScheme,
                parsed.userInfo,
                parsed.host,
                parsed.port,
                "/",
                null,
                null
            ).toString()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Invalid backend URL for ping: %s", wsUrl)
            null
        }
    }

    /**
     * 尝试连接单个地址。同步等待握手结果（最多 [CONNECT_TIMEOUT_SECONDS] 秒）。
     * 成功返回 true 并把 [webSocket]/[isConnected] 设置好；失败返回 false。
     */
    private fun tryConnect(url: String): Boolean {
        val latch = CountDownLatch(1)
        var openOk = false

        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openOk = true
                latch.countDown()
                webSocket.send(JSONObject().apply {
                    put("type", "register")
                    put("role", "glasses")
                }.toString())
                onStatusChanged("Connected")
                Timber.tag(TAG).i("Connected to Indoor Navigation Backend: %s", url)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Timber.tag(TAG).i("Closing connection: %s", reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                if (this@NavigationBackendClient.webSocket === webSocket) {
                    this@NavigationBackendClient.webSocket = null
                    connectedUrl = null
                }
                onStatusChanged("Disconnected")
                Timber.tag(TAG).i("Closed connection")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                if (this@NavigationBackendClient.webSocket === webSocket) {
                    this@NavigationBackendClient.webSocket = null
                    connectedUrl = null
                }
                latch.countDown()  // 握手失败也释放 latch，让外层去试下一个
                // 仅在握手阶段打印 error（openOk=false 说明还没建立成功）
                if (!openOk) {
                    Timber.tag(TAG).w("Connect failed for %s: %s", url, t.message)
                } else {
                    onStatusChanged("Error: ${t.message ?: "Unknown"}")
                    Timber.tag(TAG).e(t, "Connection failure")
                }
            }
        })

        // 同步等待握手结果
        val connected = try {
            latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (connected && openOk) {
            webSocket = ws
            isConnected = true
            connectedUrl = url
            return true
        }

        // 超时或失败：关掉这次失败的 socket，继续尝试下一个
        try {
            ws.cancel()
        } catch (_: Exception) {
        }
        return false
    }

    fun sendNavigationRequest(query: String, target: String?) {
        var socket = webSocket
        if (socket == null || !isConnected) {
            Timber.tag(TAG).w("Cannot send request: Not connected")
            if (!connectIfNeeded()) {
                return
            }
            socket = webSocket
            if (socket == null || !isConnected) {
                Timber.tag(TAG).w("Cannot send request after reconnect: Not connected")
                return
            }
        }

        val payload = JSONObject().apply {
            put("type", "nav_request")
            put("requestId", UUID.randomUUID().toString())
            put("query", query)
            put("target", target ?: JSONObject.NULL)
        }

        socket?.send(payload.toString())
        Timber.tag(TAG).i("Sent navigation request for target: $target")
    }

    fun disconnect() {
        webSocket?.close(1000, "User logout")
        webSocket = null
        isConnected = false
    }

    fun sendStopNavigationRequest() {
        val socket = webSocket
        if (socket == null || !isConnected) {
            Timber.tag(TAG).w("Cannot send stop request: Not connected")
            return
        }

        val payload = JSONObject().apply {
            put("type", "stop_navigation")
        }

        socket.send(payload.toString())
        Timber.tag(TAG).i("Sent stop_navigation request")
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "imu_status") {
                onImuStatus(json.optString("status", "unknown"))
                return
            }

            if (type == "warning" || type == "backend_warning") {
                val message = json.optString("text")
                if (message.isNotEmpty()) {
                    onWarning(message)
                }
                return
            }
            
            // "nav_prompt" / "status" : 纯文本提示
            if (type == "nav_prompt" || type == "navigation_prompt" || type == "status") {
                val prompt = json.optString("text")
                if (prompt.isNotEmpty()) {
                    onGuidanceText(prompt)
                }
                return
            }

            // 流式 TTS 协议：start → chunk* → end
            // chunk 到达即解码入队，AudioPlayer 边收边播，首字延迟最低。
            when (type) {
                "nav_audio_start" -> {
                    // 仅在首包时显示一次文字，避免每个 chunk 都刷 UI
                    val prompt = json.optString("text")
                    if (prompt.isNotEmpty()) {
                        onGuidanceText(prompt)
                    }
                    // 通知播放层：新句开始，清空旧缓冲区（不同句之间互相打断）
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    onGuidanceAudioStart(timestamp)
                }
                "nav_audio_chunk" -> {
                    val audioB64 = json.optString("audio")
                    if (audioB64.isNotEmpty()) {
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        try {
                            val audioData = Base64.decode(audioB64, Base64.DEFAULT)
                            // 每个分块当作一段 PCM 推给播放队列
                            onGuidanceAudio(audioData, timestamp)
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error decoding TTS audio chunk")
                        }
                    }
                }
                "nav_audio_end" -> {
                    // 通知上层：本轮 TTS 已下发完毕（可恢复被压低的其它音频）
                    val ts = json.optLong("timestamp", System.currentTimeMillis())
                    onGuidanceAudioEnd(ts)
                }
                "nav_beep" -> {
                    // 蜂鸣引导：UE 端根据剩余角度/距离计算频率后下发。
                    // pan（左右平衡，-1..1）和 volume（0..1）为可选字段，缺省时居中/满音量。
                    // beep_type: "turn_calibrate"=转向高频警报
                    val active = json.optBoolean("active", false)
                    val freqHz = json.optInt("freq_hz", 0)
                    val pan = json.optDouble("pan", 0.0).toFloat()
                    val volume = json.optDouble("volume", 1.0).toFloat()
                    val intervalMs = json.optDouble("interval_ms", 0.0).toFloat()
                    val beepType = json.optString("beep_type", "")
                    onBeep(active, freqHz, pan, volume, intervalMs, beepType)
                }
                "nav_drip" -> {
                    val active = json.optBoolean("active", false)
                    val intervalMs = json.optDouble("interval_ms", 0.0).toFloat()
                    onDrip(active, intervalMs)
                }
                "nav_sound" -> {
                    // 离散音效事件：UE 端在到达路点/偏离/到达终点时发送。
                    // sound: "waypoint" | "deviation" | "arrival"
                    val soundId = json.optString("sound", "")
                    if (soundId.isNotEmpty()) {
                        Timber.tag(TAG).i("nav_sound: %s", soundId)
                        onSound(soundId)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing message: $text")
        }
    }
}
