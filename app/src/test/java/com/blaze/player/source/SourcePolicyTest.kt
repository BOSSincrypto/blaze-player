package com.blaze.player.source

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class SourcePolicyTest {
    private class Access(private val readable: Boolean = true) : LocalSourceAccess {
        var persisted = 0
        var opened = 0
        var persistResult = true
        override fun canRead(uri: Uri) = readable
        override fun takePersistableReadPermission(uri: Uri): Boolean { persisted++; return persistResult }
        override fun openForPlayback(uri: Uri): Boolean { opened++; return readable }
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

    @Test fun `persisted local identity and media id survive recreation`() {
        val uri = Uri.parse("content://provider/video/1")
        val first = SourceNormalizer.fromPicker(uri, Access()) as SourceResult.Accepted
        val recreated = SourceNormalizer.reopen(first.source, Access()) as SourceResult.Accepted
        assertEquals(first.source.identity, recreated.source.identity)
        assertEquals(first.mediaItem.mediaId, recreated.mediaItem.mediaId)
        assertEquals(uri.toString(), recreated.source.identity)
    }

    @Test fun `revoked persisted grant fails safely during playback preparation`() {
        val uri = Uri.parse("content://provider/video/1")
        val source = (SourceNormalizer.fromPicker(uri, Access()) as SourceResult.Accepted).source
        val revoked = Access(readable = false)
        val result = SourceNormalizer.reopen(source, revoked)
        assertEquals(Reason.NOT_READABLE, (result as SourceResult.Rejected).reason)
        assertEquals(1, revoked.opened)
    }

    @Test fun `missing grant never claims durable reopen`() {
        val uri = Uri.parse("content://provider/video/1")
        val source = (SourceNormalizer.fromPicker(uri, Access().also { it.persistResult = false }) as SourceResult.Accepted).source
        assertFalse(source.persistable)
        assertEquals(Reason.NOT_READABLE, (SourceNormalizer.reopen(source, Access(false)) as SourceResult.Rejected).reason)
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

    @Test fun `drm markers in query and fragment are rejected`() {
        val urls = listOf(
            "https://example.test/movie.mp4?license=https%3A%2F%2Flicense.test",
            "https://example.test/movie.mp4?widevine=true",
            "https://example.test/movie.mp4#playready",
            "https://example.test/movie.mp4#track=encrypted&keySystem=clearkey"
        )
        urls.forEach { url ->
            assertEquals(Reason.UNSUPPORTED_MEDIA, (SourceNormalizer.normalize(Uri.parse(url), Access()) as SourceResult.Rejected).reason)
        }
    }

    @Test fun `drm metadata is rejected while ordinary metadata remains progressive`() {
        val uri = Uri.parse("https://example.test/movie.mp4")
        assertEquals(
            Reason.UNSUPPORTED_MEDIA,
            (SourceNormalizer.normalize(uri, Access(), mapOf("contentProtection" to "cenc")) as SourceResult.Rejected).reason
        )
        assertTrue(SourceNormalizer.normalize(uri, Access(), mapOf("title" to "A movie")) is SourceResult.Accepted)
    }

    @Test fun `encoded drm query fragment and metadata are rejected`() {
        val encodedQuery = Uri.parse("https://example.test/movie.mp4?token=drm%3Dwidevine")
        val encodedFragment = Uri.parse("https://example.test/movie.mp4#keySystem%3Dplayready")
        assertEquals(Reason.UNSUPPORTED_MEDIA, (SourceNormalizer.normalize(encodedQuery, Access()) as SourceResult.Rejected).reason)
        assertEquals(Reason.UNSUPPORTED_MEDIA, (SourceNormalizer.normalize(encodedFragment, Access()) as SourceResult.Rejected).reason)
        assertEquals(
            Reason.UNSUPPORTED_MEDIA,
            (SourceNormalizer.normalize(Uri.parse("https://example.test/movie.mp4"), Access(), mapOf("streamInfo" to "content%5Fprotection=cenc")) as SourceResult.Rejected).reason
        )
    }

    @Test fun `ordinary encoded progressive query remains accepted`() {
        assertTrue(SourceNormalizer.normalize(Uri.parse("https://example.test/movie.mp4?title=summer%20movie"), Access()) is SourceResult.Accepted)
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

    @Test fun `cleartext is limited to user media and never becomes global trust`() {
        val http = Uri.parse("http://example.test/video.mp4")
        val https = Uri.parse("https://example.test/video.mp4")
        assertTrue(CleartextMediaPolicy.allows(http, SourceOrigin.USER_SELECTED))
        assertTrue(CleartextMediaPolicy.allows(http, SourceOrigin.USER_ENTERED))
        assertFalse(CleartextMediaPolicy.allows(http, SourceOrigin.INTERNAL))
        assertTrue(CleartextMediaPolicy.allows(https, SourceOrigin.INTERNAL))
        assertFalse(CleartextMediaPolicy.allows(Uri.parse("ftp://example.test/video.mp4"), SourceOrigin.USER_ENTERED))
        assertFalse((SourceNormalizer.normalize(http, Access(), 0, emptyMap(), SourceOrigin.INTERNAL) as SourceResult.Accepted).source.cleartextMediaAllowed)
    }
}
