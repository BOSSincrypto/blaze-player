package com.blaze.player.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun `cold start restores durable order position completion and speed without autoplay`() {
        val snapshot = PlaybackRecoveryPolicy.coldStart(
            sourceIdentity = "https://example.test/a.mp4",
            orderedSourceIdentities = listOf("a", "b", "a"),
            position = PlaybackPositionEntity("a", 12_000, 1),
            completed = false,
            speed = 1.5f
        )
        assertEquals(listOf("a", "b"), snapshot.orderedSourceIdentities)
        assertEquals(12_000, snapshot.positionMs)
        assertEquals(1.5f, snapshot.speed)
        assertEquals(false, snapshot.completed)
    }

    @Test fun `completed recovery opens source at zero and process death gets new player`() {
        assertEquals(0, PlaybackRecoveryPolicy.coldStart("a", listOf("a"), PlaybackPositionEntity("a", 99, 1), true, 1f).positionMs)
        assertEquals(LifecycleOutcome.NEW_PLAYER_FROM_DURABLE, PlaybackRecoveryPolicy.lifecycleOutcome(LifecycleEvent.PROCESS_DIED, true))
        assertEquals(LifecycleOutcome.RETAIN_PLAYER, PlaybackRecoveryPolicy.lifecycleOutcome(LifecycleEvent.PIP_ENTERED, true))
        assertEquals(LifecycleOutcome.RESTORE_DURABLE, PlaybackRecoveryPolicy.lifecycleOutcome(LifecycleEvent.SERVICE_RESTARTED, false))
        assertEquals(false, PlaybackRecoveryPolicy.coldStart("a", listOf("a"), null, false, 1f).autoplay)
    }

    @Test fun `checkpoint coalescer retains latest acknowledged state`() = runBlocking {
        val writes = mutableListOf<Int>()
        val coalescer = CheckpointCoalescer<Int> { writes += it }
        coalescer.submit(1)
        coalescer.submit(2)
        coalescer.flush()
        assertEquals(listOf(2), writes)
    }

    @Test fun `store ownership is isolated and explicit`() {
        assertEquals(StoreOwner.ROOM, PersistenceStoreOwnership.owner("position"))
        assertEquals(StoreOwner.ROOM, PersistenceStoreOwnership.owner("playlist"))
        assertEquals(StoreOwner.DATASTORE, PersistenceStoreOwnership.owner("global_playback_speed"))
    }

    @Test fun `privacy redaction removes local details and URL secrets`() {
        assertEquals("redacted-source", PrivacyRedactor.source("content://private.provider/user/path/video.mp4"))
        assertEquals("redacted-source", PrivacyRedactor.source("https://example.test/video.mp4?token=secret"))
        assertTrue(PrivacyRedactor.metadata("Authorization: Bearer secret token=x").contains("[REDACTED]"))
    }

    @Test fun `privacy metadata removes auth query and local path details`() {
        val safe = PrivacyRedactor.metadata(
            "Authorization: Bearer top-secret\nurl=https://user:pass@example.test/video.mp4?access_token=secret#/private/path\npath=C:\\Users\\boss\\private.mp4"
        )
        assertTrue(!safe.contains("top-secret"))
        assertTrue(!safe.contains("access_token=secret"))
        assertTrue(!safe.contains("C:\\Users\\boss"))
        assertTrue(safe.contains("https://[REDACTED]@example.test/video.mp4"))
    }

    @Test fun `privacy metadata redacts unix local paths`() {
        val safe = PrivacyRedactor.metadata("path=/private/user/video.mp4")
        assertTrue(!safe.contains("/private/user/video.mp4"))
        assertTrue(safe.contains("[REDACTED-PATH]"))
    }

}