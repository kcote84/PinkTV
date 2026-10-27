package ca.pinktv

import org.json.JSONArray
import org.json.JSONObject

data class WatchProgress(val entry: Entry, val positionMs: Long, val durationMs: Long, val completed: Boolean,
    val updatedAt: Long, val next: Entry? = null, val hidden: Boolean = false) {
    val resumable get() = !completed && positionMs >= 5000
    fun seriesEntry() = Entry(entry.seriesId, entry.seriesName.ifBlank { entry.name }, entry.category, "series",
        adult = entry.adult, poster = entry.seriesPoster.ifBlank { entry.poster })
}

object WatchHistory {
    const val LIMIT = 200
    fun completed(position: Long, duration: Long, ended: Boolean = false) = ended ||
        duration > 0 && position >= duration - minOf(90000L, duration / 20)
    fun update(records: List<WatchProgress>, progress: WatchProgress): List<WatchProgress> =
        (records.filterNot { it.entry.key == progress.entry.key } + progress).sortedByDescending { it.updatedAt }.take(LIMIT)
    fun encode(records: List<WatchProgress>): String = JSONArray().apply { records.take(LIMIT).forEach { p ->
        put(JSONObject().put("entry", EntryJson.write(p.entry)).put("position", p.positionMs.coerceAtLeast(0))
            .put("duration", p.durationMs.coerceAtLeast(0)).put("completed", p.completed).put("updated", p.updatedAt).put("hidden", p.hidden)
            .apply { p.next?.let { put("next", EntryJson.write(it)) } })
    } }.toString()
    fun decode(raw: String): List<WatchProgress> = runCatching {
        val array = JSONArray(raw)
        (0 until minOf(array.length(), LIMIT)).mapNotNull { index -> runCatching {
            val o = array.getJSONObject(index); val entry = EntryJson.read(o.getJSONObject("entry"))
            require(entry.kind in listOf("vod", "episode") && entry.id.matches(Regex("\\d+")))
            WatchProgress(entry, o.optLong("position").coerceAtLeast(0), o.optLong("duration").coerceAtLeast(0),
                o.optBoolean("completed"), o.optLong("updated"), runCatching { o.optJSONObject("next")?.let { EntryJson.read(it) } }.getOrNull()
                    ?.takeIf { it.kind == "episode" && it.id.matches(Regex("\\d+")) && it.seriesId.isNotEmpty() && it.seriesId == entry.seriesId && it.key != entry.key }, o.optBoolean("hidden"))
        }.getOrNull() }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())
    fun continuing(records: List<WatchProgress>, kind: String): List<WatchProgress> {
        val recent = records.sortedByDescending { it.updatedAt }
        return if(kind == "vod") recent.filter { it.entry.kind == "vod" && it.resumable && !it.hidden }
        else recent.filter { it.entry.kind == "episode" && it.entry.seriesId.isNotEmpty() }.distinctBy { it.entry.seriesId }
            .filter { !it.hidden && (it.resumable || it.completed && it.next != null) }
    }
    fun recommend(details: MediaDetails, records: List<WatchProgress>): Episode? {
        val episodes = details.episodes
        val watched = records.filter { it.completed }.map { it.entry.key }.toSet()
        val latest = records.filter { it.entry.seriesId == details.entry.id }.maxByOrNull { it.updatedAt }
        val index = episodes.indexOfFirst { it.entry.key == latest?.entry?.key }
        if(index >= 0) {
            if(latest?.completed != true) return episodes[index]
            episodes.drop(index + 1).firstOrNull { it.entry.key !in watched }?.let { return it }
        }
        return episodes.firstOrNull { it.entry.season > 0 && it.entry.key !in watched }
            ?: episodes.firstOrNull { it.entry.key !in watched } ?: episodes.firstOrNull { it.entry.season > 0 } ?: episodes.firstOrNull()
    }
    fun stamp(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) / 1000)
        val minutes = seconds / 60
        return if(minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else if(minutes > 0) "$minutes min" else "$seconds s"
    }
    fun badge(p: WatchProgress) = if(p.completed) "✓ Vu" else if(p.resumable) "En cours · ${stamp(p.positionMs)}" else ""
}
