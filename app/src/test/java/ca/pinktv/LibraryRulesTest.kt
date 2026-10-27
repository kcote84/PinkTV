package ca.pinktv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    @Test fun quebecLiveExcludesFranceWithinMixedFrenchGroups() {
        listOf("TVA (FR)", "ICI Télé", "RDS 2", "ZESTE (FR)", "Canal Vie", "ARTV (FR)", "MÉTÉO MÉDIA (FR)", "MAX (FR)", "SUPER ÉCRAN 1 (FR)", "TV5 (FR)", "TÉLÉTOON (FR)").forEach {
            assertTrue(it, CatalogRules.isQuebecLive("FRENCH", it))
        }
        listOf("France 2", "France 24", "TF1", "M6", "ARTE", "English TV", "TV5 MONDE (FR)", "TÉLÉTOON PLUS (FR)", "SCIENCE & VIE TV (FR)").forEach {
            assertFalse(it, CatalogRules.isQuebecLive("FRENCH", it))
        }
        assertFalse(CatalogRules.isQuebecLive("Québec", "France 24"))
        assertFalse(CatalogRules.isQuebecLive("FRANCE", "Canal Vie"))
    }
    @Test fun localMetadataIsRecognisedWithoutTreatingAllCanadaAsFrench() {
        assertTrue(CatalogRules.isQuebecLive("Québec | Français", "Chaîne locale"))
        assertTrue(CatalogRules.isQuebecLive("Canada | French", "Chaîne locale"))
        assertFalse(CatalogRules.isQuebecLive("Canada | English", "CTV"))
        assertFalse(CatalogRules.isQuebecLive("FRENCH", "France 3"))
    }
    @Test fun vodIncludesFrenchFromFranceAndQuebecAndDubbedFilms() {
        assertTrue(CatalogRules.isFrenchVod("FRENCH", "Un été en France"))
        assertTrue(CatalogRules.isFrenchVod("France", "Un film"))
        assertTrue(CatalogRules.isFrenchVod("Séries québécoises", "Un titre"))
        assertTrue(CatalogRules.isFrenchVod("Nouveautés", "Un titre [VFQ]"))
        assertTrue(CatalogRules.isFrenchVod("Nouveautés", "Un titre VFF"))
    }
    @Test fun subtitlesAndUnmarkedEnglishDoNotEnterFrenchLibrary() {
        assertFalse(CatalogRules.isFrenchVod("FRENCH", "Un titre VOSTFR"))
        assertFalse(CatalogRules.isFrenchVod("VOSTFR", "Un titre"))
        assertFalse(CatalogRules.isFrenchVod("FRENCH", "[EN] A title"))
        assertFalse(CatalogRules.isFrenchVod("ENGLISH", "The French Connection"))
        assertFalse(CatalogRules.isFrenchVod("Canada", "A title MULTI"))
    }
    @Test fun filmAndSeriesArtUsesProviderFieldsAndRelativePaths() {
        val movie = CatalogParser.entry(JSONObject("""{"stream_id":"42","name":"Film VF","stream_icon":"/images/film.jpg"}"""), "vod", "https://demo.invalid:8080")
        assertEquals("https://demo.invalid:8080/images/film.jpg", movie.poster)
        assertEquals("vod:42", movie.key)
        val series = CatalogParser.entry(JSONObject("""{"series_id":"7","cover":"https://images.invalid/cover.jpg"}"""), "series", "https://demo.invalid")
        assertEquals("https://images.invalid/cover.jpg", series.poster)
        assertEquals("series:7", series.key)
    }
    @Test fun artworkRejectsUnsafeAndMissingUrlsWithoutBreakingEntries() {
        listOf("", "null", "javascript:alert(1)", "file:///private/image.jpg", "https://user:password@demo.invalid/image.jpg").forEach {
            assertEquals(it, "", ArtworkRules.url(it, "https://demo.invalid"))
        }
        assertEquals("https://images.invalid/a%20b.jpg", ArtworkRules.url("//images.invalid/a b.jpg"))
        val entry = CatalogParser.entry(JSONObject("""{"stream_id":"1","is_adult":true,"container_extension":"../../bad"}"""), "vod", "https://demo.invalid")
        assertEquals("", entry.poster); assertTrue(entry.adult); assertEquals("mp4", entry.extension)
    }
}
