package com.example.vva

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import android.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the Indoor Navigation Backend.
 * Strictly separated from the LLM backend.
 */
class NavigationBackendClient(
    private val backendWsUrl: String,
    private val onStatusChanged: (String) -> Unit,
    private val onGuidanceText: (String) -> Unit,
    private val onGuidanceAudio: (ByteArray, Long) -> Unit
) {

    private companion object {
        private const val TAG = "NavBackendClient"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    fun connectIfNeeded() {
        if (isConnected || webSocket != null) return
        if (backendWsUrl.isBlank()) {
            Timber.tag(TAG).w("Navigation backend URL is empty")
            return
        }

        val request = Request.Builder().url(backendWsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                webSocket.send(JSONObject().apply {
                    put("type", "register")
                    put("role", "glasses")
                }.toString())
                onStatusChanged("Connected")
                Timber.tag(TAG).i("Connected to Indoor Navigation Backend")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Timber.tag(TAG).i("Closing connection: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                this@NavigationBackendClient.webSocket = null
                onStatusChanged("Disconnected")
                Timber.tag(TAG).i("Closed connection")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                this@NavigationBackendClient.webSocket = null
                onStatusChanged("Error: ${t.message ?: "Unknown"}")
                Timber.tag(TAG).e(t, "Connection failure")
            }
        })
    }

    fun sendNavigationRequest(query: String, target: String?) {
        val socket = webSocket
        if (socket == null || !isConnected) {
            Timber.tag(TAG).w("Cannot send request: Not connected")
            connectIfNeeded()
            return
        }

        val payload = JSONObject().apply {
            put("type", "nav_request")
            put("requestId", UUID.randomUUID().toString())
            put("query", query)
            put("target", target ?: JSONObject.NULL)
        }

        socket.send(payload.toString())
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
            
            // "nav_prompt" is the standard type for guidance text from the server
            if (type == "nav_prompt" || type == "navigation_prompt" || type == "status") {
                val prompt = json.optString("text")
                if (prompt.isNotEmpty()) {
                    onGuidanceText(prompt)
                }
            } else if (type == "nav_audio") {
                val prompt = json.optString("text")
                val audioB64 = json.optString("audio")
                if (prompt.isNotEmpty()) {
                    onGuidanceText(prompt)
                }
                if (audioB64.isNotEmpty()) {
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    try {
                        val audioData = Base64.decode(audioB64, Base64.DEFAULT)
                        onGuidanceAudio(audioData, timestamp)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error decoding TTS audio")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing message: $text")
        }
    }
}
