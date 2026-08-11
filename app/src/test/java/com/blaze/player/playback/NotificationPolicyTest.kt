package com.blaze.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    @Test fun `api 33 requests only once before playback when not yet requested`() {
        assertTrue(NotificationPermissionPolicy.shouldRequest(33, NotificationPermissionState.NOT_YET_REQUESTED, true))
        assertFalse(NotificationPermissionPolicy.shouldRequest(33, NotificationPermissionState.GRANTED, true))
        assertFalse(NotificationPermissionPolicy.shouldRequest(33, NotificationPermissionState.DENIED, true))
        assertFalse(NotificationPermissionPolicy.shouldRequest(33, NotificationPermissionState.NOT_YET_REQUESTED, false))
    }

    @Test fun `denial is never treated as visible notification permission`() {
        assertEquals(ForegroundNotificationPolicy.RUN_WITHOUT_SHADE_NOTIFICATION,
            NotificationPermissionPolicy.foregroundPolicy(33, NotificationPermissionState.DENIED))
        assertEquals(ForegroundNotificationPolicy.SHOW_MEDIA_CONTROLS,
            NotificationPermissionPolicy.foregroundPolicy(32, NotificationPermissionState.DENIED))
    }

    @Test fun `task removal retains playback while explicit stop stops playback`() {
        assertEquals(DismissalResult.RETAIN_PLAYBACK, NotificationStopPolicy.result(NotificationDismissal.TASK_REMOVED))
        assertEquals(DismissalResult.STOP_PLAYBACK, NotificationStopPolicy.result(NotificationDismissal.EXPLICIT_STOP))
    }
}
