package ca.pinktv

/** Runs on the guide's filtering worker with immutable snapshots, never Android views. */
object GuideFilters {
    fun select(entries: List<Entry>, categories: Map<String, String>, favourites: Set<String>,
        filter: String, category: String = "", query: String = ""): List<Entry> {
        val terms = CatalogRules.normalize(query)
        return entries.filter { entry ->
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val name = categories[entry.category].orEmpty()
            (category.isEmpty() || entry.category == category) && when(filter) {
                "★ Favoris" -> entry.key in favourites
                "Québec" -> CatalogRules.isQuebecLive(name, entry.name)
                "Adultes" -> CatalogRules.isAdult(name, entry.name, entry.adult)
                else -> true
            } && (terms.isEmpty() || CatalogRules.normalize("${entry.name} ${entry.number} $name").contains(terms))
        }
    }
}
