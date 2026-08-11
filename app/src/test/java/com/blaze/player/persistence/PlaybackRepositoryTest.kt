package com.blaze.player.persistence

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRepositoryTest {
    @Test fun `canonical identity normalizes remote host and default port`() {
        assertEquals(
            "https://example.test/video.mp4?token=x#part",
            SourceIdentity.canonical(Uri.parse("HTTPS://EXAMPLE.TEST:443/video.mp4?token=x#part"))
        )
    }

    @Test fun `content identity remains exact and distinct sources do not collide`() {
        assertEquals("content://provider/item/1", SourceIdentity.canonical(Uri.parse("content://provider/item/1")))
        assertEquals("content://provider/item/2", SourceIdentity.canonical(Uri.parse("content://provider/item/2")))
    }

    private class FakeRuntime(private var ids: List<String>) : RuntimePlaylist {
        var replacements = 0
        override fun mediaIds() = ids
        override fun replaceMediaIds(ids: List<String>) { replacements++; this.ids = ids }
    }

    @Test fun `reconciliation uses Room order and removes duplicate runtime ids`() {
        val runtime = FakeRuntime(listOf("stale", "a", "a"))
        RuntimePlaylistReconciler().reconcile(listOf("a", "b", "a"), runtime)
        assertEquals(listOf("a", "b"), runtime.mediaIds())
        assertEquals(1, runtime.replacements)
    }

    @Test fun `reconciliation is idempotent when runtime already matches authoritative order`() {
        val runtime = FakeRuntime(listOf("a", "b"))
        RuntimePlaylistReconciler().reconcile(listOf("a", "b"), runtime)
        assertEquals(0, runtime.replacements)
    }
}
