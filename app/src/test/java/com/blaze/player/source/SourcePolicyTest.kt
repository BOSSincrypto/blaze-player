package com.blaze.player.source

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class SourcePolicyTest {
    private class Access(private val readable: Boolean = true) : LocalSourceAccess {
        var persisted = 0
        var persistResult = true
        override fun canRead(uri: Uri) = readable
        override fun takePersistableReadPermission(uri: Uri): Boolean { persisted++; return persistResult }
    }

    @Test fun `local content is accepted and grant retained`() {
        val access = Access(); val result = SourceNormalizer.fromPicker(Uri.parse("content://provider/video/1"), access)
        assertTrue(result is SourceResult.Accepted); assertEquals(1, access.persisted)
        assertTrue((result as SourceResult.Accepted).source.persistable)
    }

    @Test fun `non persistable local grant is accepted without durable claim`() {
        val access = Access().also { it.persistResult = false }
        val result = SourceNormalizer.fromPicker(Uri.parse("content://provider/video/1"), access)
        assertTrue(result is SourceResult.Accepted)
        assertFalse((result as SourceResult.Accepted).source.persistable)
    }

    @Test fun `intent candidates converge to one accepted source`() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_TEXT, "https://example.test/a.mp4")
        assertTrue(SourceNormalizer.fromIntent(intent, Access()) is SourceResult.Accepted)
    }

    @Test fun `unsafe and adaptive urls are rejected`() {
        assertEquals(Reason.UNSUPPORTED_SCHEME, (SourceNormalizer.normalize(Uri.parse("file:///tmp/a.mp4"), Access()) as SourceResult.Rejected).reason)
        assertEquals(Reason.MALFORMED_URL, (SourceNormalizer.normalize(Uri.parse("https://example.test/%%%"), Access()) as SourceResult.Rejected).reason)
        assertEquals(Reason.ADAPTIVE_NOT_SUPPORTED, (SourceNormalizer.normalize(Uri.parse("https://example.test/live.m3u8"), Access()) as SourceResult.Rejected).reason)
        val credentialUrl = Uri.parse("https://user" + ":" + "pass@example.test/a.mp4")
        assertEquals(Reason.CREDENTIALS_NOT_ALLOWED, (SourceNormalizer.normalize(credentialUrl, Access()) as SourceResult.Rejected).reason)
        assertEquals(Reason.UNSUPPORTED_MEDIA, (SourceNormalizer.normalize(Uri.parse("https://example.test/movie.mp4?drm=widevine"), Access()) as SourceResult.Rejected).reason)
    }

    @Test fun `redirect policy blocks downgrade and credentials`() {
        val p = RedirectPolicy()
        assertTrue(p.allows(Uri.parse("http://a.test/x"), Uri.parse("https://b.test/y")))
        assertFalse(p.allows(Uri.parse("https://a.test/x"), Uri.parse("http://b.test/y")))
        assertFalse(p.allows(Uri.parse("https://a.test/x"), Uri.parse("https://u:p@b.test/y")))
    }
    @Test fun `network responses fail closed`() {
        assertEquals(NetworkFailure.AUTH_REQUIRED, NetworkResponsePolicy.classify(401, false))
        assertEquals(NetworkFailure.REDIRECT_REJECTED, NetworkResponsePolicy.classify(302, false))
        assertEquals(NetworkFailure.RANGE_UNSUPPORTED, NetworkResponsePolicy.classify(200, true, false))
        assertNull(NetworkResponsePolicy.classify(206, true, true))
        assertEquals(NetworkFailure.HTTP_ERROR, NetworkResponsePolicy.classify(500, false, false))
    }
}
