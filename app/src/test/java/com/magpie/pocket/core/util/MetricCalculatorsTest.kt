package com.magpie.pocket.core.util

import com.magpie.pocket.core.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricCalculatorsTest {

    @Test
    fun tokensWithoutDoubleReasoning() {
        val usage = TokenUsage(
            inputUncached = 100L,
            output = 50L,
            cacheRead = 800L,
            cacheWrite = 50L,
            reasoningIncludedInOutput = 20L
        )
        val total = MetricCalculators.calculateTotalTokens(usage)
        assertEquals(1000L, total)
    }

    @Test
    fun weightedCacheRate() {
        val usages = listOf(
            TokenUsage(
                inputUncached = 0L,
                output = 100L,
                cacheRead = 1000L,
                cacheWrite = 0L,
                reasoningIncludedInOutput = 0L
            ),
            TokenUsage(
                inputUncached = 9000L,
                output = 0L,
                cacheRead = 0L,
                cacheWrite = 0L,
                reasoningIncludedInOutput = 0L
            )
        )
        val rate = MetricCalculators.calculateWeightedCacheHitRate(usages)
        assertEquals(0.1, rate ?: 0.0, 0.0001)
    }

    @Test
    fun meanIsNotMedian() {
        val samples = listOf(1000L, 2000L, 9000L)
        val mean = MetricCalculators.calculateTtftMean(samples.sum(), samples.size.toLong())
        assertEquals(4000.0, mean ?: 0.0, 0.001)

        // Verify median would be 2000, but our function delivers mean (4000)
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        assertEquals(2000.0, median, 0.001)
        assertTrue(mean != median)
    }

    @Test
    fun unknownWindow() {
        val ratio = MetricCalculators.calculateWindowUsedRatio(214000L, null)
        assertNull(ratio)

        val zeroLimitRatio = MetricCalculators.calculateWindowUsedRatio(214000L, 0L)
        assertNull(zeroLimitRatio)
    }

    @Test
    fun zeroDenominatorSuccessRate() {
        val rate = MetricCalculators.calculateSuccessRate(0L, 0L)
        assertNull(rate)
    }

    @Test
    fun zeroDenominatorCacheHitRate() {
        val usage = TokenUsage(
            inputUncached = 0L,
            output = 0L,
            cacheRead = 0L,
            cacheWrite = 0L
        )
        val rate = MetricCalculators.calculateCacheHitRate(usage)
        assertNull(rate)
    }

    @Test
    fun successRateCalculation() {
        val rate = MetricCalculators.calculateSuccessRate(1728L, 4L)
        // 1724 / 1728 = 0.997685...
        assertEquals(0.997685, rate ?: 0.0, 0.0001)
    }

    @Test
    fun quotaWarningThreshold() {
        // 301 / 350 = 0.86 >= 0.85 -> warning
        assertTrue(MetricCalculators.isQuotaWarning(301L, 350L))
        // 100 / 350 = 0.285 < 0.85 -> no warning
        assertTrue(!MetricCalculators.isQuotaWarning(100L, 350L))
    }

    @Test
    fun maskAccountName() {
        assertEquals("账号 01", MetricCalculators.maskAccountName("账号 01", true))
        assertEquals("API key 01", MetricCalculators.maskAccountName("API key 01", true))
        assertEquals("ad***n@example.com", MetricCalculators.maskAccountName("admin@example.com", true))
        assertEquals("sec***key", MetricCalculators.maskAccountName("secret-token-key", true))
        assertEquals("raw@example.com", MetricCalculators.maskAccountName("raw@example.com", false))
    }
}
