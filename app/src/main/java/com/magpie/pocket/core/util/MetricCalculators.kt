package com.magpie.pocket.core.util

import com.magpie.pocket.core.model.QuotaWindow
import com.magpie.pocket.core.model.TokenUsage
import java.text.DecimalFormat
import java.util.Locale

object MetricCalculators {

    /**
     * Total tokens normalized: inputUncached + output + cacheRead + cacheWrite.
     * Note: reasoningIncludedInOutput is already part of output, must NOT be added again.
     */
    fun calculateTotalTokens(usage: TokenUsage?): Long {
        if (usage == null) return 0L
        val uncached = usage.inputUncached ?: 0L
        val out = usage.output ?: 0L
        val cRead = usage.cacheRead ?: 0L
        val cWrite = usage.cacheWrite ?: 0L
        return uncached + out + cRead + cWrite
    }

    /**
     * Cache hit rate: cacheRead / (inputUncached + cacheRead + cacheWrite).
     * If denominator is 0, returns null (cannot divide by zero).
     */
    fun calculateCacheHitRate(usage: TokenUsage?): Double? {
        if (usage == null) return null
        val uncached = usage.inputUncached ?: 0L
        val cRead = usage.cacheRead ?: 0L
        val cWrite = usage.cacheWrite ?: 0L
        val denominator = uncached + cRead + cWrite
        if (denominator <= 0L) return null
        return cRead.toDouble() / denominator.toDouble()
    }

    /**
     * Weighted cache hit rate across multiple requests/items.
     * Formula: sum(cacheRead) / sum(inputUncached + cacheRead + cacheWrite).
     */
    fun calculateWeightedCacheHitRate(usages: List<TokenUsage>): Double? {
        var totalRead = 0L
        var totalDenominator = 0L
        for (u in usages) {
            val uncached = u.inputUncached ?: 0L
            val cRead = u.cacheRead ?: 0L
            val cWrite = u.cacheWrite ?: 0L
            totalRead += cRead
            totalDenominator += (uncached + cRead + cWrite)
        }
        if (totalDenominator <= 0L) return null
        return totalRead.toDouble() / totalDenominator.toDouble()
    }

    /**
     * Success rate: (completed - finalErrors) / completed.
     * If completed == 0, returns null.
     */
    fun calculateSuccessRate(completed: Long, finalErrors: Long): Double? {
        if (completed <= 0L) return null
        val successCount = completed - finalErrors
        val rate = successCount.toDouble() / completed.toDouble()
        return rate.coerceIn(0.0, 1.0)
    }

    /**
     * Arithmetic mean for TTFT: ttftSumMs / ttftSampleCount.
     * Not median!
     */
    fun calculateTtftMean(ttftSumMs: Long?, ttftSampleCount: Long?): Double? {
        if (ttftSumMs == null || ttftSampleCount == null || ttftSampleCount <= 0L) {
            return null
        }
        return ttftSumMs.toDouble() / ttftSampleCount.toDouble()
    }

    /**
     * Context window used ratio: contextTokens / windowLimit.
     * If limit is null or <= 0, returns null.
     */
    fun calculateWindowUsedRatio(contextTokens: Long?, windowLimit: Long?): Double? {
        if (contextTokens == null || windowLimit == null || windowLimit <= 0L) {
            return null
        }
        return contextTokens.toDouble() / windowLimit.toDouble()
    }

    /**
     * Quota window used ratio: used / limit.
     */
    fun calculateQuotaUsedRatio(used: Long, limit: Long): Double? {
        if (limit <= 0L) return null
        return used.toDouble() / limit.toDouble()
    }

    /**
     * Quota ratio normalized across legacy absolute counts and upstream percentages.
     * Unknown or unlimited windows have no ratio rather than a fake full bar.
     */
    fun calculateQuotaUsedRatio(window: QuotaWindow): Double? {
        if (!window.known || window.unlimited) return null
        window.usedPercent?.let { return (it / 100.0).coerceAtLeast(0.0) }
        if (window.limit > 0L) return window.used.toDouble() / window.limit.toDouble()
        return null
    }

    /**
     * Check if quota is in warning zone (>= 85%).
     */
    fun isQuotaWarning(used: Long, limit: Long, threshold: Double = 0.85): Boolean {
        val ratio = calculateQuotaUsedRatio(used, limit) ?: return false
        return ratio >= threshold
    }

    fun isQuotaWarning(window: QuotaWindow, threshold: Double = 0.85): Boolean {
        val ratio = calculateQuotaUsedRatio(window) ?: return false
        return ratio >= threshold
    }

    /**
     * Format numbers into compact human-readable strings (e.g. 1.2K, 24.6M).
     */
    fun formatCompactNumber(value: Long): String {
        return when {
            value >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
            value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
            value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
            else -> value.toString()
        }
    }

    /**
     * Format milliseconds to seconds string with 1 decimal if >= 1000ms.
     */
    fun formatDurationMs(durationMs: Long?): String {
        if (durationMs == null) return "—"
        return if (durationMs >= 1000) {
            val sec = durationMs / 1000.0
            String.format(Locale.US, "%.1fs", sec)
        } else {
            "${durationMs}ms"
        }
    }

    /**
     * Format percentage (e.g. 99.8%).
     */
    fun formatPercent(rate: Double?): String {
        if (rate == null) return "—"
        return String.format(Locale.US, "%.1f%%", rate * 100.0)
    }

    /**
     * Mask account name for privacy if masking is enabled.
     */
    fun maskAccountName(name: String, maskEnabled: Boolean = true): String {
        if (!maskEnabled || name.isBlank()) return name
        // If already an alias like "账号 01" or "API key 01", keep it
        if (name.startsWith("账号 ") || name.startsWith("API key ")) return name

        // If email
        if (name.contains("@")) {
            val parts = name.split("@")
            val user = parts[0]
            val domain = parts.getOrNull(1) ?: ""
            val maskedUser = if (user.length <= 3) {
                user.first() + "***"
            } else {
                user.take(2) + "***" + user.takeLast(1)
            }
            return "$maskedUser@$domain"
        }

        // Generic key or string
        return if (name.length <= 6) {
            "***"
        } else {
            name.take(3) + "***" + name.takeLast(3)
        }
    }
}
