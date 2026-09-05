package com.example.vva

import android.annotation.SuppressLint
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

class MicrophoneRecorder(context: android.content.Context) {
    private var recorderProcessor: OnRecorderProcessor? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var processingHandler: Handler? = null
    private var processingThread: HandlerThread? = null
    private var silenceForwardedMs = 0L

    companion object {
        private const val TAG = "MicrophoneRecorder"
        private const val THREAD_JOIN_TIMEOUT_MS = 500
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 保留原有的本地输入门限；服务端 VAD 仍负责最终的语音分段。
        private const val MIN_RMS_AMPLITUDE = 500.0
        private const val MAX_SILENCE_FORWARD_MS = 1000L
    }

    fun setRecorderProcessor(processor: OnRecorderProcessor) {
        recorderProcessor = processor
    }

    @Synchronized
    fun start() {
        Timber.tag(TAG).i("start...")
        if (isRecording) {
            Timber.tag(TAG).w("Recording already in progress")
            return
        }

        try {
            val bufferSize = getBufferSize()
            audioRecord = getAudioRecord(bufferSize)
            if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Timber.tag(TAG).e("AudioRecord initialization failed")
                releaseResources()
                return
            }

            audioRecord!!.startRecording()
            isRecording = true
            silenceForwardedMs = 0L

            val buffer = ByteArray(bufferSize)
            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                Timber.tag(TAG).i("AudioRecorder thread started")
                while (isRecording && !Thread.currentThread().isInterrupted) {
                    val record = audioRecord ?: break
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        if (!shouldForwardAudio(buffer, bytesRead)) {
                            continue
                        }

                        val handler = processingHandler
                        if (handler != null) {
                            val data = buffer.copyOf(bytesRead)
                            handler.post { onDataReceived(data) }
                        } else {
                            onDataReceived(buffer.copyOf(bytesRead))
                        }

                        try {
                            Thread.sleep(20)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        Timber.tag(TAG).e("AudioRecord read failed: ERROR_INVALID_OPERATION")
                        isRecording = false
                        stopInner()
                        break
                    } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Timber.tag(TAG).e("AudioRecord read failed: ERROR_BAD_VALUE")
                        isRecording = false
                        stopInner()
                        break
                    }
                }
                Timber.tag(TAG).i("AudioRecorder thread finished")
            }, "AudioRecorder Thread")
            recordingThread?.start()

            processingThread = HandlerThread("AudioProcessingThread")
            processingThread?.start()
            processingHandler = Handler(processingThread!!.looper)
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "Missing RECORD_AUDIO permission?")
            releaseResources()
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "AudioRecord initialization failed")
            releaseResources()
        }
    }

    @Synchronized
    fun stop() {
        Timber.tag(TAG).d("stop...")
        if (!isRecording) return
        isRecording = false
        stopInner()
    }

    private fun stopInner() {
        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }

            val threadToStop = recordingThread
            if (threadToStop != null && threadToStop !== Thread.currentThread()) {
                threadToStop.interrupt()
                threadToStop.join(THREAD_JOIN_TIMEOUT_MS.toLong())
            }
            recordingThread = null
        } catch (e: InterruptedException) {
            Timber.tag(TAG).e(e, "Interrupted while stopping recording thread")
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).e(e, "AudioRecord stop failed")
        } finally {
            releaseResources()
        }

        processingHandler?.removeCallbacksAndMessages(null)
        processingThread?.quitSafely()
        processingThread = null
        processingHandler = null
    }

    private fun releaseResources() {
        audioRecord?.release()
        audioRecord = null
    }

    private fun getBufferSize(): Int {
        var bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2
        }
        return bufferSize
    }

    @SuppressLint("MissingPermission")
    private fun getAudioRecord(bufferSize: Int): AudioRecord {
        return AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNELS, AUDIO_FORMAT, bufferSize)
    }

    private fun onDataReceived(data: ByteArray) {
        recorderProcessor?.onDataProcess(data)
    }

    private fun shouldForwardAudio(data: ByteArray, length: Int): Boolean {
        var sumSquares = 0L
        var samples = 0
        var index = 0
        while (index + 1 < length) {
            val sample = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sumSquares += signedSample.toLong() * signedSample.toLong()
            samples++
            index += 2
        }

        if (samples == 0) return false
        val rms = kotlin.math.sqrt(sumSquares.toDouble() / samples)
        val chunkDurationMs = samples * 1000L / SAMPLE_RATE

        if (rms >= MIN_RMS_AMPLITUDE) {
            silenceForwardedMs = 0L
            return true
        }

        if (silenceForwardedMs < MAX_SILENCE_FORWARD_MS) {
            silenceForwardedMs += chunkDurationMs
            return true
        }
        return false
    }

}
