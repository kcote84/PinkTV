package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class EverydayUxTest {
    @Test fun audioNeverClaimsUnknownOrUnmarkedTrackHasNoNarration() {
        assertEquals("Piste 1 · langue non précisée", AudioRules.title("und", null, false, 0))
        assertEquals("Français", AudioRules.title("fr-CA", null, false, 0))
        assertEquals("Français · avec narration", AudioRules.title("fra", "AD", true, 1))
        assertEquals("Anglais", AudioRules.title("eng", null, false, 0))
    }
    @Test fun largeLibraryKeepsFrenchFromAllOriginsAndFavouriteRestriction() {
        val entries = (1..40000).map { Entry("$it", if(it % 2 == 0) "FR | Film $it" else "EN | Movie $it", if(it % 2 == 0) "fr" else "en", "vod") }
        val names = mapOf("fr" to "Films | Français", "en" to "English")
        val french = CatalogueSelection.select(entries, names, emptySet(), "", true, "")
        assertEquals(20000, french.size)
        assertTrue(french.all { it.category == "fr" })
        assertEquals(listOf("2"), CatalogueSelection.select(entries, names, setOf("vod:2", "vod:3"), "Favoris", true, "").map { it.id })
        assertEquals(listOf("40000"), CatalogueSelection.select(entries, names, emptySet(), "", true, "40000").map { it.id })
    }
    @Test fun obsoleteSearchCanBeCancelled() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { CatalogueSelection.select(listOf(Entry("1", "FR | Film", "fr", "vod")), emptyMap(), emptySet(), "", true, "") }
        } finally { Thread.interrupted() }
    }
}
