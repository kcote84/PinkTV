package ca.pinktv

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class GuideWindowTest {
    private fun p(title: String, start: Long, end: Long) = Programme(title, "", start, end)
    @Test fun clipsToWindowAndKeepsRealGaps() {
        val first = p("Début", 50, 150); val last = p("Fin", 175, 250)
        val slots = GuideWindow.slots(listOf(last, first), 100, 200)
        assertEquals(listOf(GuideSlot(100,150,first), GuideSlot(150,175,null), GuideSlot(175,200,last)), slots)
        assertEquals(first, GuideWindow.at(slots, 149)?.programme)
        assertNull(GuideWindow.at(slots,150)?.programme)
        assertEquals(last, GuideWindow.at(slots,175)?.programme)
        assertNull(GuideWindow.at(slots,200))
    }
    @Test fun providerOverlapCannotCoverOtherCellsOrCreateNegativeWidths() {
        val first = p("Premier", 0, 150); val overlap = p("Suivant", 100, 250)
        val slots = GuideWindow.slots(listOf(first, p("Doublon", 0, 100), overlap), 50, 200)
        assertEquals(listOf(GuideSlot(50,150,first), GuideSlot(150,200,overlap)), slots)
        assertTrue(slots.all { it.end > it.start })
    }
    @Test fun missingScheduleStillProducesASelectableChannel() {
        assertEquals(listOf(GuideSlot(100,200,null)), GuideWindow.slots(emptyList(),100,200))
        assertTrue(GuideWindow.slots(emptyList(),200,100).isEmpty())
    }
    @Test fun parsesEpochMillisecondsAndRejectsOverflow() {
        val raw = """{"epg_listings":[{"title":"Émission","start_timestamp":1700000000000,"stop_timestamp":1700003600000},{"start_timestamp":9223372036854775807,"stop_timestamp":9223372036854775807}]}"""
        val result = Guide.parse(raw)
        assertEquals(1, result.size); assertEquals(1700000000000L, result.single().start)
        assertEquals(1700003600000L, result.single().end)
    }
    @Test fun onlyInterpretsNaiveDatesWhenProviderTimezoneIsKnown() {
        val raw = """{"epg_listings":[{"title":"Les nouvelles","start":"2026-10-21 18:00:00","end":"2026-10-21 19:00:00"}]}"""
        assertTrue(Guide.parse(raw).isEmpty())
        val result = Guide.parse(raw, ZoneId.of("America/Toronto")).single()
        assertEquals(java.time.Instant.parse("2026-10-21T22:00:00Z").toEpochMilli(), result.start)
    }
    @Test fun parsesExplicitTimezoneWithoutServerMetadata() {
        val raw = """{"epg_listings":[{"title":"Les nouvelles","start":"2026-10-21T18:00:00-04:00","end":"2026-10-21T19:00:00-04:00"}]}"""
        assertEquals(java.time.Instant.parse("2026-10-21T22:00:00Z").toEpochMilli(), Guide.parse(raw).single().start)
    }
}
