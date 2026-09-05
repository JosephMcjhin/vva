package com.example.vva

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
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

    private enum class LlmResponseGate {
        PendingDecision,
        Allowed,
        Suppressed
    }

    private companion object {
        private const val TAG = "VoiceViewModel"

        private const val IMAGE_POLLING_INTERVAL = 500L // 轮询间隔
        private const val NAV_BACKEND_PING_INTERVAL = 3_000L
        private const val DASHSCOPE_RECONNECT_INTERVAL = 5_000L
    }

    private var audioPlayer: AudioPlayer? = null       // LLM 对话音频（OmniRealtime）
    private var navAudioPlayer: AudioPlayer? = null    // 导航 TTS 音频，独立播放，可与 LLM 同时出声
    private var microphoneRecorder: MicrophoneRecorder? = null
    private var realtimeClient: OmniRealtimeClient? = null
    private var navigationBackendClient: NavigationBackendClient? = null
    private var beepPlayer: BeepPlayer? = null
    private var dripPlayer: DripPlayer? = null
    private var soundPool: SoundPool? = null
    @Volatile private var activeImageManager: ImageManager? = null
    private val soundResMap = mutableMapOf<String, Int>()  // soundId → resourceId
    private val soundLoadedIds = mutableSetOf<String>()
    private val pendingSoundIds = mutableListOf<String>()

    @Volatile
    private var isInitializer = false

    @Volatile
    private var isUserSpeaking = false
    private var isExternalSpeaking = false
    @Volatile
    private var llmResponseGate = LlmResponseGate.Suppressed
    private val llmGateLock = Any()
    private val pendingLlmText = StringBuilder()
    private val pendingLlmAudioChunks = mutableListOf<ByteArray>()
    private var lastNavAudioTimestamp = 0L

    private var feedImageJob: Job? = null
    private var navConnectJob: Job? = null
    private var realtimeConnectJob: Job? = null


    val connectState = MutableStateFlow("disconnected")
    val dashscopeState = MutableStateFlow("disconnected")
    val dashscopeError = MutableStateFlow("")
    val imuState = MutableStateFlow("waiting")
    val userText = MutableStateFlow("")
    val aiText = MutableStateFlow("")
    val beepInfo = MutableStateFlow("")  // 蜂鸣调试信息

    fun initialize(context: Context, imageManager: ImageManager?) {
        activeImageManager = imageManager
        if (isInitializer) {
            connectNavigationBackendAsync()
            connectRealtimeAsync()
            return
        }
        isInitializer = true
        
        viewModelScope.launch {
            try {
                audioPlayer = AudioPlayer(context).apply {
                    onQueueDrained = {
                        isExternalSpeaking = false
                    }
                    start()
                }
                navAudioPlayer = AudioPlayer(context).apply {
                    start()
                    // 导航 TTS 真正播完后（队列耗尽），恢复 LLM 对话音量
                    onQueueDrained = {
                        audioPlayer?.unduck()
                    }
                }
                microphoneRecorder = MicrophoneRecorder(context)
                beepPlayer = BeepPlayer()
                dripPlayer = DripPlayer {
                    playNavigationSound("drip")
                }
                // 初始化 SoundPool 用于导航音效（到达路点/偏离/到达终点）
                initSoundPool(context)
                // Initialize Navigation Backend Client
                navigationBackendClient = NavigationBackendClient(
                    backendWsUrls = context.resources.getStringArray(R.array.nav_backend_urls).toList(),
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
                    },
                    onBeep = { active, freqHz, pan, volume, intervalMs, beepType ->
                        if (active && beepType != "turn_calibrate") {
                            beepPlayer?.stop()
                            beepInfo.update { "Beep: OFF" }
                        } else {
                            beepPlayer?.setActive(active, freqHz, pan, volume, intervalMs, beepType)
                            beepInfo.update {
                                if (active) {
                                    val panStr = if (pan > 0.1f) "R" else if (pan < -0.1f) "L" else "C"
                                    "Beep: ${freqHz}Hz $panStr"
                                } else "Beep: OFF"
                            }
                        }
                    },
                    onDrip = { active, intervalMs ->
                        if (active) {
                            beepPlayer?.stop()
                        }
                        dripPlayer?.setActive(active, intervalMs)
                        beepInfo.update {
                            if (active) "Drip: ${intervalMs.toInt()}ms" else "Drip: OFF"
                        }
                    },
                    onSound = { soundId ->
                        playDiscreteNavigationSound(soundId)
                    },
                    onWarning = { warning ->
                        Timber.tag(TAG).w("Backend warning: $warning")
                        imuState.update { warning }
                    },
                    onImuStatus = { status ->
                        Timber.tag(TAG).i("IMU status: $status")
                        imuState.update { status }
                    }
                )
                connectNavigationBackendAsync()
                realtimeClient = OmniRealtimeClient(
                    apiKey = context.getString(R.string.api_key),
                    onConnected = {
                        Timber.tag(TAG).i("onInit")
                        dashscopeState.update { "connected" }
                        dashscopeError.update { "" }
                        startRecording()
                        //startCamera(imageManager)
                    },
                    onDisconnected = { reason ->
                        Timber.tag(TAG).i("onDisconnected: $reason")
                        dashscopeState.update { "disconnected" }
                        if (dashscopeError.value.isBlank()) {
                            dashscopeError.update { reason }
                        }
                    },
                    onResponseText = { text ->
                        handleLlmTextDelta(text)
                    },
                    onResponseAudio = { audioData ->
                        handleLlmAudioDelta(audioData)
                    },
                    onResponseDone = {
                        Timber.tag(TAG).i("onResponseDone")
                        // ASR 完成事件可能晚于 response.done，保留待判定内容给 onAsrResult 决定。
                        val hasPendingDecision = synchronized(llmGateLock) {
                            llmResponseGate == LlmResponseGate.PendingDecision
                        }
                        if (!hasPendingDecision) {
                            suppressCurrentLlmResponse()
                        }
                        realtimeClient?.clearAudioBuffer()
                    },
                    onAsrResult = { text ->
                        userText.update { text }
                        Timber.tag(TAG).i("onAsrResult: $text")
                        val intent = NavigationKeywordMatcher.match(text)
                        val keepCurrentLlmSpeaking = isExternalSpeaking && !intent.isStopSpeaking
                        when {
                            intent.isStopSpeaking -> {
                                suppressCurrentLlmResponse()
                                isExternalSpeaking = false
                                // 立即静音所有播放（LLM 对话 + 导航 TTS），恢复音量
                                Timber.tag(TAG).i("Stop-speaking command: silencing all playback")
                                realtimeClient?.clearAudioBuffer()
                                audioPlayer?.prepareForNextTurn()
                                audioPlayer?.unduck()
                                navAudioPlayer?.prepareForNextTurn()
                            }
                            intent.isStop -> {
                                if (!keepCurrentLlmSpeaking) {
                                    suppressCurrentLlmResponse()
                                }
                                viewModelScope.launch(Dispatchers.IO) {
                                    navigationBackendClient?.sendStopNavigationRequest()
                                }
                            }
                            intent.isNavigation -> {
                                if (!keepCurrentLlmSpeaking) {
                                    suppressCurrentLlmResponse()
                                }
                                viewModelScope.launch(Dispatchers.IO) {
                                    navigationBackendClient?.sendNavigationRequest(text, intent.target)
                                }
                            }
                            shouldAllowLlmForUserText(text) -> {
                                if (!keepCurrentLlmSpeaking) {
                                    allowCurrentLlmResponse()
                                }
                            }
                            else -> {
                                if (keepCurrentLlmSpeaking) {
                                    Timber.tag(TAG).d("Keeping current LLM speech while user talks: %s", text)
                                } else {
                                    suppressCurrentLlmResponse()
                                }
                            }
                        }
                    },
                    onVadBegin = {
                        Timber.tag(TAG).i("onVadBegin")
                        isUserSpeaking = true
                        if (!isExternalSpeaking) {
                            beginPendingLlmResponse()
                        }
                        userText.update { "" }

                        // 立即发送图片
                        activeImageManager?.let { sendImage(it) }
                    },
                    onVadEnd = {
                        Timber.tag(TAG).i("onVadEnd")
                        isUserSpeaking = false
                    },
                    onError = { error ->
                        Timber.tag(TAG).e("DashScope error: $error")
                        dashscopeState.update { "error" }
                        dashscopeError.update { error.take(180) }
                    }
                )

                connectRealtimeAsync()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
                dashscopeState.update { "error" }
                dashscopeError.update {
                    "初始化失败：${e.message ?: e.javaClass.simpleName}".take(180)
                }
            }
        }
    }

    fun setImageManager(imageManager: ImageManager?) {
        activeImageManager = imageManager
    }

    fun setBeepChannelSwap(swapped: Boolean) {
        beepPlayer?.setChannelSwap(swapped)
    }

    private fun connectNavigationBackendAsync() {
        if (navConnectJob?.isActive == true) return
        navConnectJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = navigationBackendClient
                if (client == null) {
                    connectState.update { "disconnected" }
                    break
                }

                // 连接负责恢复 WebSocket，状态只认 HTTP ping 的结果。
                client.connectIfNeeded()
                val reachable = client.pingCurrentBackend()
                connectState.update { if (reachable) "connected" else "disconnected" }
                delay(NAV_BACKEND_PING_INTERVAL)
            }
        }
    }

    private fun connectRealtimeAsync() {
        if (realtimeConnectJob?.isActive == true) return
        dashscopeState.update { "connecting" }
        realtimeConnectJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = realtimeClient
                if (client == null) {
                    dashscopeState.update { "error: client not initialized" }
                    dashscopeError.update { "client not initialized" }
                    delay(DASHSCOPE_RECONNECT_INTERVAL)
                    continue
                }

                if (!client.isConnected()) {
                    dashscopeState.update { "connecting" }
                    try {
                        client.connect()
                    } catch (e: Exception) {
                        dashscopeState.update { "error" }
                        dashscopeError.update {
                            (e.message ?: e.javaClass.simpleName).take(180)
                        }
                        Timber.tag(TAG).e(e, "DashScope connection failed")
                    }
                }

                delay(DASHSCOPE_RECONNECT_INTERVAL)
            }
        }
    }

    private fun beginPendingLlmResponse() {
        synchronized(llmGateLock) {
            llmResponseGate = LlmResponseGate.PendingDecision
            pendingLlmText.clear()
            pendingLlmAudioChunks.clear()
        }
    }

    private fun allowCurrentLlmResponse() {
        val textToFlush: String
        val audioToFlush: List<ByteArray>
        synchronized(llmGateLock) {
            llmResponseGate = LlmResponseGate.Allowed
            textToFlush = pendingLlmText.toString()
            audioToFlush = pendingLlmAudioChunks.toList()
            pendingLlmText.clear()
            pendingLlmAudioChunks.clear()
        }

        if (textToFlush.isNotEmpty()) {
            appendAiText(textToFlush)
        }
        if (audioToFlush.isNotEmpty()) {
            isExternalSpeaking = true
            audioToFlush.forEach { audioPlayer?.addAudioData(it) }
        }
    }

    private fun suppressCurrentLlmResponse() {
        synchronized(llmGateLock) {
            llmResponseGate = LlmResponseGate.Suppressed
            pendingLlmText.clear()
            pendingLlmAudioChunks.clear()
        }
    }

    private fun handleLlmTextDelta(text: String) {
        val shouldAppend = synchronized(llmGateLock) {
            when (llmResponseGate) {
                LlmResponseGate.PendingDecision -> {
                    pendingLlmText.append(text)
                    false
                }
                LlmResponseGate.Allowed -> true
                LlmResponseGate.Suppressed -> false
            }
        }

        if (shouldAppend) {
            appendAiText(text)
        }
    }

    private fun handleLlmAudioDelta(audioData: ByteArray) {
        val shouldPlay = synchronized(llmGateLock) {
            when (llmResponseGate) {
                LlmResponseGate.PendingDecision -> {
                    pendingLlmAudioChunks.add(audioData)
                    false
                }
                LlmResponseGate.Allowed -> true
                LlmResponseGate.Suppressed -> false
            }
        }

        if (shouldPlay) {
            isExternalSpeaking = true
            audioPlayer?.addAudioData(audioData)
        }
    }

    private fun appendAiText(text: String) {
        if (aiText.value.startsWith("[导航]")) {
            aiText.update { text }
        } else {
            aiText.update { it + text }
        }
        Timber.tag(TAG).d("onResponseText: ${aiText.value}")
    }

    private fun shouldAllowLlmForUserText(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false

        val descriptionTriggers = listOf(
            "描述",
            "帮我描述",
            "分析一下当前",
            "分析当前",
            "分析场景",
            "当前场景",
            "看看周围",
            "看一下周围",
            "看下周围",
            "看看前面",
            "看一下前面",
            "看下前面"
        )

        return descriptionTriggers.any { normalized.contains(it) }
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

    /**
     * 初始化 SoundPool，加载导航音效资源。
     * 音效文件放在 res/raw/ 目录下：
     *   - raw/nav_waypoint.ogg  → "waypoint"  到达路点柔和水声
     *   - raw/nav_deviation.ogg → "deviation" 偏离错误警报
     *   - raw/nav_arrival.ogg   → "arrival"   到达终点
     * 若资源文件不存在，会静默跳过（不崩溃），此时无音效播放。
     */
    private fun initSoundPool(context: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        soundPool = pool
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            val navSoundId = soundResMap.entries.firstOrNull { it.value == sampleId }?.key
                ?: return@setOnLoadCompleteListener
            if (status == 0) {
                soundLoadedIds.add(navSoundId)
                var hadPendingPlay = false
                while (pendingSoundIds.remove(navSoundId)) {
                    hadPendingPlay = true
                }
                Timber.tag(TAG).i("Loaded nav sound: %s", navSoundId)
                if (hadPendingPlay) {
                    playNavigationSound(navSoundId)
                }
            } else {
                soundResMap.remove(navSoundId)
                Timber.tag(TAG).w("Failed to load nav sound: %s status=%d", navSoundId, status)
            }
        }

        // 尝试加载音效资源（不存在则跳过）
        val resMap = mapOf(
            "drip" to R.raw.nav_drip,
            "waypoint" to R.raw.nav_waypoint,
            "deviation" to R.raw.nav_deviation,
            "arrival" to R.raw.nav_arrival
        )
        for ((id, resId) in resMap) {
            try {
                val soundId = pool.load(context, resId, 1)
                if (soundId != 0) {
                    soundResMap[id] = soundId
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Sound resource not found for '$id': %s", e.message)
            }
        }
    }

    private fun playDiscreteNavigationSound(soundId: String) {
        if (soundId == "drip") {
            Timber.tag(TAG).i("Ignoring discrete drip sound; nav_drip drives the continuous drip loop")
            return
        }
        playNavigationSound(soundId)
    }

    /** 播放导航音效（到达路点/偏离/到达终点）。 */
    private fun playNavigationSound(soundId: String) {
        val resId = soundResMap[soundId] ?: run {
            Timber.tag(TAG).w("Sound not loaded: %s", soundId)
            return
        }
        if (!soundLoadedIds.contains(soundId)) {
            if (!pendingSoundIds.contains(soundId)) {
                pendingSoundIds.add(soundId)
            }
            Timber.tag(TAG).i("Queued nav sound until loaded: %s", soundId)
            return
        }
        try {
            soundPool?.play(resId, 1.0f, 1.0f, 1, 0, 1.0f)
            Timber.tag(TAG).i("Played nav sound: %s", soundId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to play sound: %s", soundId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer?.stop()
        navAudioPlayer?.stop()
        microphoneRecorder?.stop()
        feedImageJob?.cancel()
        navConnectJob?.cancel()
        realtimeConnectJob?.cancel()
        realtimeClient?.disconnect()
        navigationBackendClient?.disconnect()
        beepPlayer?.release()
        dripPlayer?.release()
        soundPool?.release()
        soundResMap.clear()
        soundLoadedIds.clear()
        pendingSoundIds.clear()
    }

    private fun speakTextLocally(text: String) {
        // 废弃本地 TTS，仅保留日志用于调试流程
        Timber.tag(TAG).d("Local TTS (Disabled): $text")
    }
}
