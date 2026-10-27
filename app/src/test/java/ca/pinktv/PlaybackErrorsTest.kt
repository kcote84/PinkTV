package ca.pinktv

import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorsTest {
    @Test fun permanentAccessAndMissingResourcesDoNotLoopButTransientFailuresRetry() {
        listOf(401, 403, 404, 410).forEach { assertFalse(PlaybackErrors.shouldRetry(it)) }
        listOf(null, 408, 429, 500, 502, 503).forEach { assertTrue(PlaybackErrors.shouldRetry(it)) }
    }

    @Test fun messagesDistinguishAccessMissingResourceRateLimitAndServerFailure() {
        assertTrue(PlaybackErrors.explanation(403).contains("refuse l’accès"))
        assertTrue(PlaybackErrors.explanation(404).contains("introuvable"))
        assertTrue(PlaybackErrors.explanation(429).contains("trop de demandes"))
        assertTrue(PlaybackErrors.explanation(503).contains("serveur rencontre une erreur"))
        assertTrue(PlaybackErrors.explanation(302).contains("réponse HTTP"))
        assertTrue(PlaybackErrors.explanation(null).contains("réglages"))
    }

    @Test fun unrelatedOrCyclicCausesDoNotLeakMessagesOrHang() {
        val first = Exception("https://example.invalid/live/SECRET_USER/SECRET_PASSWORD/1.m3u8")
        val second = Exception("SECRET_SERVER_RESPONSE", first)
        first.initCause(second)
        assertNull(PlaybackErrors.httpStatus(first))
        val message = PlaybackErrors.explanation(PlaybackErrors.httpStatus(first))
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("https://"))
    }
}
