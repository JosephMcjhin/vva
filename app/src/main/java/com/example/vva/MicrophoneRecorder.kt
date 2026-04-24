package com.example.vva

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import timber.log.Timber

fun interface OnRecorderProcessor {

    fun onDataProcess(data: ByteArray)
}

class MicrophoneRecorder(context: Context) {

    private var recorderProcessor: OnRecorderProcessor? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var processingHandler: Handler? = null
    private var processingThread: HandlerThread? = null

    companion object {
        private const val TAG = "MicrophoneRecorder"

        private const val THREAD_JOIN_TIMEOUT_MS: Int = 500

        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    fun setRecorderProcessor(processor: OnRecorderProcessor) {
        this.recorderProcessor = processor
    }

    @Synchronized
    fun start() {
        Timber.tag(TAG).i("start...")
        if (isRecording) {
            Timber.w(TAG, "Recording already in progress")
            return
        }

        try {
            val bufferSize = getBufferSize()
            audioRecord = getAudioRecord(bufferSize)
            if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e(TAG, "AudioRecord initialization failed")
                releaseResources()
                return
            }
            audioRecord!!.startRecording()
            isRecording = true

            val buffer = ByteArray(bufferSize)
            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                Timber.i(TAG, "AudioRecorder thread started")
                while (isRecording && !Thread.currentThread().isInterrupted) {
                    val bytesRead = audioRecord!!.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        if (processingHandler != null) {
                            val data = ByteArray(bytesRead)
                            System.arraycopy(buffer, 0, data, 0, bytesRead)
                            processingHandler?.post { onDataReceived(data) }
                        } else {
                            onDataReceived(buffer) // 如果没有处理线程，直接回调
                        }
                        Thread.sleep(20)
                    } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        Timber.e(TAG, "Error reading audio data: ERROR_INVALID_OPERATION")
                        stopInner() // 发生错误时停止录制
                    } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Timber.e(TAG, "Error reading audio data: ERROR_BAD_VALUE")
                        stopInner() // 发生错误时停止录制
                    }
                }
                Timber.i(TAG, "AudioRecorder thread finished")
            }, "AudioRecorder Thread")

            recordingThread?.start()

            // 初始化用于处理数据的后台线程
            processingThread = HandlerThread("AudioProcessingThread")
            processingThread?.start()
            processingHandler = Handler(processingThread!!.looper)
        } catch (e: SecurityException) {
            Timber.e(TAG, "Missing RECORD_AUDIO permission?", e)
            releaseResources()
        } catch (e: IllegalStateException) {
            Timber.e(TAG, "startRecording() called on uninitialized AudioRecord", e)
            releaseResources()
        }
    }

    @Synchronized
    fun stop() {
        Timber.d(TAG, "stop...")
        if (!isRecording) {
            return
        }
        isRecording = false
        stopInner()
    }

    private fun stopInner() {
        try {
            if (audioRecord != null) {
                if (audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord!!.stop()
                }
            }
            if (recordingThread != null) {
                recordingThread?.interrupt() // 请求线程停止
                recordingThread?.join(THREAD_JOIN_TIMEOUT_MS.toLong()) // 等待线程结束
                recordingThread = null
            }
        } catch (e: InterruptedException) {
            Timber.e(TAG, "Interrupted while stopping recording thread", e)
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            Timber.e(TAG, "AudioRecord stop failed", e)
        } finally {
            releaseResources()
        }

        // 停止处理数据的后台线程
        if (processingThread != null) {
            processingThread?.quitSafely()
            processingThread = null
            processingHandler = null
        }
    }

    private fun releaseResources() {
        if (audioRecord != null) {
            audioRecord!!.release()
            audioRecord = null
        }
    }

    private fun getBufferSize(): Int {
        var bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Timber.tag(TAG).w("Failed to get minimum buffer size, using fallback size.")
            bufferSize = SAMPLE_RATE * 2 // 后备缓冲区大小
        }
        return bufferSize
    }

    @SuppressLint("MissingPermission")
    private fun getAudioRecord(bufferSize: Int): AudioRecord {
        return AudioRecord(
            AUDIO_SOURCE,
            SAMPLE_RATE,
            CHANNELS,
            AUDIO_FORMAT,
            bufferSize
        )
    }

    private fun onDataReceived(data: ByteArray) {
        recorderProcessor?.onDataProcess(data)
    }
}