package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class AudioRulesTest {
    private val description = AudioChoice("ad", "fr", "Français AD", 2, true)
    private val english = AudioChoice("en", "en", null, 2, false)
    private val frenchStereo = AudioChoice("fr2", "fra", null, 2, false)
    private val frenchSurround = AudioChoice("fr6", "fr-CA", null, 6, false)

    @Test fun describedFrenchMustNotWinOverRegularEnglishEvenWhenRemembered() {
        assertEquals(1, AudioRules.choose(listOf(description, english), "ad", "auto", true))
    }
    @Test fun surroundAndStereoPreferencesSelectAvailableFrenchTracks() {
        val tracks = listOf(description, english, frenchSurround, frenchStereo)
        assertEquals(3, AudioRules.choose(tracks, null, "stereo", true))
        assertEquals(2, AudioRules.choose(tracks, null, "surround", true))
        assertEquals(3, AudioRules.choose(tracks, "fr2", "surround", true))
    }
    @Test fun explicitDescriptionPreferenceAndSingleTrackStillWork() {
        assertEquals(0, AudioRules.choose(listOf(description, english), "ad", "auto", false))
        assertEquals(0, AudioRules.choose(listOf(description), null, "stereo", true))
        assertNull(AudioRules.choose(emptyList(), null, "auto", true))
    }
    @Test fun descriptionLabelsAndFrenchIsoCodes() {
        assertTrue(AudioRules.isDescription("Français - audiodescription"))
        assertTrue(AudioRules.isDescription("French (AD)"))
        assertTrue(AudioRules.isDescription("Vidéodescription"))
        assertFalse(AudioRules.isDescription("Radio Canada"))
        assertEquals(1, AudioRules.choose(listOf(english, frenchStereo.copy(language = "fre")), null, "auto", true))
    }
}
