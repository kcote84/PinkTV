package ca.pinktv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchHistoryTest {
    private fun episode(id: String, season: Int, number: Int) = Entry(id, "Titre $id", "fr", "episode", seriesId = "42", seriesName = "Ma série", season = season, episode = number, seriesPoster = "https://example.com/poster.jpg")
    private val first = episode("1", 1, 1)
    private val second = episode("2", 1, 2)
    private val third = episode("3", 2, 1)
    private fun progress(e: Entry, position: Long = 15000, done: Boolean = false, at: Long = 1, next: Entry? = null) = WatchProgress(e, position, 120000, done, at, next)
    @Test fun positionAndSeasonSurviveSerializationIncludingNextSeason() {
        val p = progress(second, next = third)
        assertEquals(p, WatchHistory.decode(WatchHistory.encode(listOf(p))).single())
        assertEquals("series:42", p.seriesEntry().key)
        assertEquals("https://example.com/poster.jpg", p.seriesEntry().poster)
    }
    @Test fun completionUsesActualEndOrBoundedCreditsWindowAndUnknownDurationIsSafe() {
        assertFalse(WatchHistory.completed(15000, 120000))
        assertTrue(WatchHistory.completed(119000, 120000))
        assertFalse(WatchHistory.completed(4500000, 7200000))
        assertFalse(WatchHistory.completed(9999999, 0))
        assertTrue(WatchHistory.completed(0, 0, true))
        assertFalse(progress(first, 4000).resumable)
    }
    @Test fun recentSeriesResumeAtSameEpisodeOrNextAcrossSeasons() {
        val d = MediaDetails(Entry("42", "Ma série", "fr", "series"), seasons = listOf(Season("1", 1, listOf(Episode(first, "Un"), Episode(second, "Deux"))), Season("2", 2, listOf(Episode(third, "Trois")))))
        assertEquals(second, WatchHistory.recommend(d, listOf(progress(first, done = true), progress(second, at = 2)))?.entry)
        assertEquals(third, WatchHistory.recommend(d, listOf(progress(second, done = true, next = third)))?.entry)
        assertEquals(first, WatchHistory.recommend(d, emptyList())?.entry)
    }
    @Test fun newSeriesStartsAtSeasonOneBeforeSpecials() {
        val special = episode("0", 0, 1)
        val d = MediaDetails(Entry("42", "Série", "fr", "series"), seasons = listOf(Season("0", 0, listOf(Episode(special, "Bonus"))), Season("1", 1, listOf(Episode(first, "Un")))))
        assertEquals(first, WatchHistory.recommend(d, emptyList())?.entry)
    }
    @Test fun continuingHasOneRecentEntryPerSeriesAndDoesNotReviveOlderProgressAfterLastEpisode() {
        assertEquals(listOf(second), WatchHistory.continuing(listOf(progress(first), progress(second, at = 2)), "series").map { it.entry })
        assertTrue(WatchHistory.continuing(listOf(progress(first), progress(third, done = true, at = 2)), "series").isEmpty())
        assertEquals(second, WatchHistory.continuing(listOf(progress(second, done = true, next = third)), "series").single().entry)
    }
    @Test fun onlyIncompleteFilmsAppearInContinue() {
        val movie = Entry("7", "Film", "fr", "vod")
        assertEquals(movie, WatchHistory.continuing(listOf(progress(movie)), "vod").single().entry)
        assertTrue(WatchHistory.continuing(listOf(progress(movie, done = true)), "vod").isEmpty())
    }
    @Test fun updatingARecordIsIdempotentAndHistoryIsBounded() {
        val all = (1..250).map { progress(Entry("$it", "Film", "fr", "vod"), at = it.toLong()) }
        val bounded = WatchHistory.update(all, progress(all.last().entry, position = 42000, at = 999))
        assertEquals(200, bounded.size); assertEquals(200, bounded.map { it.entry.key }.toSet().size)
        assertEquals(42000, bounded.first().positionMs)
        assertEquals(200, WatchHistory.decode(WatchHistory.encode(bounded)).size)
    }
    @Test fun badRecordDoesNotLoseOtherRecordsOrAffectFavouriteData() {
        val raw = JSONArray(WatchHistory.encode(listOf(progress(first)))).put(JSONObject().put("entry", JSONObject().put("id", "bad")))
        assertEquals(first, WatchHistory.decode(raw.toString()).single().entry)
        assertTrue(WatchHistory.decode("not json").isEmpty())
    }
    @Test fun removedEpisodeDoesNotSelectAnUnrelatedSeriesRecord() {
        val d = MediaDetails(Entry("42", "Série", "fr", "series"), seasons = listOf(Season("1", 1, listOf(Episode(first, "Un")))))
        assertEquals(first, WatchHistory.recommend(d, listOf(progress(second.copy(seriesId = "99"))))?.entry)
        assertNull(WatchHistory.recommend(d.copy(seasons = emptyList()), emptyList()))
    }
    @Test fun corruptOptionalNextDoesNotEraseCurrentPosition() {
        val a = JSONArray(WatchHistory.encode(listOf(progress(first))))
        a.getJSONObject(0).put("next", JSONObject().put("id", "bad"))
        assertEquals(15000, WatchHistory.decode(a.toString()).single().positionMs)
    }
    @Test fun hidingFromContinuePreservesPositionAndWatchedStateAndDoesNotReviveOlderSeries() {
        val old = progress(first)
        val hidden = progress(second, at = 2, next = third).copy(hidden = true)
        val restored = WatchHistory.decode(WatchHistory.encode(listOf(hidden, old)))
        assertEquals(15000, restored.first().positionMs)
        assertTrue(WatchHistory.continuing(restored, "series").isEmpty())
        assertEquals(second, WatchHistory.continuing(listOf(hidden.copy(hidden = false)), "series").single().entry)
        val movie = progress(Entry("7", "Film", "fr", "vod")).copy(hidden = true)
        assertTrue(WatchHistory.continuing(listOf(movie), "vod").isEmpty())
    }
    @Test fun continueSkipsAlreadyWatchedEpisodesWhenStatesWereCorrectedOutOfOrder() {
        val d = MediaDetails(Entry("42", "Série", "fr", "series"), seasons = listOf(Season("1", 1, listOf(Episode(first, "Un"), Episode(second, "Deux"))), Season("2", 2, listOf(Episode(third, "Trois")))))
        assertEquals(third, WatchHistory.recommend(d, listOf(progress(second, done = true, at = 1), progress(first, done = true, at = 2)))?.entry)
    }
}
