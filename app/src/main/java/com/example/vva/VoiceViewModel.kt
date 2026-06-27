package com.example.vva

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class VoiceViewModel : ViewModel() {

    private companion object {
        private const val TAG = "VoiceViewModel"

        private const val IMAGE_POLLING_INTERVAL = 500L // 轮询间隔
    }

    private var audioPlayer: AudioPlayer? = null       // LLM 对话音频（OmniRealtime）
    private var navAudioPlayer: AudioPlayer? = null    // 导航 TTS 音频，独立播放，可与 LLM 同时出声
    private var microphoneRecorder: MicrophoneRecorder? = null
    private var realtimeClient: OmniRealtimeClient? = null
    private var navigationBackendClient: NavigationBackendClient? = null

    @Volatile
    private var isInitializer = false

    @Volatile
    private var isUserSpeaking = false
    private var isExternalSpeaking = false
    private var lastNavAudioTimestamp = 0L

    private var feedImageJob: Job? = null


    val connectState = MutableStateFlow("disconnected")
    val userText = MutableStateFlow("")
    val aiText = MutableStateFlow("")

    fun initialize(context: Context, imageManager: ImageManager) {
        if (isInitializer) {
            navigationBackendClient?.connectIfNeeded()
            realtimeClient?.connect()
            return
        }
        isInitializer = true
        
        viewModelScope.launch {
            try {
                audioPlayer = AudioPlayer(context).apply { start() }
                navAudioPlayer = AudioPlayer(context).apply {
                    start()
                    // 导航 TTS 真正播完后（队列耗尽），恢复 LLM 对话音量
                    onQueueDrained = {
                        audioPlayer?.unduck()
                    }
                }
                microphoneRecorder = MicrophoneRecorder(context)
                // Initialize Navigation Backend Client
                navigationBackendClient = NavigationBackendClient(
                    backendWsUrls = listOf(
                        context.getString(R.string.nav_backend_url_1),
                        context.getString(R.string.nav_backend_url_2),
                        context.getString(R.string.nav_backend_url_3),
                    ),
                    onStatusChanged = { status -> Timber.tag(TAG).i("Nav status: $status") },
                    onGuidanceText = { guidance ->
                        aiText.update { "[导航] $guidance" }
                        // speakTextLocally(guidance) // 禁用本地 TTS，改用服务器下发的音频
                    },
                    onGuidanceAudioStart = { timestamp ->
                        if (timestamp >= lastNavAudioTimestamp) {
                            lastNavAudioTimestamp = timestamp
                            // 新句开始：只清导航自身的队列，不影响 LLM 对话音频
                            navAudioPlayer?.prepareForNextTurn()
                            // ducking：导航要说话了，把 LLM 对话音量压低，避免互相盖住
                            audioPlayer?.duck(0.6f)
                        }
                    },
                    onGuidanceAudio = { audioData, timestamp ->
                        if (timestamp >= lastNavAudioTimestamp) {
                            // 流式 chunk：顺序入队，AudioTrack 边收边播（首字延迟低）
                            navAudioPlayer?.addAudioData(audioData)
                        } else {
                            Timber.tag(TAG).w("Discarding outdated nav audio chunk (ts: $timestamp, last: $lastNavAudioTimestamp)")
                        }
                    },
                    onGuidanceAudioEnd = { timestamp ->
                        // 无需在此 unduck；navAudioPlayer 的 onQueueDrained 会在
                        // 导航音频真正播完后自动恢复 LLM 音量（更准确）。
                    }
                )
                navigationBackendClient?.connectIfNeeded()
                realtimeClient = OmniRealtimeClient(
                    apiKey = context.getString(R.string.api_key),
                    onConnected = {
                        Timber.tag(TAG).i("onInit")
                        startRecording()
                        //startCamera(imageManager)
                        connectState.update { "connected" }
                    },
                    onDisconnected = {
                        Timber.tag(TAG).i("onDisconnected")
                        connectState.update { "disconnected" }
                    },
                    onResponseText = { text ->
                        if (aiText.value.startsWith("[导航]")) {
                            aiText.update { text }
                        } else {
                            aiText.update { it + text }
                        }
                        Timber.tag(TAG).d("onResponseText: ${aiText.value}")
                    },
                    onResponseAudio = { audioData ->
                        if (!isUserSpeaking) {
                            audioPlayer?.addAudioData(audioData)
                        }
                    },
                    onResponseDone = {
                        Timber.tag(TAG).i("onResponseDone")
                        isExternalSpeaking = false
                        realtimeClient?.clearAudioBuffer()
                    },
                    onAsrResult = { text ->
                        userText.update { text }
                        Timber.tag(TAG).i("onAsrResult: $text")
                        val intent = NavigationKeywordMatcher.match(text)
                        when {
                            intent.isStopSpeaking -> {
                                // 立即静音所有播放（LLM 对话 + 导航 TTS），恢复音量
                                Timber.tag(TAG).i("Stop-speaking command: silencing all playback")
                                realtimeClient?.clearAudioBuffer()
                                audioPlayer?.prepareForNextTurn()
                                audioPlayer?.unduck()
                                navAudioPlayer?.prepareForNextTurn()
                            }
                            intent.isStop -> {
                                navigationBackendClient?.sendStopNavigationRequest()
                            }
                            intent.isNavigation -> {
                                navigationBackendClient?.sendNavigationRequest(text, intent.target)
                            }
                        }
                    },
                    onVadBegin = {
                        Timber.tag(TAG).i("onVadBegin")
                        isUserSpeaking = true
                        if (!isExternalSpeaking) {
                            userText.update { "" }
                            aiText.update { "" }
                            // 停止播放AI当前的回复，允许用户打断AI
                            realtimeClient?.clearAudioBuffer()
                            audioPlayer?.prepareForNextTurn()
                        }

                        // 立即发送图片
                        sendImage(imageManager)
                    },
                    onVadEnd = {
                        Timber.tag(TAG).i("onVadEnd")
                        isUserSpeaking = false
                    }
                )

                realtimeClient?.connect()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
            }
        }
    }

    private fun startRecording() {
        viewModelScope.launch {
            try {
                microphoneRecorder?.setRecorderProcessor {
                    realtimeClient?.sendAudio(it)
                }
                microphoneRecorder?.start()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
            }
        }
    }

    private fun startCamera(imageManager: ImageManager) {
        if (feedImageJob?.isActive == true) {
            Timber.tag(TAG).i("startCamera already running")
            feedImageJob?.cancel("startCamera")
        }
        feedImageJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (isUserSpeaking) {
                    delay(IMAGE_POLLING_INTERVAL)
                    sendImage(imageManager)
                } else {
                    delay(100)
                }
            }
        }
    }

    private fun sendImage(imageManager: ImageManager) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val image = imageManager.takeJpeg()
                if (image.isNotEmpty()) {
                    realtimeClient?.sendImage(image)
                } else {
                    Timber.tag(TAG).i("No image")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer?.stop()
        navAudioPlayer?.stop()
        microphoneRecorder?.stop()
        feedImageJob?.cancel()
        realtimeClient?.disconnect()
        navigationBackendClient?.disconnect()
    }

    private fun speakTextLocally(text: String) {
        // 废弃本地 TTS，仅保留日志用于调试流程
        Timber.tag(TAG).d("Local TTS (Disabled): $text")
    }
}
