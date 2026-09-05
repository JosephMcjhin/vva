package com.example.vva

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

/**
 * 一个使用 AudioTrack 和协程进行流式 PCM 音频播放的播放器。
 */
class AudioPlayer(private val context: Context) {

    private companion object {
        private const val TAG = "AudioPlayer"

        // 语音通常使用较低的采样率，例如 24000 Hz
        private const val SAMPLE_RATE = 24000
        private const val CHANNELS = 1 // 单声道
        private const val SAMPLE_WIDTH = 2 // 16-bit PCM (2 bytes per sample)
        private const val BYTES_PER_FRAME = CHANNELS * SAMPLE_WIDTH // 每帧 2 字节
        private const val MAX_QUEUE_CHUNKS = 60
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val queuedChunks = AtomicInteger(0)

    @Volatile
    private var isPlaying = false
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── 音量 / ducking（被其它播放器压低） ──────────────────────────────────
    // 当前生效音量 = normalVolume * duckFactor。duckFactor 在 duck() 时变小、
    // unduck() 时回到 1.0，用于「导航说话时把 LLM 压低」的效果。
    @Volatile private var normalVolume = 1.0f
    @Volatile private var duckFactor = 1.0f
    private val duckLock = Any()

    /**
     * 队列从「有数据」变为「空」时回调一次（在播放协程里触发）。
     * 用于通知上层「本播放器的音频已全部播完」，可据此恢复被 duck 的其它播放器。
     * 注意：仅在真正写出过数据后才会触发，避免刚 start 就误触发。
     */
    var onQueueDrained: (() -> Unit)? = null
    @Volatile private var hasEverPlayedData = false

    private fun applyVolume() {
        val v = (normalVolume * duckFactor).coerceIn(0.0f, 1.0f)
        try {
            audioTrack?.setVolume(v)
        } catch (e: Exception) {
            // 部分 ROM 对 setVolume 支持不全，忽略
        }
    }

    /**
     * 设置正常音量（0.0 ~ 1.0）。不会被 duck 影响。
     */
    fun setNormalVolume(v: Float) {
        synchronized(duckLock) {
            normalVolume = v.coerceIn(0.0f, 1.0f)
            applyVolume()
        }
    }

    /**
     * 闪避：把当前播放音量压低到 [factor]（0.0 ~ 1.0，通常 0.3）。
     * 用于「导航 TTS 开始播放时，把 LLM 对话音频压低」。
     */
    fun duck(factor: Float = 0.3f) {
        synchronized(duckLock) {
            duckFactor = factor.coerceIn(0.0f, 1.0f)
            applyVolume()
        }
    }

    /**
     * 恢复：撤销 duck()，音量回到正常水平。
     * 用于「导航 TTS 播放结束后，把 LLM 对话音频音量还原」。
     */
    fun unduck() {
        synchronized(duckLock) {
            duckFactor = 1.0f
            applyVolume()
        }
    }

    /**
     * 初始化并开始 AudioTrack 播放。
     */
    fun start() {
        if (isPlaying) return

        try {
            // 确保最小缓冲区大小满足要求
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // 设置缓冲区大小为最小大小的 2 倍或更多，以减少欠载的风险
            val bufferSizeInBytes = minBufferSize * 2

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Timber.tag(TAG).e("AudioTrack 初始化失败")
                audioTrack?.release()
                audioTrack = null
                return
            }

            audioTrack?.play()
            isPlaying = true
            applyVolume()  // 应用初始音量（含可能的 duck 状态）

            playbackJob = scope.launch {
                playAudio()
            }
            Timber.tag(TAG).d("AudioPlayer 启动成功，缓冲区大小：$bufferSizeInBytes 字节")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "启动 AudioPlayer 时发生错误")
            e.printStackTrace()
        }
    }

    /**
     * 停止播放并释放资源。
     */
    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        clearQueue()

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Timber.tag(TAG).d("AudioPlayer 停止并释放资源")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "停止/释放 AudioTrack 时发生错误")
            e.printStackTrace()
        }
    }

    /**
     * 将新的音频数据块添加到队列中等待播放。
     */
    fun addAudioData(audioData: ByteArray) {
        if (isPlaying && audioData.isNotEmpty()) {
            while (queuedChunks.get() >= MAX_QUEUE_CHUNKS) {
                if (pollAudioData() == null) break
            }
            audioQueue.offer(audioData)
            queuedChunks.incrementAndGet()
        }
    }

    /**
     * 清空待播放队列，并刷新 AudioTrack 中已经写入但尚未播放的旧数据。
     * 导航 TTS 的新句子到达时调用，保证旧句不会继续从硬件缓冲区播出。
     */
    fun prepareForNextTurn() {
        clearQueue()
        hasEverPlayedData = false
        try {
            audioTrack?.apply {
                pause()
                flush()
                play()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "刷新 AudioTrack 缓冲区失败")
        }
        Timber.tag(TAG).d("清除音频队列并刷新播放缓冲区")
    }

    /**
     * 强行插播：清空队列和 AudioTrack 缓冲区，立即播放新数据。
     */
    fun interruptAndPlay(audioData: ByteArray) {
        if (!isPlaying) return
        
        clearQueue()
        try {
            audioTrack?.apply {
                pause()
                flush()
                play()
            }
            Timber.tag(TAG).d("执行强行插播，已重置缓冲区")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "重置 AudioTrack 缓冲区失败")
        }
        
        if (audioData.isNotEmpty()) {
            audioQueue.offer(audioData)
            queuedChunks.incrementAndGet()
        }
    }

    private fun clearQueue() {
        audioQueue.clear()
        queuedChunks.set(0)
    }

    private fun pollAudioData(): ByteArray? {
        val data = audioQueue.poll()
        if (data != null) {
            queuedChunks.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        return data
    }

    /**
     * 协程中的音频写入循环。
     */
    private suspend fun playAudio() {
        while (isPlaying && currentCoroutineContext().isActive) {
            try {
                // 等待队列中的数据，最多等待 100 毫秒，防止持续空转
                val audioData = withTimeoutOrNull(100) {
                    while (audioQueue.isEmpty() && isPlaying) {
                        delay(10)
                    }
                    pollAudioData()
                }

                audioData?.let { data ->
                    // 写入数据到 AudioTrack
                    val written = audioTrack?.write(data, 0, data.size) ?: 0
                    hasEverPlayedData = true
                }

                // 队列已空 + 之前播放过数据 → 通知上层「播完了」
                // （用于让 LLM 在导航 TTS 真正播完后才恢复音量）
                if (hasEverPlayedData && audioQueue.isEmpty()) {
                    hasEverPlayedData = false
                    onQueueDrained?.invoke()
                }

                // 避免 CPU 过度消耗
                delay(1)
            } catch (e: TimeoutCancellationException) {
                // 队列为空，继续等待或检查 isPlaying 状态
            } catch (e: CancellationException) {
                // 协程被取消，跳出循环
                break
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "播放循环发生错误")
                break
            }
        }
    }
}
