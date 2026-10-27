package ca.pinktv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaDetailsTest {
    private val series = Entry("42", "Une belle série", "fr", "series", poster = "https://example.com/old.jpg")
    @Test fun seasonsAndEpisodesAreNumericOrderedWithStableParentIdentity() {
        val raw = JSONObject("""{"info":{"plot":"Une famille au Québec.","releaseDate":"2024-01-10","cover":"/art.jpg"},"episodes":{"10":[{"id":"100","episode_num":1,"title":"Dix"}],"2":[{"id":"21","episode_num":2},{"id":"20","episode_num":1,"title":"Deux","info":{"duration_secs":"1800","plot":"Résumé"}}],"1":[{"id":"10","episode_num":1}],"0":[{"id":"1","episode_num":1,"title":"Spécial"}]}}""")
        val d = MediaDetailsParser.parse(raw, series, "https://example.com")
        assertEquals(listOf(0, 1, 2, 10), d.seasons.map { it.number })
        assertEquals(listOf("20", "21"), d.seasons[2].episodes.map { it.entry.id })
        assertEquals("2024", d.year)
        val e = d.seasons[2].episodes[0]
        assertEquals(1800000, e.durationMs)
        assertEquals("42", e.entry.seriesId); assertEquals(2, e.entry.season); assertEquals(1, e.entry.episode)
        assertEquals("https://example.com/art.jpg", e.entry.seriesPoster)
        assertEquals("Spéciaux", d.seasons[0].title)
    }
    @Test fun invalidAndDuplicateEpisodeIdsDoNotCreatePlayableRows() {
        val d = MediaDetailsParser.parse(JSONObject("""{"episodes":{"2":[{"id":"1"}],"1":[{"id":"bad/path"},{"id":"1"},{"id":"2","container_extension":"../../bad"}]}}"""), series, "")
        assertEquals(listOf("1", "2"), d.episodes.map { it.entry.id })
        assertEquals("mp4", d.episodes[1].entry.extension)
    }
    @Test fun missingMetadataKeepsPlayableFilmAndDoesNotInventDetails() {
        val entry = Entry("1", "Un film", "fr", "vod", "mkv", poster = "https://example.com/p.jpg")
        val d = MediaDetailsParser.parse(JSONObject("""{"info":{"plot":null,"year":"null","duration":"unknown","movie_image":"javascript:bad"}}"""), entry, "https://example.com")
        assertEquals(entry, d.entry); assertEquals("", d.plot); assertEquals("", d.year); assertEquals(0, d.durationMs)
    }
    @Test fun movieMetadataAndValidatedExtensionAreUsed() {
        val d = MediaDetailsParser.parse(JSONObject("""{"info":{"plot":"Le résumé.","releasedate":"2023-02-03","duration":"01:42:30","genre":"Comédie","cast":"A, B","director":"C","rating":"8.2","cover_big":"/a b.jpg"},"movie_data":{"container_extension":"mp4"}}"""), Entry("1", "Film", "fr", "vod", "mkv"), "https://example.com")
        assertEquals(6150000, d.durationMs); assertEquals("2023", d.year); assertEquals("mp4", d.entry.extension)
        assertEquals("https://example.com/a%20b.jpg", d.entry.poster); assertEquals("Comédie", d.genre)
    }
    @Test fun malformedDurationsAndEmptySeasonsAreHandled() {
        assertEquals(0, MediaDetailsParser.duration(JSONObject("""{"duration_secs":"NaN","duration":"garbage"}""")))
        assertEquals(0, MediaDetailsParser.duration(JSONObject("""{"duration":"01:99:99"}""")))
        assertEquals(1500000, MediaDetailsParser.duration(JSONObject("""{"duration":"25:00"}""")))
        val d = MediaDetailsParser.parse(JSONObject("""{"episodes":{"1":[],"2":"invalid"}}"""), series, "")
        assertTrue(d.episodes.isEmpty()); assertEquals(2, d.seasons.size)
    }
    @Test fun legacyFavouritesRoundTripWithoutLosingFields() {
        val raw = JSONObject("""{"id":"82","name":"Zeste","category":"qc","kind":"live","extension":"ts","number":82,"adult":false,"poster":"https://example.com/z.png"}""")
        val entry = EntryJson.read(raw)
        assertEquals("live:82", entry.key); assertEquals("ts", entry.extension); assertEquals("", entry.seriesId)
        assertEquals(entry, EntryJson.read(EntryJson.write(entry)))
    }
}
