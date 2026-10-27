package ca.pinktv

/** Saved live favourites remain usable even while the provider catalogue is unavailable. */
object MiniGuideRules {
    fun channels(catalogue: List<Entry>, saved: List<Entry>, categories: Map<String, String>, favourites: Set<String>, filter: String): List<Entry> {
        val source = if(filter == "★ Favoris") (catalogue + saved).filter { it.kind == "live" }.distinctBy { it.key }
            else catalogue.filter { it.kind == "live" }
        return GuideFilters.select(source, categories, favourites, filter)
    }
    data class Schedule(val now: Programme?, val next: Programme?)
    fun schedule(programmes: List<Programme>, time: Long): Schedule {
        val ordered = programmes.sortedBy { it.start }
        val current = ordered.firstOrNull { it.isNow(time) }
        return Schedule(current, ordered.firstOrNull { it.start >= (current?.end ?: time) })
    }
}
