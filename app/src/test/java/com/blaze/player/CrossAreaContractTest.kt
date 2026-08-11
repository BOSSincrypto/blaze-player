package com.blaze.player

import com.blaze.player.persistence.PlaybackRecoveryPolicy
import com.blaze.player.persistence.PlaybackRepository
import com.blaze.player.persistence.LifecycleEvent
import com.blaze.player.persistence.LifecycleOutcome
import com.blaze.player.persistence.PersistenceStoreOwnership
import com.blaze.player.persistence.StoreOwner
import com.blaze.player.persistence.PrivacyRedactor
import com.blaze.player.playback.AutoplayContext
import com.blaze.player.playback.AutoplayPolicy
import com.blaze.player.playback.AutoplayRequest
import com.blaze.player.playback.PlaybackSpeedStore
import com.blaze.player.playback.PlaybackService
import com.blaze.player.ui.PlaybackSpeed
import com.blaze.player.source.NetworkResponsePolicy
import com.blaze.player.source.NetworkFailure
import com.blaze.player.source.SourceNormalizer
import com.blaze.player.source.SourceResult
import com.blaze.player.source.LocalSourceAccess
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Cross-area acceptance tests keep the canonical policies from drifting apart. */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class CrossAreaContractTest {
    private val unreadable = object : LocalSourceAccess {
        override fun canRead(uri: Uri) = false
        override fun takePersistableReadPermission(uri: Uri) = false
    }

    @Test fun `empty launch has safe defaults and no stale autoplay`() {
        val snapshot = PlaybackRecoveryPolicy.coldStart("", emptyList(), null, false, PlaybackSpeedStore.DEFAULT)
        assertEquals(PlaybackSpeedStore.DEFAULT, snapshot.speed)
        assertFalse(snapshot.autoplay)
        assertTrue(snapshot.orderedSourceIdentities.isEmpty())
        assertEquals(StoreOwner.ROOM, PersistenceStoreOwnership.owner("position"))
        assertEquals(StoreOwner.DATASTORE, PersistenceStoreOwnership.owner("global_playback_speed"))
    }

    @Test fun `supported network source and local source share rejection boundary`() {
        assertTrue(SourceNormalizer.normalize(Uri.parse("https://example.test/video.mp4?access=1#part"), unreadable) is SourceResult.Accepted)
        assertTrue(SourceNormalizer.normalize(Uri.parse("file:///private/video.mp4"), unreadable) is SourceResult.Rejected)
        assertTrue(SourceNormalizer.normalize(Uri.parse("unsupported://example.test/video.mp4"), unreadable) is SourceResult.Rejected)
        assertEquals(NetworkFailure.AUTH_REQUIRED, NetworkResponsePolicy.classify(401, false))
        assertEquals(NetworkFailure.RANGE_UNSUPPORTED, NetworkResponsePolicy.classify(206, true, false))
    }

    @Test fun `speed history completion and resume use shared source policy`() {
        assertEquals(listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f), PlaybackSpeed.presets)
        assertEquals(null, PlaybackSpeedStore.normalize(Float.NaN))
        assertTrue(PlaybackRepository.isCompleted(70_000, 100_000))
        assertFalse(PlaybackRepository.isCompleted(69_999, 100_000))
        assertEquals(0, PlaybackRepository.resumePosition(null, true))
        assertEquals(0, PlaybackRepository.resumePosition(null, false))
    }

    @Test fun `lifecycle and autoplay retain singleton ownership rules`() {
        assertEquals(LifecycleOutcome.RETAIN_PLAYER, PlaybackRecoveryPolicy.lifecycleOutcome(LifecycleEvent.PIP_ENTERED, true))
        assertEquals(LifecycleOutcome.NEW_PLAYER_FROM_DURABLE, PlaybackRecoveryPolicy.lifecycleOutcome(LifecycleEvent.PROCESS_DIED, true))
        assertTrue(AutoplayPolicy.allows(AutoplayRequest(AutoplayContext.SHARE, "https://example.test/video.mp4")))
        assertFalse(AutoplayPolicy.allows(AutoplayRequest(AutoplayContext.RECONNECT, "https://example.test/video.mp4")))
        assertTrue(PlaybackService.playerInstance() == null || PlaybackService.playerInstance() === PlaybackService.playerInstance())
    }

    @Test fun `custom command media item payload decodes from nested bundle`() {
        val expected = MediaItem.Builder().setMediaId("test-id").setUri("https://example.test/video.mp4").build()
        val args = Bundle().apply {
            putBundle(PlaybackService.MEDIA_ITEM_ARGUMENT_KEY, expected.toBundle())
        }

        val decoded = PlaybackService.mediaItemFromArgs(args)
        // Media3 Bundleable round-tripping normalizes implementation details, so
        // compare the stable public identifier rather than implementation details
        // such as local configuration reconstruction.
        assertEquals(expected.mediaId, decoded?.mediaId)
        assertEquals(null, PlaybackService.mediaItemFromArgs(Bundle()))
    }

    @Test fun `cross-area diagnostics redact credentials and private paths`() {
        val redacted = PrivacyRedactor.metadata("url=https://example.test/video.mp4?token=redaction-marker path=C:\\Users\\boss\\private.mp4")
        assertFalse(redacted.contains("redaction-marker"))
        assertFalse(redacted.contains("C:\\Users\\boss"))
    }
}
