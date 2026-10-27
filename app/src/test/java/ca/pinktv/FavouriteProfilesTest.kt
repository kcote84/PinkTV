package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class FavouriteProfilesTest {
    @Test fun legacyFavouritesSurvivePasswordChangeAndEquivalentAddress() {
        val a = FavouriteProfiles.identity("http://EXAMPLE.com:8080/", "Alex")
        val b = FavouriteProfiles.identity("http://example.com:8080", "Alex")
        val selected = FavouriteProfiles.switch("{}", a, b, setOf("live:82"), "{\"live:82\":{\"name\":\"Zeste\"}}")
        assertEquals(setOf("live:82"), selected.keys)
        assertTrue(selected.details.contains("Zeste"))
        assertEquals("{}", selected.archive)
    }
    @Test fun differentAccountsKeepSeparateFavouritesAndReturningRestoresDetails() {
        val a = FavouriteProfiles.identity("http://example.com:8080", "A")
        val b = FavouriteProfiles.identity("http://example.com:8080", "B")
        val switched = FavouriteProfiles.switch("{}", a, b, setOf("live:82"), "{\"live:82\":{\"name\":\"Zeste\",\"poster\":\"art\"}}")
        assertTrue(switched.keys.isEmpty())
        val returned = FavouriteProfiles.switch(switched.archive, b, a, setOf("vod:1"), "{\"vod:1\":{\"name\":\"Film\"}}")
        assertEquals(setOf("live:82"), returned.keys)
        assertTrue(returned.details.contains("Zeste"))
        assertTrue(returned.details.contains("art"))
        val again = FavouriteProfiles.switch(returned.archive, a, b, returned.keys, returned.details)
        assertEquals(setOf("vod:1"), again.keys)
        assertFalse(switched.archive.contains("example.com"))
    }
    @Test fun otherProviderOrCaseSensitiveUsernameHasItsOwnList() {
        assertNotEquals(FavouriteProfiles.identity("https://example.com", "Alex"), FavouriteProfiles.identity("https://example.com", "alex"))
        assertNotEquals(FavouriteProfiles.identity("https://example.com", "Alex"), FavouriteProfiles.identity("https://other.com", "Alex"))
    }
}
