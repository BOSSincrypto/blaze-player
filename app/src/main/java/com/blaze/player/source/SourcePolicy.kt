package com.blaze.player.source

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import java.net.URI

sealed interface SourceResult {
    data class Accepted(val mediaItem: MediaItem, val source: NormalizedSource) : SourceResult
    data class Rejected(val reason: Reason) : SourceResult
}

enum class Reason {
    EMPTY, UNSUPPORTED_SCHEME, MALFORMED_URL, CREDENTIALS_NOT_ALLOWED,
    ADAPTIVE_NOT_SUPPORTED, NOT_READABLE, UNSUPPORTED_MEDIA
}

data class NormalizedSource(val uri: Uri, val local: Boolean, val persistable: Boolean = false)

interface LocalSourceAccess {
    fun canRead(uri: Uri): Boolean
    fun takePersistableReadPermission(uri: Uri): Boolean
}

object SourceNormalizer {
    fun fromPicker(uri: Uri?, access: LocalSourceAccess): SourceResult = normalizeLocal(uri, access)

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
        return candidates.asSequence().map { normalize(it, access) }.firstOrNull { it is SourceResult.Accepted }
            ?: (normalize(candidates.first(), access) as SourceResult.Rejected)
    }

    fun normalize(uri: Uri?, access: LocalSourceAccess): SourceResult {
        if (uri == null || uri.toString().isBlank()) return SourceResult.Rejected(Reason.EMPTY)
        return when (uri.scheme?.lowercase()) {
            "content" -> normalizeLocal(uri, access)
            "http", "https" -> normalizeRemote(uri)
            else -> SourceResult.Rejected(Reason.UNSUPPORTED_SCHEME)
        }
    }

    private fun normalizeLocal(uri: Uri?, access: LocalSourceAccess): SourceResult {
        if (uri?.scheme?.lowercase() != "content" || uri.authority.isNullOrBlank()) {
            return SourceResult.Rejected(Reason.EMPTY)
        }
        val readable = runCatching { access.canRead(uri) }.getOrDefault(false)
        if (!readable) return SourceResult.Rejected(Reason.NOT_READABLE)
        // Providers are allowed to return a non-persistable grant. Playback remains
        // valid for this handoff, but we must not claim durable reopen in that case.
        val persistable = runCatching { access.takePersistableReadPermission(uri) }.getOrDefault(false)
        return SourceResult.Accepted(MediaItem.fromUri(uri), NormalizedSource(uri, local = true, persistable = persistable))
    }

    private fun normalizeRemote(uri: Uri): SourceResult {
        val raw = uri.toString()
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return SourceResult.Rejected(Reason.MALFORMED_URL)
        if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
            return SourceResult.Rejected(Reason.MALFORMED_URL)
        }
        if (parsed.userInfo != null) return SourceResult.Rejected(Reason.CREDENTIALS_NOT_ALLOWED)
        val path = parsed.path.orEmpty().lowercase()
        if (path.endsWithAny(".m3u8", ".mpd") || parsed.query.orEmpty().contains("manifest", true)) {
            return SourceResult.Rejected(Reason.ADAPTIVE_NOT_SUPPORTED)
        }
        if (parsed.fragment.orEmpty().contains("drm", ignoreCase = true)) return SourceResult.Rejected(Reason.UNSUPPORTED_MEDIA)
        return SourceResult.Accepted(MediaItem.fromUri(uri), NormalizedSource(uri, local = false))
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
