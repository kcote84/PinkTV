package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class GuideFiltersTest {
    private val categories = mapOf("fr" to "FRENCH", "en" to "ENGLISH", "adult" to "Adultes")
    private val entries = listOf(
        Entry("1", "Zeste (FR)", "fr", "live", number = 82),
        Entry("2", "France 24", "fr", "live", number = 83),
        Entry("3", "TVA (FR)", "fr", "live", number = 84),
        Entry("4", "English TV", "en", "live"),
        Entry("5", "Canal XXX", "adult", "live", adult = true))

    @Test fun switchingTabsDoesNotModifyFavouriteSnapshotOrCatalogue() {
        val favourites = setOf("live:2", "live:4")
        val before = entries.toList()
        assertEquals(listOf(entries[1], entries[3]), GuideFilters.select(entries, categories, favourites, "★ Favoris"))
        assertEquals(listOf(entries[0], entries[2]), GuideFilters.select(entries, categories, favourites, "Québec"))
        assertEquals(before, GuideFilters.select(entries, categories, favourites, "Toutes"))
        assertEquals(setOf("live:2", "live:4"), favourites)
        assertEquals(before, entries)
        assertEquals(listOf(entries[1], entries[3]), GuideFilters.select(entries, categories, favourites, "★ Favoris"))
    }
    @Test fun categoryAndAccentInsensitiveNameOrNumberSearchCombineWithTab() {
        assertEquals(listOf(entries[0]), GuideFilters.select(entries, categories, emptySet(), "Québec", "fr", "82"))
        assertEquals(listOf(entries[2]), GuideFilters.select(entries, categories, emptySet(), "Québec", "fr", "tVa"))
        assertTrue(GuideFilters.select(entries, categories, emptySet(), "Québec", "en").isEmpty())
        assertEquals(listOf(entries[4]), GuideFilters.select(entries, categories, emptySet(), "Adultes"))
    }
    @Test fun emptyFavouritesDoNotFallBackToOtherChannels() {
        assertTrue(GuideFilters.select(entries, categories, emptySet(), "★ Favoris").isEmpty())
    }
    @Test fun supersededFilteringCanBeInterruptedWithoutClearingFavourites() {
        Thread.currentThread().interrupt()
        try {
            GuideFilters.select(entries, categories, setOf("live:1"), "Québec")
            fail("Cancelled filtering must stop before publishing a result")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
