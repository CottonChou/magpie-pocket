package com.magpie.pocket.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * RFC3339/legacy request timestamp handling that works on Android 7 (API 24)
 * without java.time desugaring. Parsing is strict: malformed input is shown as
 * an em dash rather than silently becoming "now" or a made-up local time.
 */
object RequestTimestamps {
    const val MISSING = "—"

    private val isoPattern = Regex(
        "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})" +
            "(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?(Z|[+-]\\d{2}:?\\d{2})?)?$",
        RegexOption.IGNORE_CASE
    )
    private val timeOnlyPattern = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?$")

    fun parseMillis(raw: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        timeOnlyPattern.matchEntire(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            val second = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
            val millis = fractionMillis(match.groupValues[4])
            return calendar(zone, 1970, 1, 1, hour, minute, second, millis)?.timeInMillis
        }

        val match = isoPattern.matchEntire(value) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hasTime = match.groupValues[4].isNotEmpty()
        val hour = match.groupValues[4].takeIf { hasTime }?.toIntOrNull() ?: 0
        val minute = match.groupValues[5].takeIf { hasTime }?.toIntOrNull() ?: 0
        val second = match.groupValues[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val millis = fractionMillis(match.groupValues[7])
        val offset = match.groupValues[8]

        if (month !in 1..12 || day !in 1..31) return null
        if (hasTime && (hour !in 0..23 || minute !in 0..59 || second !in 0..59)) return null

        val effectiveZone = if (offset.isNotEmpty()) zoneForOffset(offset) ?: return null else zone
        return calendar(effectiveZone, year, month, day, hour, minute, second, millis)?.timeInMillis
    }

    /** HH:mm:ss in the device timezone. Time-only strings are preserved as clock time. */
    fun formatTime(
        raw: String?,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val value = raw?.trim().orEmpty()
        timeOnlyPattern.matchEntire(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return MISSING
            val minute = match.groupValues[2].toIntOrNull() ?: return MISSING
            val second = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return MISSING
            return String.format(locale, "%02d:%02d:%02d", hour, minute, second)
        }
        val millis = parseMillis(value, zone) ?: return MISSING
        return SimpleDateFormat("HH:mm:ss", locale).apply { timeZone = zone }.format(Date(millis))
    }

    /**
     * Readable full local time with an explicit UTC offset and timezone id, e.g.
     * "2026-10-22 20:36:54 · UTC+08:00 · Asia/Shanghai".
     */
    fun formatFullLocal(
        raw: String?,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return MISSING
        if (timeOnlyPattern.matches(value)) {
            val time = formatTime(value, zone, locale)
            if (time == MISSING) return MISSING
            val offset = offsetLabel(System.currentTimeMillis(), zone)
            return "$time · $offset · ${zone.id}"
        }
        val millis = parseMillis(value, zone) ?: return MISSING
        val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale)
            .apply { timeZone = zone }
            .format(Date(millis))
        return "$formatted · ${offsetLabel(millis, zone)} · ${zone.id}"
    }

    fun offsetLabel(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val totalMinutes = zone.getOffset(millis) / 60_000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absolute = kotlin.math.abs(totalMinutes)
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, absolute / 60, absolute % 60)
    }

    /** Always emits a true UTC instant; never formats a local clock with a literal Z. */
    fun nowUtcIso(nowMillis: Long = System.currentTimeMillis()): String = formatUtcIso(nowMillis)

    fun formatUtcIso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun fractionMillis(raw: String): Int {
        if (raw.isEmpty()) return 0
        val padded = raw.take(3).padEnd(3, '0')
        return padded.toIntOrNull() ?: 0
    }

    private fun zoneForOffset(raw: String): TimeZone? {
        if (raw.equals("Z", ignoreCase = true)) return TimeZone.getTimeZone("UTC")
        if (raw.length !in 5..6) return null
        val sign = when (raw[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val digits = raw.drop(1).replace(":", "")
        if (digits.length != 4) return null
        val hours = digits.substring(0, 2).toIntOrNull() ?: return null
        val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
        if (hours !in 0..18 || minutes !in 0..59) return null
        val total = sign * (hours * 60 + minutes)
        return TimeZone.getTimeZone(
            String.format(Locale.US, "GMT%s%02d:%02d", if (total < 0) "-" else "+", kotlin.math.abs(total) / 60, kotlin.math.abs(total) % 60)
        )
    }

    private fun calendar(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millis: Int
    ): Calendar? = try {
        GregorianCalendar(zone).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }.also { it.timeInMillis }
    } catch (_: Exception) {
        null
    }
}
