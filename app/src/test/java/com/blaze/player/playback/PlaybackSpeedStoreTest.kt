package com.blaze.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSpeedStoreTest {
    @Test fun `normalizes only finite supported speeds`() {
        assertEquals(.25f, PlaybackSpeedStore.normalize(.25f))
        assertEquals(4f, PlaybackSpeedStore.normalize(4f))
        assertNull(PlaybackSpeedStore.normalize(0f))
        assertNull(PlaybackSpeedStore.normalize(4.01f))
        assertNull(PlaybackSpeedStore.normalize(Float.NaN))
        assertNull(PlaybackSpeedStore.normalize(Float.POSITIVE_INFINITY))
    }
}
