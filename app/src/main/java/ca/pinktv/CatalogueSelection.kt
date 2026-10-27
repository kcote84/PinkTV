package ca.pinktv

object CatalogueSelection {
    fun select(entries: List<Entry>, names: Map<String, String>, favourites: Set<String>, section: String,
               frenchOnly: Boolean, query: String): List<Entry> {
        val term = CatalogRules.normalize(query)
        return entries.filter {
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val category = names[it.category].orEmpty()
            (section != "Favoris" || it.key in favourites) && CatalogRules.matches(section, category, it.name, it.adult) &&
                (!frenchOnly || CatalogRules.isFrenchVod(category, it.name)) && CatalogRules.normalize(it.name).contains(term)
        }
    }
}
