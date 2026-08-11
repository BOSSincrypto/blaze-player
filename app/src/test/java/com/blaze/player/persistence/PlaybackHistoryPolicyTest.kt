package com.blaze.player.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryPolicyTest {
    @Test fun `history completion uses exact remaining or percent thresholds`() {
        assertTrue(PlaybackRepository.isCompleted(70_000, 100_000))
        assertTrue(PlaybackRepository.isCompleted(95_000, 100_000))
        assertFalse(PlaybackRepository.isCompleted(69_999, 100_000))
        assertFalse(PlaybackRepository.isCompleted(94_999, 100_000))
    }

    @Test fun `unknown and live durations never infer completion`() {
        assertFalse(PlaybackRepository.isCompleted(100_000, null))
        assertFalse(PlaybackRepository.isCompleted(100_000, 0))
        assertEquals(0, PlaybackRepository.resumePosition(null, true))
    }

    @Test fun `resume is source safe and defaults to zero`() {
        val saved = PlaybackPositionEntity("source-a", 12_000, 1)
        assertEquals(12_000, PlaybackRepository.resumePosition(saved, false))
        assertEquals(0, PlaybackRepository.resumePosition(saved, true))
        assertEquals(0, PlaybackRepository.resumePosition(null, false))
    }
}
