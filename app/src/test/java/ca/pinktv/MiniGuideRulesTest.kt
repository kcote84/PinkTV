package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class MiniGuideRulesTest {
    private val zeste = Entry("82", "Zeste", "qc", "live")
    private val france = Entry("83", "France 24", "fr", "live")
    private val movie = Entry("82", "Film en français", "qc", "vod")
    private val names = mapOf("qc" to "Québec", "fr" to "France")

    @Test fun savedLiveFavouritesWorkBeforeCatalogueArrives() {
        assertEquals(listOf(zeste), MiniGuideRules.channels(emptyList(), listOf(zeste, movie), names, setOf(zeste.key, movie.key), "★ Favoris"))
    }
    @Test fun freshCatalogueMetadataWinsWithoutDuplicatingSavedFavourite() {
        val fresh = zeste.copy(name = "Zeste HD")
        assertEquals(listOf(fresh), MiniGuideRules.channels(listOf(fresh, france), listOf(zeste), names, setOf(zeste.key), "★ Favoris"))
    }
    @Test fun favouritesMissingFromCatalogueRemainAvailable() {
        assertEquals(listOf(zeste), MiniGuideRules.channels(listOf(france), listOf(zeste), names, setOf(zeste.key), "★ Favoris"))
    }
    @Test fun tabsDoNotLeakSavedChannelsIntoAnotherCatalogue() {
        assertEquals(listOf(france), MiniGuideRules.channels(listOf(france), listOf(zeste), names, setOf(zeste.key), "Toutes"))
        assertTrue(MiniGuideRules.channels(listOf(france), listOf(zeste), names, setOf(zeste.key), "Québec").isEmpty())
    }
    @Test fun emptyFavouritesNeverFallBackToAllChannels() {
        assertTrue(MiniGuideRules.channels(listOf(zeste, france), listOf(movie), names, setOf(movie.key), "★ Favoris").isEmpty())
    }
    @Test fun quebecTabRetainsExistingRegionalRules() {
        assertEquals(listOf(zeste), MiniGuideRules.channels(listOf(zeste, france, movie), emptyList(), names, emptySet(), "Québec"))
    }
    @Test fun scheduleBoundaryAndUnorderedInputChooseActualCurrentAndNext() {
        val before = Programme("Avant", "", 10, 20)
        val now = Programme("En cours", "", 20, 30)
        val next = Programme("Après", "", 30, 40)
        assertEquals(MiniGuideRules.Schedule(now, next), MiniGuideRules.schedule(listOf(next, before, now), 20))
        assertEquals(MiniGuideRules.Schedule(next, null), MiniGuideRules.schedule(listOf(before, now, next), 30))
    }
    @Test fun scheduleGapsAndMissingDataDoNotInventCurrentProgramme() {
        val old = Programme("Terminé", "", 10, 20)
        val future = Programme("À venir", "", 40, 50)
        assertEquals(MiniGuideRules.Schedule(null, future), MiniGuideRules.schedule(listOf(old, future), 30))
        assertEquals(MiniGuideRules.Schedule(null, null), MiniGuideRules.schedule(emptyList(), 30))
    }
}
