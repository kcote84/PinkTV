package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class AdultRulesTest {
    @Test fun recognizesProviderGroupsAndExplicitMarkers() {
        listOf("ADULT", "Adults", "Adultes | FR", "18+", "XXX FR", "Porno", "Érotique").forEach {
            assertTrue(it, CatalogRules.isAdult(it, "Chaîne de test"))
        }
        assertTrue(CatalogRules.isAdult("International", "XXX TV"))
        assertTrue(CatalogRules.isAdult("International", "Playboy TV"))
    }
    @Test fun providerFlagIncludesNeutrallyNamedEntries() {
        assertTrue(CatalogRules.matches("Adultes", "Divers", "Canal 123", true))
        assertFalse(CatalogRules.matches("Adultes", "Divers", "Canal 123", false))
    }
    @Test fun avoidsUnrelatedWordsAndOrdinaryShowTitles() {
        listOf("Adult Swim", "Adults in the Room", "Sex Education", "Portrait", "Extra", "18 ans après", "Le pornographe").forEach {
            assertFalse(it, CatalogRules.isAdult("Divertissement", it))
        }
        assertFalse(CatalogRules.isAdult("Adult Swim", "Cartoon Network"))
        assertFalse(CatalogRules.isAdult("Sports", "RDS"))
    }
    @Test fun adultGroupingDoesNotChangeOtherViewsOrLanguageFilters() {
        assertTrue(CatalogRules.matches("Français", "Adultes | FR", "Canal"))
        assertTrue(CatalogRules.matches("", "Adultes", "Canal"))
        assertTrue(CatalogRules.matches("Favoris", "Divers", "Canal", true))
    }
}
