package ca.pinktv
enum class RecordingChoice(val title: String, val mode: String) { WATCH("Regarder", "start") }
object RecordingPlayback {
    fun choices(status: String, bytes: Long): List<RecordingChoice> = if(status !in RecordingRules.active && bytes > 0) listOf(RecordingChoice.WATCH) else emptyList()
}
