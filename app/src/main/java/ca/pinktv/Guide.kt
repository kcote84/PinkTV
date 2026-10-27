package ca.pinktv

import org.json.JSONObject
import java.util.Base64
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class Programme(val title: String, val description: String, val start: Long, val end: Long) {
    fun isNow(now: Long) = now in start until end
}

object Guide {
    private fun decode(value: String): String = runCatching {
        val decoded = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        if ('\uFFFD' in decoded || decoded.any { it.isISOControl() && it !in "\n\r\t" }) value else decoded
    }.getOrDefault(value)

    private fun time(row: JSONObject, keys: List<String>, dateKey: String, zone: ZoneId?): Long {
        keys.forEach { key ->
            val n = row.optString(key).toLongOrNull() ?: 0
            if (n in 1..253402300799L) return n * 1000
            if (n in 1000000000000L..253402300799999L) return n
        }
        val date = row.optString(dateKey).replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()
            ?: zone?.let { runCatching { LocalDateTime.parse(date).atZone(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0
    }
    fun parse(raw: String, zone: ZoneId? = null): List<Programme> {
        val rows = JSONObject(raw).optJSONArray("epg_listings") ?: return emptyList()
        return (0 until minOf(rows.length(), 4000)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val start = time(row, listOf("start_timestamp"), "start", zone)
            val end = time(row, listOf("stop_timestamp", "end_timestamp"), "end", zone)
            if (start <= 0 || end <= start) null else Programme(
                decode(row.optString("title")).ifBlank { "Émission sans titre" }.take(160),
                decode(row.optString("description")).take(2000), start, end
            )
        }.distinctBy { it.start to it.title }.sortedBy { it.start }
    }
}

data class GuideSlot(val start: Long, val end: Long, val programme: Programme?)

/** Half-open slots keep gaps honest and prevent overlapping provider entries from covering each other. */
object GuideWindow {
    const val HALF_HOUR = 30 * 60 * 1000L
    const val SPAN = 4 * HALF_HOUR
    fun floor(time: Long) = time / HALF_HOUR * HALF_HOUR
    fun slots(programmes: List<Programme>, start: Long, end: Long): List<GuideSlot> {
        if (end <= start) return emptyList()
        val result = mutableListOf<GuideSlot>()
        var cursor = start
        programmes.sortedBy { it.start }.forEach { p ->
            val a = maxOf(cursor, p.start); val b = minOf(end, p.end)
            if (b > a) {
                if (a > cursor) result.add(GuideSlot(cursor, a, null))
                result.add(GuideSlot(a, b, p)); cursor = b
            }
        }
        if (cursor < end) result.add(GuideSlot(cursor, end, null))
        return result
    }
    fun at(slots: List<GuideSlot>, time: Long): GuideSlot? = slots.firstOrNull { time >= it.start && time < it.end }
}
