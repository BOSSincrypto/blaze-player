package com.blaze.player.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackMathTest {
    @Test fun `timeline progress is bounded including unknown duration`() {
        assertEquals(0f, PlaybackMath.progress(10, 0), 0f)
        assertEquals(0f, PlaybackMath.progress(-10, 100), 0f)
        assertEquals(1f, PlaybackMath.progress(200, 100), 0f)
        assertTrue(PlaybackMath.progress(50, -1).isFinite())
        assertEquals(0L, PlaybackMath.position(-10, 100))
        assertEquals(100L, PlaybackMath.position(200, 100))
    }

    @Test fun `seek targets clamp and coalescer dispatches final only`() {
        assertEquals(0L, PlaybackMath.seekTarget(-1f, 1000))
        assertEquals(1000L, PlaybackMath.seekTarget(2f, 1000))
        assertEquals(0L, PlaybackMath.seekTarget(Float.NaN, 1000))
        val calls = mutableListOf<Long>()
        SeekCoalescer(calls::add).also { it.offer(10); it.offer(20); it.release() }
        assertEquals(listOf(20L), calls)
    }

    @Test fun `vertical gesture levels are finite and bounded`() {
        assertEquals(1f, PlaybackMath.verticalLevel(-1000f, 100f))
        assertEquals(0f, PlaybackMath.verticalLevel(1000f, 100f))
        assertTrue(PlaybackMath.verticalLevel(Float.NaN, 0f).isFinite())
    }

    @Test fun `speed exposes exact presets and rejects invalid values`() {
        assertEquals(listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f), PlaybackSpeed.presets)
        assertNull(PlaybackSpeed.validate(Float.NaN))
        assertNull(PlaybackSpeed.validate(4.01f))
        assertEquals(1f, PlaybackSpeed.validate(1f))
    }
}
