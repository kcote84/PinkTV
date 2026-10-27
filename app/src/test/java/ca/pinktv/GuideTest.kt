package ca.pinktv

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GuideTest {
    @Test fun decodesSortsAndRejectsInvalidSchedules() {
        val encoded = Base64.getEncoder().encodeToString("Téléjournal".toByteArray(Charsets.UTF_8))
        val rows = Guide.parse("""{"epg_listings":[
            {"title":"$encoded","description":"RGV0YWlscw==","start_timestamp":"200","stop_timestamp":"300"},
            {"title":"Sport en direct","start_timestamp":"100","end_timestamp":"200"},
            {"title":"Invalide","start_timestamp":"500","stop_timestamp":"400"},
            {"title":"Sans heure"}
        ]}""")
        assertEquals(2, rows.size)
        assertEquals("Sport en direct", rows[0].title)
        assertEquals("Téléjournal", rows[1].title)
        assertEquals("Details", rows[1].description)
        assertTrue(rows[0].isNow(100000L))
        assertFalse(rows[0].isNow(200000L))
    }
    @Test fun missingScheduleIsEmpty() {
        assertTrue(Guide.parse("{}").isEmpty())
        assertTrue(Guide.parse("""{"epg_listings":[]}""").isEmpty())
    }
}
