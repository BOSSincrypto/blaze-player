package com.blaze.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplayTransitionControllerTest {
    private fun assertOnePlay(context: AutoplayContext) {
        val controller = AutoplayTransitionController()
        assertEquals(listOf(AutoplayTransitionController.Effect.PREPARE), controller.request("video", context))
        assertEquals(listOf(AutoplayTransitionController.Effect.PLAY), controller.prepared("video"))
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.prepared("video"))
    }

    @Test fun `history and playlist transitions autoplay once after preparation`() {
        listOf(AutoplayContext.HISTORY, AutoplayContext.PLAYLIST_START, AutoplayContext.PLAYLIST_NEXT).forEach(::assertOnePlay)
    }

    @Test fun `failed preparation clears autoplay and retry gets one intentional play`() {
        val controller = AutoplayTransitionController()
        controller.request("video", AutoplayContext.HISTORY)
        controller.failed("video")
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.prepared("video"))
        assertEquals(listOf(AutoplayTransitionController.Effect.PREPARE), controller.request("video", AutoplayContext.RETRY, retry = true))
        assertEquals(listOf(AutoplayTransitionController.Effect.PLAY), controller.prepared("video"))
    }

    @Test fun `focus denial and lifecycle callbacks cannot replay autoplay`() {
        val controller = AutoplayTransitionController()
        controller.request("video", AutoplayContext.PLAYLIST_START)
        controller.focusDenied("video")
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.prepared("video"))
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.request("video", AutoplayContext.RECONNECT))
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.request("video", AutoplayContext.PLAYLIST_START))
    }

    @Test fun `duplicate commands and repeated preparation emit no duplicate effects`() {
        val controller = AutoplayTransitionController()
        assertEquals(listOf(AutoplayTransitionController.Effect.PREPARE), controller.request("one", AutoplayContext.HISTORY))
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.request("one", AutoplayContext.HISTORY))
        assertEquals(listOf(AutoplayTransitionController.Effect.PLAY), controller.prepared("one"))
        assertEquals(emptyList<AutoplayTransitionController.Effect>(), controller.prepared("one"))
        assertEquals(listOf(AutoplayTransitionController.Effect.PREPARE), controller.request("two", AutoplayContext.PLAYLIST_NEXT))
    }
}
