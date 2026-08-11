package com.blaze.player.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {
    @Test fun `states expose loading ready and error`() {
        assertEquals(PlaybackStatus.LOADING, PlaybackState(PlaybackStatus.LOADING).status)
        assertEquals(PlaybackStatus.READY, PlaybackState(PlaybackStatus.READY).status)
        assertEquals(PlaybackStatus.ERROR, PlaybackState(PlaybackStatus.ERROR).status)
    }

    @Test fun `network errors map to actionable redacted message`() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException("network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertEquals("Network error", mapped.title)
        assertTrue(mapped.canRetry)
        assertFalse(mapped.message.contains("secret"))
    }

    @Test fun `decoder errors are actionable and not retryable`() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        )
        assertEquals("Unsupported video", mapped.title)
        assertFalse(mapped.canRetry)
    }
}
