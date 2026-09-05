package com.example.vva

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turn-calibration beep player.
 *
 * Forward walking uses nav_drip and the real drip SoundPool asset only.
 * This class intentionally does not synthesize any forward-walk drip/beep sound.
 */
class BeepPlayer(
    private val sampleRate: Int = 44100,
    private val amplitude: Float = 0.3f
) {

    private companion object {
        private const val TAG = "BeepPlayer"
        private const val BEEP_MS_ALERT = 80
        private const val ATTACK_MS_ALERT = 2L
        private const val RELEASE_MS_ALERT = 15L
        private const val HRTF_MAX_DELAY_SAMPLES = 7
        private const val H2_GAIN_ALERT = 0.30f
        private const val H3_GAIN_ALERT = 0.20f
        private const val H4_GAIN_ALERT = 0.10f
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var currentFreqHz: Int = 0
    @Volatile private var currentIntervalMs: Float = 0f
    @Volatile private var currentPan: Float = 0f
    @Volatile private var currentVolume: Float = 1f
    @Volatile private var swapChannels: Boolean = false
    @Volatile private var shouldPlay: Boolean = false
    private var playJob: Job? = null

    private var audioTrack: AudioTrack? = null

    private fun ensureTrack(): AudioTrack? {
        audioTrack?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) return it }
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 10)

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also {
                    it.play()
                    audioTrack = it
                }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create AudioTrack")
            null
        }
    }

    private fun synthAlertTone(freqHz: Int, chunkMs: Int, baseAmp: Float): ShortArray {
        val samples = sampleRate * chunkMs / 1000
        val out = ShortArray(samples)
        if (freqHz <= 0) return out

        val phaseStep = 2.0 * PI * freqHz / sampleRate
        val ampInt = (Short.MAX_VALUE * baseAmp)
            .coerceIn(0f, Short.MAX_VALUE.toFloat())
            .toInt()
        var phase = 0.0

        for (i in 0 until samples) {
            val fundamental = sin(phase).toFloat()
            val h2 = sin(2.0 * phase).toFloat()
            val h3 = sin(3.0 * phase).toFloat()
            val h4 = sin(4.0 * phase).toFloat()
            val mixed = fundamental +
                H2_GAIN_ALERT * h2 +
                H3_GAIN_ALERT * h3 +
                H4_GAIN_ALERT * h4
            out[i] = (mixed * ampInt).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()

            phase += phaseStep
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }

        return out
    }

    private fun applyEnvelope(raw: ShortArray, attackMs: Long, releaseMs: Long): ShortArray {
        if (raw.isEmpty()) return raw

        val attackSamples = (sampleRate * attackMs / 1000).toInt().coerceAtMost(raw.size / 2)
        val releaseSamples = (sampleRate * releaseMs / 1000).toInt().coerceAtMost(raw.size / 2)
        if (attackSamples <= 0 && releaseSamples <= 0) return raw

        val enveloped = raw.copyOf()
        for (i in 0 until attackSamples.coerceAtMost(enveloped.size)) {
            val t = i.toFloat() / attackSamples
            val gain = 0.5f - 0.5f * cos(PI.toFloat() * t)
            enveloped[i] = (enveloped[i] * gain).toInt().toShort()
        }

        val n = enveloped.size
        for (i in 0 until releaseSamples.coerceAtMost(n)) {
            val t = i.toFloat() / releaseSamples
            val gain = 0.5f + 0.5f * cos(PI.toFloat() * t)
            val idx = n - releaseSamples + i
            if (idx in 0 until n) {
                enveloped[idx] = (enveloped[idx] * gain).toInt().toShort()
            }
        }

        return enveloped
    }

    private fun panToStereo(mono: ShortArray, pan: Float, volume: Float): ShortArray {
        val clampedPan = pan.coerceIn(-1f, 1f)
        val outputPan = if (swapChannels) -clampedPan else clampedPan
        val theta = (outputPan + 1f) * (PI.toFloat() / 4f)
        val leftGain = cos(theta)
        val rightGain = sin(theta)
        val vol = volume.coerceIn(0f, 1f)
        val out = ShortArray(mono.size * 2)

        val delaySamples = (abs(outputPan) * HRTF_MAX_DELAY_SAMPLES).roundToInt()
        val delayLeft = outputPan > 0f
        val delayRight = outputPan < 0f

        for (i in mono.indices) {
            val sample = (mono[i] * vol).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val leftDirect = (sample * leftGain).toInt()
            val rightDirect = (sample * rightGain).toInt()
            val leftDelayed = if (delayLeft && delaySamples > 0 && i - delaySamples >= 0) {
                (mono[i - delaySamples] * leftGain * vol).toInt()
            } else {
                0
            }
            val rightDelayed = if (delayRight && delaySamples > 0 && i - delaySamples >= 0) {
                (mono[i - delaySamples] * rightGain * vol).toInt()
            } else {
                0
            }

            out[i * 2] = (leftDirect + 0.3f * leftDelayed).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            out[i * 2 + 1] = (rightDirect + 0.3f * rightDelayed).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        return out
    }

    private suspend fun loop() {
        val track = ensureTrack() ?: return
        var lastKey = BeepKey(freq = -1, pan = 999f, volume = -1f, intervalMs = 0f, swapped = false)
        var cycleStereo = ShortArray(0)

        try {
            while (scope.isActive && shouldPlay) {
                val freqHz = currentFreqHz
                val intervalMs = currentIntervalMs

                if (freqHz <= 0) {
                    val key = BeepKey(
                        freq = 0,
                        pan = currentPan,
                        volume = currentVolume,
                        intervalMs = intervalMs,
                        swapped = swapChannels
                    )
                    if (key != lastKey) {
                        cycleStereo = ShortArray(sampleRate * BEEP_MS_ALERT / 1000 * 2)
                        lastKey = key
                    }
                    track.write(cycleStereo, 0, cycleStereo.size)
                    delay(10)
                    continue
                }

                val gapMs = (intervalMs - BEEP_MS_ALERT).toInt().coerceAtLeast(0)
                val key = BeepKey(
                    freq = freqHz,
                    pan = currentPan,
                    volume = currentVolume,
                    intervalMs = intervalMs,
                    swapped = swapChannels
                )

                if (key != lastKey || cycleStereo.isEmpty()) {
                    val rawMono = synthAlertTone(freqHz, BEEP_MS_ALERT, amplitude)
                    val envelopedMono = applyEnvelope(rawMono, ATTACK_MS_ALERT, RELEASE_MS_ALERT)
                    val beepStereo = panToStereo(envelopedMono, currentPan, currentVolume)
                    cycleStereo = if (gapMs > 0) {
                        val silenceStereo = ShortArray(sampleRate * gapMs / 1000 * 2)
                        ShortArray(beepStereo.size + silenceStereo.size).also {
                            beepStereo.copyInto(it, 0)
                            silenceStereo.copyInto(it, beepStereo.size)
                        }
                    } else {
                        beepStereo
                    }
                    lastKey = key
                }

                track.write(cycleStereo, 0, cycleStereo.size)
                delay(10)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Beep loop ended")
        }
    }

    fun setActive(
        active: Boolean,
        freqHz: Int,
        pan: Float = 0f,
        volume: Float = 1f,
        intervalMs: Float = 0f,
        beepType: String = "turn_calibrate"
    ) {
        if (!active) {
            stop()
            return
        }

        if (beepType != "turn_calibrate") {
            Timber.tag(TAG).i("Ignoring unsupported beep_type: %s", beepType)
            stop()
            return
        }

        currentFreqHz = freqHz.coerceAtLeast(0)
        currentPan = pan.coerceIn(-1f, 1f)
        currentVolume = volume.coerceIn(0f, 1f)
        currentIntervalMs = intervalMs.coerceAtLeast(0f)

        if (!shouldPlay) {
            shouldPlay = true
            playJob?.cancel()
            playJob = scope.launch { loop() }
        }
    }

    fun setChannelSwap(swapped: Boolean) {
        swapChannels = swapped
    }

    fun stop() {
        shouldPlay = false
        playJob?.cancel()
        playJob = null
        try {
            audioTrack?.apply {
                if (playState != AudioTrack.PLAYSTATE_STOPPED) stop()
                release()
            }
        } catch (_: Exception) {
        }
        audioTrack = null
        currentFreqHz = 0
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private data class BeepKey(
        val freq: Int,
        val pan: Float,
        val volume: Float,
        val intervalMs: Float,
        val swapped: Boolean
    )
}
