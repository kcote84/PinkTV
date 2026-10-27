package ca.pinktv

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Account passwords never participate in the identity or the archived favourites. */
object FavouriteProfiles {
    fun identity(server: String, username: String): String {
        val uri = URI(server.trim().trimEnd('/'))
        val address = "${uri.scheme.lowercase(Locale.ROOT)}://${uri.host.lowercase(Locale.ROOT)}:${uri.port}"
        return MessageDigest.getInstance("SHA-256").digest("$address\u0000$username".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    data class Selection(val archive: String, val keys: Set<String>, val details: String)
    fun switch(archive: String, old: String, next: String, keys: Set<String>, details: String): Selection {
        if(old == next) return Selection(archive, keys, details)
        val profiles = JSONObject(archive)
        profiles.put(old, JSONObject().put("keys", JSONArray(keys.toList())).put("details", JSONObject(details)))
        val target = profiles.optJSONObject(next)
        val ids = target?.optJSONArray("keys")
        return Selection(profiles.toString(), (0 until (ids?.length() ?: 0)).map { ids!!.getString(it) }.toSet(),
            target?.optJSONObject("details")?.toString() ?: "{}")
    }
}
