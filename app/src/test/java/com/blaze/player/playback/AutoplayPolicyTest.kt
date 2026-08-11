package com.blaze.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPolicyTest {
    @Test fun `supported user playback contexts autoplay`() {
        listOf(
            AutoplayContext.PICKER,
            AutoplayContext.SHARE,
            AutoplayContext.HISTORY,
            AutoplayContext.PLAYLIST_START,
            AutoplayContext.PLAYLIST_NEXT,
            AutoplayContext.RETRY,
        ).forEach { context ->
            assertTrue(context.name, AutoplayPolicy.allows(AutoplayRequest(context, "video")))
        }
    }

    @Test fun `reconnect and cold launch never autoplay`() {
        listOf(AutoplayContext.RECONNECT, AutoplayContext.COLD_LAUNCH).forEach { context ->
            assertFalse(context.name, AutoplayPolicy.allows(AutoplayRequest(context, "video")))
        }
    }
}
