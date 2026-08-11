package com.blaze.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceInstrumentationTest {
    @Test fun `records dispatch acknowledgement frame and HTTP stages independently`() {
        val metrics = PerformanceInstrumentation()
        val fixtureUrl = listOf("https", "example.test", "video.mp4").joinToString("/", ".", "://") + "?query-value=placeholder"
        PerformanceStage.entries.forEachIndexed { index, stage -> metrics.record(stage, index.toLong(), fixtureUrl) }

        assertEquals(PerformanceStage.entries.toList(), metrics.snapshot().map { it.stage })
        assertEquals("https://example.test/video.mp4", metrics.snapshot().first().source)
        assertTrue(metrics.snapshot().none { it.source.contains("query-value") })
    }

    @Test fun `report encodes warm targets but leaves device measurements unverified`() {
        val metrics = PerformanceInstrumentation()
        metrics.record(PerformanceStage.DISPATCH, 10)
        metrics.record(PerformanceStage.DISPATCH, 60)
        val report = metrics.report()

        assertEquals(50L, report.targets.warmPausePlayP95Ms)
        assertEquals(50L, report.targets.warmLocalSeekP95Ms)
        assertEquals(16L, report.targets.uiFrameP95Ms)
        assertEquals(60L, report.p95MsByStage[PerformanceStage.DISPATCH])
        assertNull(report.deviceMeasurements)
        assertEquals("unverified", report.deviceMeasurementsStatus)
    }

    @Test fun `redacts local sources credentials query and fragments`() {
        assertEquals("content://redacted", PerformanceInstrumentation.redactSource("content://local/video.mp4"))
        val safeUrl = listOf("https", "example.test", "a.mp4").joinToString("://", ".", "")
        val sourceWithSecrets = safeUrl + "?query-value=placeholder#fragment"
        assertEquals(safeUrl, PerformanceInstrumentation.redactSource(sourceWithSecrets))
        assertEquals("redacted-source", PerformanceInstrumentation.redactSource("not a uri"))
    }
}
