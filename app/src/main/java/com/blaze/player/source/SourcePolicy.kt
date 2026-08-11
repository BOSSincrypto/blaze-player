package com.blaze.player.source

import android.content.Intent
import android.content.ContentResolver
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.net.Uri
import androidx.media3.common.MediaItem
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed interface SourceResult {
    data class Accepted(val mediaItem: MediaItem, val source: NormalizedSource) : SourceResult
    data class Rejected(val reason: Reason) : SourceResult
}

enum class Reason {
    EMPTY, UNSUPPORTED_SCHEME, MALFORMED_URL, CREDENTIALS_NOT_ALLOWED,
    ADAPTIVE_NOT_SUPPORTED, NOT_READABLE, UNSUPPORTED_MEDIA
}

/** Durable source record. Identity is the canonical string, not a transient Uri instance. */
data class NormalizedSource(
    val uri: Uri,
    val local: Boolean,
    val persistable: Boolean = false,
    val identity: String = uri.toString(),
    val cleartextMediaAllowed: Boolean = CleartextMediaPolicy.allows(uri, SourceOrigin.USER_ENTERED)
)

/** Provenance required before a cleartext media request may be attempted. */
enum class SourceOrigin { USER_SELECTED, USER_ENTERED, INTERNAL }

/**
 * Keeps the HTTP exception narrow. This is deliberately separate from the
 * application network config, which remains deny-by-default for all traffic.
 */
object CleartextMediaPolicy {
    fun allows(uri: Uri, origin: SourceOrigin): Boolean = when (uri.scheme?.lowercase()) {
        "https" -> true
        "http" -> origin == SourceOrigin.USER_SELECTED || origin == SourceOrigin.USER_ENTERED
        else -> false
    }
}

interface LocalSourceAccess {
    fun canRead(uri: Uri): Boolean
    fun takePersistableReadPermission(uri: Uri): Boolean

    /** Opens the source at preparation time, allowing revocation to fail safely. */
    fun openForPlayback(uri: Uri): Boolean = canRead(uri)

    /** Attempts persistence only when the originating intent advertised that grant. */
    fun takePersistableReadPermission(uri: Uri, grantFlags: Int): Boolean {
        if (grantFlags and FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            grantFlags and FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
        return takePersistableReadPermission(uri)
    }
}

/** ContentResolver adapter shared by picker intake and playback preparation. */
class ContentResolverSourceAccess(private val resolver: ContentResolver) : LocalSourceAccess {
    override fun canRead(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    override fun openForPlayback(uri: Uri): Boolean = canRead(uri)

    override fun takePersistableReadPermission(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)
}

object SourceNormalizer {
    private val drmMarkers = setOf(
        "drm", "widevine", "playready", "fairplay", "clearkey", "keysystem",
        "license", "licenseurl", "licenseuri", "keyid", "contentprotection",
        "encrypted", "encryption", "cenc", "cbcs", "pssh"
    )

    fun fromPicker(uri: Uri?, access: LocalSourceAccess, grantFlags: Int = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION): SourceResult =
        normalizeLocal(uri, access, grantFlags)

    /** Rehydrates a durable source identity after process recreation. */
    fun reopen(source: NormalizedSource, access: LocalSourceAccess): SourceResult {
        return try {
            if (!source.local || !source.persistable) return SourceResult.Rejected(Reason.NOT_READABLE)
            val uri = Uri.parse(source.identity)
            if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return SourceResult.Rejected(Reason.EMPTY)
            if (!access.openForPlayback(uri)) {
                return SourceResult.Rejected(Reason.NOT_READABLE)
            }
            SourceResult.Accepted(MediaItem.fromUri(uri), source.copy(uri = uri))
        } catch (_: RuntimeException) {
            // Providers may throw when a persisted grant has been revoked.
            SourceResult.Rejected(Reason.NOT_READABLE)
        }
    }

    fun fromIntent(intent: Intent, access: LocalSourceAccess): SourceResult {
        // Deterministic precedence: a typed stream URI, then data URI, then SEND text.
        // De-duplication matters because some share targets populate both data and text.
        val candidates = linkedSetOf<Uri>()
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(candidates::add)
        intent.data?.let(candidates::add)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.let(candidates::add)
        if (candidates.isEmpty()) return SourceResult.Rejected(Reason.EMPTY)
        return candidates.asSequence().map { normalize(it, access, intent.flags, emptyMap()) }.firstOrNull { it is SourceResult.Accepted }
            ?: (normalize(candidates.first(), access, intent.flags, emptyMap()) as SourceResult.Rejected)
    }

    fun normalize(uri: Uri?, access: LocalSourceAccess): SourceResult {
        return normalize(uri, access, 0, emptyMap(), SourceOrigin.USER_ENTERED)
    }

    /** Normalizes an external URI and optional source metadata through the same policy. */
    fun normalize(uri: Uri?, access: LocalSourceAccess, metadata: Map<String, String>): SourceResult {
        return normalize(uri, access, 0, metadata, SourceOrigin.USER_ENTERED)
    }

    fun normalize(uri: Uri?, access: LocalSourceAccess, grantFlags: Int, metadata: Map<String, String> = emptyMap(), origin: SourceOrigin = SourceOrigin.USER_ENTERED): SourceResult {
        if (uri == null || uri.toString().isBlank()) return SourceResult.Rejected(Reason.EMPTY)
        return when (uri.scheme?.lowercase()) {
            "content" -> normalizeLocal(uri, access, grantFlags)
            "http", "https" -> normalizeRemote(uri, metadata, origin)
            else -> SourceResult.Rejected(Reason.UNSUPPORTED_SCHEME)
        }
    }

    private fun normalizeLocal(uri: Uri?, access: LocalSourceAccess, grantFlags: Int = 0): SourceResult {
        if (uri?.scheme?.lowercase() != "content" || uri.authority.isNullOrBlank()) {
            return SourceResult.Rejected(Reason.EMPTY)
        }
        val readable = runCatching { access.canRead(uri) }.getOrDefault(false)
        if (!readable) return SourceResult.Rejected(Reason.NOT_READABLE)
        // Providers are allowed to return a non-persistable grant. Playback remains
        // valid for this handoff, but we must not claim durable reopen in that case.
        val persistable = runCatching { access.takePersistableReadPermission(uri, grantFlags) }.getOrDefault(false)
        return SourceResult.Accepted(MediaItem.fromUri(uri), NormalizedSource(uri, local = true, persistable = persistable))
    }

    private fun normalizeRemote(uri: Uri, metadata: Map<String, String>, origin: SourceOrigin): SourceResult {
        val raw = uri.toString()
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return SourceResult.Rejected(Reason.MALFORMED_URL)
        if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
            return SourceResult.Rejected(Reason.MALFORMED_URL)
        }
        if (parsed.userInfo != null) return SourceResult.Rejected(Reason.CREDENTIALS_NOT_ALLOWED)
        val path = parsed.path.orEmpty().lowercase()
        if (containsDrmMarker(parsed.rawQuery) || containsDrmMarker(parsed.rawFragment) ||
            metadata.any { containsDrmMarker(it.key) || containsDrmMarker(it.value) }) {
            return SourceResult.Rejected(Reason.UNSUPPORTED_MEDIA)
        }
        if (path.endsWithAny(".m3u8", ".mpd") || parsed.query.orEmpty().contains("manifest", true)) {
            return SourceResult.Rejected(Reason.ADAPTIVE_NOT_SUPPORTED)
        }
        return SourceResult.Accepted(MediaItem.fromUri(uri), NormalizedSource(uri, local = false, cleartextMediaAllowed = CleartextMediaPolicy.allows(uri, origin)))
    }

    private fun containsDrmMarker(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        // Query/fragment values are commonly percent-encoded (including nested
        // license URLs). Inspect the decoded representation so encoding cannot
        // turn an explicitly unsupported DRM source into progressive media.
        val decoded = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
        return decoded.split('&', ';', '=', '?', '/', ':', ',', '#')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .any { token ->
                val compactToken = token.filter(Char::isLetterOrDigit)
                drmMarkers.any { marker -> compactToken.equals(marker, ignoreCase = true) }
            }
    }

    private fun String.endsWithAny(vararg suffixes: String) = suffixes.any { endsWith(it) }
}

data class RedirectPolicy(val maxRedirects: Int = 5) {
    fun allows(chain: List<Uri>): Boolean = chain.size >= 2 && chain.size - 1 <= maxRedirects &&
        chain.zipWithNext().all { (from, to) -> allows(from, to) }

    fun allows(from: Uri, to: Uri): Boolean {
        if (maxRedirects < 1 || to.userInfo != null || to.host.isNullOrBlank()) return false
        val fromScheme = from.scheme?.lowercase(); val toScheme = to.scheme?.lowercase()
        return (fromScheme == "http" || fromScheme == "https") &&
            (toScheme == fromScheme || (fromScheme == "http" && toScheme == "https"))
    }
}

enum class NetworkFailure { RANGE_UNSUPPORTED, AUTH_REQUIRED, REDIRECT_REJECTED, HTTP_ERROR }

object NetworkResponsePolicy {
    fun classify(status: Int, rangeRequested: Boolean, rangeSupported: Boolean = true): NetworkFailure? = when {
        status == 401 || status == 403 -> NetworkFailure.AUTH_REQUIRED
        status in 300..399 -> NetworkFailure.REDIRECT_REJECTED
        status !in 200..299 -> NetworkFailure.HTTP_ERROR
        rangeRequested && !rangeSupported -> NetworkFailure.RANGE_UNSUPPORTED
        else -> null
    }
}
