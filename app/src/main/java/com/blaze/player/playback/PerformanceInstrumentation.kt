package com.blaze.player.playback

import java.net.URI
import java.util.Locale

/** Lifecycle stages are intentionally separate so command latency is not confused with frame/network latency. */
enum class PerformanceStage {
    DISPATCH,
    POSITION_ACKNOWLEDGEMENT,
    FIRST_RENDERED_FRAME,
    HTTP_REQUEST,
    HTTP_BUFFER,
    HTTP_SEEK
}

data class PerformanceEvent(
    val stage: PerformanceStage,
    val elapsedMs: Long,
    val source: String
)

data class PerformanceTargets(
    val warmPausePlayP95Ms: Long = 50,
    val warmLocalSeekP95Ms: Long = 50,
    val uiFrameP95Ms: Long = 16
)

data class PerformanceReport(
    val targets: PerformanceTargets,
    val p95MsByStage: Map<PerformanceStage, Long>,
    val deviceMeasurements: Map<PerformanceStage, Long>?,
    val deviceMeasurementsStatus: String = "unverified"
)

/** In-memory, bounded instrumentation suitable for Macrobenchmark/Perfetto adapters. */
class PerformanceInstrumentation(
    private val targets: PerformanceTargets = PerformanceTargets(),
    private val maxEvents: Int = 1_000
) {
    private val events = ArrayDeque<PerformanceEvent>()

    fun record(stage: PerformanceStage, elapsedMs: Long, source: String? = null) {
        require(elapsedMs >= 0) { "elapsedMs must be non-negative" }
        if (events.size == maxEvents) events.removeFirst()
        events.addLast(PerformanceEvent(stage, elapsedMs, redactSource(source)))
    }

    /** Records an instantaneous runtime boundary without exposing source details. */
    fun boundary(stage: PerformanceStage, source: String? = null) = record(stage, 0L, source)

    fun snapshot(): List<PerformanceEvent> = events.toList()

    fun report(): PerformanceReport = PerformanceReport(
        targets = targets,
        p95MsByStage = PerformanceStage.entries.associateWith { stage ->
            percentile(events.filter { it.stage == stage }.map { it.elapsedMs }, 0.95)
        }.filterValues { it != null }.mapValues { it.value!! },
        // Static/JVM harnesses must not masquerade as physical-device measurements.
        deviceMeasurements = null
    )

    private fun percentile(values: List<Long>, percentile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (kotlin.math.ceil(sorted.size * percentile).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    companion object {
        fun redactSource(source: String?): String = source?.let {
            runCatching {
                val uri = URI(it)
                when (uri.scheme?.lowercase(Locale.US)) {
                    "http", "https" -> buildString {
                        append(uri.scheme.lowercase(Locale.US)).append("://")
                        append(uri.host ?: "redacted-host")
                        uri.rawPath?.takeIf(String::isNotEmpty)?.let(::append)
                    }
                    "content" -> "content://redacted"
                    else -> "redacted-source"
                }
            }.getOrDefault("redacted-source")
        } ?: "redacted-source"
    }
}
