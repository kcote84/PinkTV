package ca.pinktv

data class AudioChoice(val fingerprint: String, val language: String?, val label: String?, val channels: Int, val described: Boolean)

/** Choose only among supported tracks. Never prefer a described track when a regular one exists. */
object AudioRules {
    fun title(language: String?, label: String?, described: Boolean, index: Int): String {
        val lang = language?.lowercase()?.substringBefore('-')?.substringBefore('_')
        val name = when(lang) {
            "fr", "fra", "fre" -> "Français"
            "en", "eng" -> "Anglais"
            "es", "spa" -> "Espagnol"
            null, "", "und", "zxx" -> "Piste ${index + 1} · langue non précisée"
            else -> java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.CANADA_FRENCH)
                .takeIf { it.isNotBlank() && it != lang }?.replaceFirstChar { it.titlecase() } ?: "Piste ${index + 1} · $lang"
        }
        // Absence of an AD flag is not proof that narration has not been mixed into the sound.
        return name + (if(described) " · avec narration" else "") +
            if(!label.isNullOrBlank() && !isDescription(label) && label.length <= 32 && label != language) " · $label" else ""
    }
    fun isDescription(label: String?): Boolean = Regex(
        "(^|[^a-z])(ad|audio.?description|audiodescription|videodescription|description|descriptive|visually impaired|malvoyants?)([^a-z]|$)"
    ).containsMatchIn(CatalogRules.normalize(label.orEmpty()))

    fun choose(options: List<AudioChoice>, saved: String?, mode: String, avoidDescription: Boolean): Int? {
        val regular = options.indices.filter { !options[it].described }
        val allowed = if (avoidDescription && regular.isNotEmpty()) regular else options.indices.toList()
        allowed.firstOrNull { options[it].fingerprint == saved }?.let { return it }
        return allowed.maxByOrNull { index ->
            val f = options[index]
            val french = f.language?.lowercase()?.substringBefore('-') in listOf("fr", "fra", "fre")
            (if (!f.described) 1000 else 0) + (if (french) 100 else 0) + when (mode) {
                "stereo" -> if (f.channels in 1..2) 20 else 0
                "surround" -> if (f.channels >= 6) 20 else 0
                else -> 0
            }
        }
    }
}
