package com.example.vva

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToLong

/**
 * Looping drip player driven by nav_drip messages.
 *
 * The actual single-shot playback is delegated to the caller so we can
 * reuse the existing SoundPool-loaded nav_drip.wav resource.
 */
class DripPlayer(
    private val playOnce: () -> Unit
) {

    private companion object {
        private const val TAG = "DripPlayer"
        private const val MIN_INTERVAL_MS = 80L
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var currentIntervalMs: Float = 0f
    @Volatile private var shouldPlay: Boolean = false
    private var playJob: Job? = null

    private suspend fun loop() {
        try {
            while (scope.isActive && shouldPlay) {
                playOnce()
                val intervalMs = currentIntervalMs.roundToLong().coerceAtLeast(MIN_INTERVAL_MS)
                delay(intervalMs)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Drip loop ended")
        }
    }

    fun setActive(active: Boolean, intervalMs: Float) {
        if (!active) {
            stop()
            return
        }

        currentIntervalMs = intervalMs.coerceAtLeast(MIN_INTERVAL_MS.toFloat())
        if (!shouldPlay) {
            shouldPlay = true
            playJob?.cancel()
            playJob = scope.launch { loop() }
        }
    }

    fun stop() {
        shouldPlay = false
        playJob?.cancel()
        playJob = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
