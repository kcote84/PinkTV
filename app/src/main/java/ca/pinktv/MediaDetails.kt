package ca.pinktv

import org.json.JSONArray
import org.json.JSONObject

data class Episode(val entry: Entry, val title: String, val plot: String = "", val durationMs: Long = 0)
data class Season(val id: String, val number: Int, val episodes: List<Episode>) {
    val title get() = if(number == 0) "Spéciaux" else "Saison $number"
}
data class MediaDetails(val entry: Entry, val plot: String = "", val year: String = "", val durationMs: Long = 0,
    val genre: String = "", val cast: String = "", val director: String = "", val rating: String = "",
    val seasons: List<Season> = emptyList(), val unavailable: Boolean = false) {
    val episodes get() = seasons.flatMap { it.episodes }
}

object MediaDetailsParser {
    private fun clean(value: String) = value.trim().takeUnless { it.equals("null", true) }.orEmpty()
    private fun value(o: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key -> clean(o.optString(key)).takeIf { it.isNotEmpty() } }.orEmpty()
    fun duration(o: JSONObject): Long {
        val seconds = value(o, "duration_secs").toDoubleOrNull()
        if(seconds != null && seconds.isFinite() && seconds in 1.0..604800.0) return (seconds * 1000).toLong()
        val parts = value(o, "duration").split(':').map { it.toLongOrNull() }
        if(parts.size in 2..3 && parts.all { it != null && it in 0..10080 } && parts.last()!! < 60 && (parts.size != 3 || parts[1]!! < 60)) {
            val total = parts.fold(0L) { acc, part -> acc * 60 + part!! }
            if(total in 1..604800) return total * 1000
        }
        return 0
    }
    fun parse(raw: JSONObject, original: Entry, base: String): MediaDetails {
        val info = raw.optJSONObject("info") ?: JSONObject()
        val movie = raw.optJSONObject("movie_data") ?: JSONObject()
        val poster = ArtworkRules.url(value(info, "cover_big", "movie_image", "cover"), base).ifBlank { original.poster }
        val entry = original.copy(poster = poster, extension = CatalogRules.safeExtension(movie.optString("container_extension"), original.extension))
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(value(info, "year", "releasedate", "releaseDate", "release_date"))?.value.orEmpty()
        val seasons = if(entry.kind != "series") emptyList() else {
            val all = raw.optJSONObject("episodes") ?: JSONObject()
            val seen = mutableSetOf<String>()
            all.keys().asSequence().toList().sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }).map { id ->
                val rows = all.optJSONArray(id) ?: JSONArray()
                val number = (id.toIntOrNull() ?: rows.optJSONObject(0)?.optInt("season") ?: 0).coerceAtLeast(0)
                val episodes = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .sortedWith(compareBy<JSONObject> { it.optInt("episode_num", Int.MAX_VALUE) }.thenBy { it.optString("id") })
                    .mapIndexedNotNull { index, row ->
                        val episodeId = row.optString("id")
                        if(!episodeId.matches(Regex("\\d+")) || !seen.add(episodeId)) return@mapIndexedNotNull null
                        val episodeNumber = row.optInt("episode_num", index + 1).takeIf { it > 0 } ?: index + 1
                        val title = value(row, "title", "name").ifBlank { "Épisode $episodeNumber" }
                        val metadata = row.optJSONObject("info") ?: JSONObject()
                        Episode(Entry(episodeId, "${entry.name} · S$number E$episodeNumber · $title", entry.category, "episode",
                            CatalogRules.safeExtension(row.optString("container_extension"), "mp4"), adult = entry.adult,
                            poster = ArtworkRules.url(value(metadata, "movie_image"), base).ifBlank { poster }, seriesId = entry.id,
                            seriesName = entry.name, season = number, episode = episodeNumber, seriesPoster = poster), title,
                            value(metadata, "plot", "description"), duration(metadata))
                    }
                Season(id, number, episodes)
            }.sortedWith(compareBy<Season> { it.number }.thenBy { it.id })
        }
        return MediaDetails(entry, value(info, "plot", "description").take(16000), year, duration(info), value(info, "genre").take(300),
            value(info, "cast", "actors").take(3000), value(info, "director").take(1000), value(info, "rating").take(30), seasons)
    }
}
