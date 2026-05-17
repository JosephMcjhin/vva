package com.example.vva

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
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
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var isPlaying = false
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

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
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Timber.tag(TAG).e("AudioTrack 初始化失败")
                audioTrack?.release()
                audioTrack = null
                return
            }

            audioTrack?.play()
            isPlaying = true

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
        audioQueue.clear()

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
            audioQueue.offer(audioData)
        }
    }

    /**
     * 清空待播放队列。
     */
    fun prepareForNextTurn() {
        audioQueue.clear()
        Timber.tag(TAG).d("清除音频队列")
    }

    /**
     * 强行插播：清空队列和 AudioTrack 缓冲区，立即播放新数据。
     */
    fun interruptAndPlay(audioData: ByteArray) {
        if (!isPlaying) return
        
        audioQueue.clear()
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
        }
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
                    audioQueue.poll()
                }

                audioData?.let { data ->
                    // 写入数据到 AudioTrack
                    val written = audioTrack?.write(data, 0, data.size) ?: 0
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