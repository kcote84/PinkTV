package ca.pinktv
import org.junit.Assert.*
import org.junit.Test

class LocalModeTest {
    @Test fun acceptsProviderPortsAndRejectsPastedStreamCredentials() {
        assertEquals("https://example.com:8443",AccountRules.server(" https://example.com:8443/ "))
        listOf("file:///etc/config","https://u:p@example.com","https://example.com/player_api.php?username=a","https://example.com#secret","http://example.com:70000").forEach { assertTrue(it,runCatching { AccountRules.server(it) }.isFailure) }
    }
    @Test fun twoPosterColumnsOnPhonesAndMoreSpaceOnTv() {
        assertEquals(2,LayoutRules.posterColumns(393)); assertEquals(4,LayoutRules.posterColumns(720)); assertEquals(6,LayoutRules.posterColumns(960))
    }
    @Test fun reservationsCanMeetButCannotOverlap() {
        assertFalse(RecordingRules.overlap(100,200,200,300))
        assertTrue(RecordingRules.overlap(100,201,200,300))
        assertTrue(RecordingRules.overlap(150,160,100,200))
    }
    @Test fun interruptedDownloadsAreNeverReportedAsComplete() {
        assertEquals("failed",RecordingRules.finalStatus(0,true))
        assertEquals("incomplete",RecordingRules.finalStatus(100,false))
        assertEquals("complete",RecordingRules.finalStatus(100,true))
        assertFalse(RecordingRules.validId("../../outside"))
    }
}
