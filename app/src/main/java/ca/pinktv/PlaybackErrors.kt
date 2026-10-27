package ca.pinktv

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackErrors {
    // Never display exception messages: they can contain the account's stream URL.
    fun httpStatus(error: Throwable): Int? = generateSequence(error) { it.cause }.take(16)
        .mapNotNull { when(it) { is HttpDataSource.InvalidResponseCodeException -> it.responseCode; else -> null } }.firstOrNull()

    fun shouldRetry(status: Int?): Boolean = status !in listOf(401, 403, 404, 410)

    fun explanation(status: Int?): String = when (status) {
        401, 403 -> "Le serveur refuse l’accès à une ressource du flux. Le compte, le lien ou le format peut être en cause."
        404, 410 -> "Une ressource du flux est introuvable ou n’est plus disponible sur le serveur."
        429 -> "Le serveur reçoit trop de demandes. Attends un peu avant de réessayer."
        in 500..599 -> "Le serveur rencontre une erreur. Réessaie un peu plus tard."
        null -> "Le flux ne peut pas être lu. Essaie Reprendre ou vérifie les réglages du direct et du son."
        else -> "Le serveur renvoie une réponse HTTP qui empêche la lecture."
    }
}
