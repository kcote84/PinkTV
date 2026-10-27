package ca.pinktv

import java.net.URI

object AccountRules {
    fun server(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "Entre l’URL HTTP ou HTTPS fournie avec ton compte, avec son port au besoin." }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Entre seulement l’adresse du serveur, sans player_api.php ni lien de chaîne." }
        require(uri.port == -1 || uri.port in 1..65535) { "Le port du serveur est invalide." }
        return uri.toASCIIString().trimEnd('/')
    }
}
