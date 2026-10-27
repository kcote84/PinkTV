package ca.pinktv
import org.junit.Assert.*
import org.junit.Test
class CatalogRulesTest {
    @Test fun frenchNamesAndAccents() {
        assertTrue(CatalogRules.matches("Français", "Québec", "TVA"))
        assertTrue(CatalogRules.matches("Français", "FR | Généralistes", "France 2"))
        assertFalse(CatalogRules.matches("Français", "USA", "Fresh movies"))
        assertFalse(CatalogRules.matches("Français", "Canada | English", "CTV"))
        assertTrue(CatalogRules.matches("Français", "Films VFQ", "Titre"))
        assertTrue(CatalogRules.matches("Québec", "Séries québécoises", "Titre"))
        assertFalse(CatalogRules.matches("Québec", "France", "Titre français"))
    }
    @Test fun sportsAndOrdinaryChannels() {
        assertTrue(CatalogRules.matches("Sports", "Canada", "RDS 2"))
        assertTrue(CatalogRules.matches("Sports", "UK SPORTS", "Sky"))
        assertFalse(CatalogRules.matches("Sports", "Cinéma", "Cinépop"))
    }
    @Test fun extensionsCannotChangeAPath() {
        assertEquals("mkv", CatalogRules.safeExtension("mkv", "mp4"))
        assertEquals("mp4", CatalogRules.safeExtension("../../secret", "mp4"))
        assertEquals("ts", CatalogRules.safeExtension("", "ts"))
    }
}
