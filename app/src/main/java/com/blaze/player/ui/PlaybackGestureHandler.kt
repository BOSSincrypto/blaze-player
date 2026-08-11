package com.blaze.player.ui

import kotlin.math.abs

/** Pure gesture state machine. UI/system side effects are supplied as callbacks. */
class PlaybackGestureHandler(
    private val duration: () -> Long,
    private val position: () -> Long,
    private val brightness: () -> Float,
    private val volume: () -> Float,
    private val seek: (Long) -> Unit,
    private val setBrightness: (Float) -> Unit,
    private val setVolume: (Float) -> Unit,
    private val showOverlay: (String) -> Unit,
    private val clearOverlay: () -> Unit,
    private val onSystemControlFailure: (Kind, Throwable) -> Unit = { kind, _ ->
        showOverlay("${kind.name.lowercase().replaceFirstChar { it.uppercase() }} unavailable")
    }
) {
    enum class Kind { SEEK, BRIGHTNESS, VOLUME }
    private var kind: Kind? = null
    private var startX = 0f
    private var startY = 0f
    private var width = 1f
    private var height = 1f
    private var startPosition = 0L
    private var startLevel = 0f
    private var systemControlFailed = false
    private val coalescer = SeekCoalescer(seek)

    fun down(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        runCatching { clearOverlay() }
        systemControlFailed = false
        startX = x; startY = y
        width = viewWidth.coerceAtLeast(1f); height = viewHeight.coerceAtLeast(1f)
        kind = null
        startPosition = PlaybackMath.position(position(), duration())
        val controlKind = if (x < width / 2f) Kind.BRIGHTNESS else Kind.VOLUME
        startLevel = runCatching {
            if (controlKind == Kind.BRIGHTNESS) brightness() else volume()
        }.getOrElse {
            onFailure(controlKind, it)
            0.5f
        }.let(PlaybackMath::clamp)
    }

    fun move(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        if (kind == null && maxOf(abs(dx), abs(dy)) < 8f) return
        if (kind == null) kind = if (abs(dx) >= abs(dy)) Kind.SEEK else if (startX < width / 2f) Kind.BRIGHTNESS else Kind.VOLUME
        when (kind) {
            Kind.SEEK -> {
                val target = PlaybackMath.seekByDelta(startPosition, dx, width, duration())
                coalescer.offer(target)
                showOverlay("Seek ${target / 1000}s")
            }
            Kind.BRIGHTNESS -> {
                val level = PlaybackMath.verticalLevel(dy, height, startLevel)
                if (runSystemControl(Kind.BRIGHTNESS, level, setBrightness)) {
                    showOverlay("Brightness ${(level * 100).toInt()}%")
                }
            }
            Kind.VOLUME -> {
                val level = PlaybackMath.verticalLevel(dy, height, startLevel)
                if (runSystemControl(Kind.VOLUME, level, setVolume)) {
                    showOverlay("Volume ${(level * 100).toInt()}%")
                }
            }
            null -> Unit
        }
    }

    fun up() {
        if (kind == Kind.SEEK) coalescer.release()
        kind = null
        // Keep actionable system-control failures visible until the next gesture.
        if (!systemControlFailed) runCatching { clearOverlay() }
    }

    private fun runSystemControl(kind: Kind, level: Float, setter: (Float) -> Unit): Boolean {
        return runCatching { setter(level) }.onFailure { error ->
            // System settings/audio routes can disappear while a gesture is active.
            // Report the failure, but keep the gesture state machine and player alive.
            onFailure(kind, error)
        }.isSuccess
    }

    private fun onFailure(kind: Kind, error: Throwable) {
        systemControlFailed = true
        runCatching { onSystemControlFailure(kind, error) }
    }
}
