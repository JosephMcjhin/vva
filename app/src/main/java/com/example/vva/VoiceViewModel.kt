package com.example.vva

import android.content.Context
import android.speech.tts.TextToSpeech
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

    private var audioPlayer: AudioPlayer? = null
    private var microphoneRecorder: MicrophoneRecorder? = null
    private var realtimeClient: OmniRealtimeClient? = null
    private var navigationBackendClient: NavigationBackendClient? = null
    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitializer = false

    @Volatile
    private var isUserSpeaking = false
    private var isExternalSpeaking = false

    private var feedImageJob: Job? = null


    val connectState = MutableStateFlow("disconnected")
    val userText = MutableStateFlow("")
    val aiText = MutableStateFlow("")

    fun initialize(context: Context, imageManager: ImageManager) {
        if (isInitializer) {
            realtimeClient?.connect()
            return
        }
        isInitializer = true
        // 初始化本地 TTS
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.CHINESE
            }
        }
        viewModelScope.launch {
            try {
                audioPlayer = AudioPlayer(context).apply { start() }
                microphoneRecorder = MicrophoneRecorder(context)
                // Initialize Navigation Backend Client
                navigationBackendClient = NavigationBackendClient(
                    backendWsUrl = context.getString(R.string.nav_backend_url),
                    onStatusChanged = { status -> Timber.tag(TAG).i("Nav status: $status") },
                    onGuidanceText = { guidance ->
                        aiText.update { "[导航] $guidance" }
                        speakTextLocally(guidance)
                    }
                )
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
                        if (intent.isStop) {
                            navigationBackendClient?.sendStopNavigationRequest()
                        } else if (intent.isNavigation) {
                            navigationBackendClient?.sendNavigationRequest(text, intent.target)
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
        microphoneRecorder?.stop()
        tts?.stop()
        tts?.shutdown()
        feedImageJob?.cancel()
        realtimeClient?.disconnect()
        navigationBackendClient?.disconnect()
    }

    private fun speakTextLocally(text: String) {
        if (text.isBlank()) return
        isExternalSpeaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guidance")
    }
}