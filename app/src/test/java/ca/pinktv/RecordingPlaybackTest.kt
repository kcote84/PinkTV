package ca.pinktv
import org.junit.Assert.*
import org.junit.Test
class RecordingPlaybackTest {
    @Test fun localFilesWaitForWriterToClose() {
        assertTrue(RecordingPlayback.choices("pending",0).isEmpty())
        assertTrue(RecordingPlayback.choices("running",100).isEmpty())
        assertEquals(listOf(RecordingChoice.WATCH),RecordingPlayback.choices("incomplete",100))
        assertTrue(RecordingPlayback.choices("failed",0).isEmpty())
    }
}
