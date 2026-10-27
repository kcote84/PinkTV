package ca.pinktv

import org.json.JSONObject
import java.net.URI

object ArtworkRules {
    fun url(value: String, base: String = ""): String = runCatching {
        val raw = value.trim()
        if(raw.isEmpty() || raw.length > 4096 || raw.equals("null", true)) return ""
        val encoded = raw.replace(" ", "%20")
        val candidate = URI(if(encoded.startsWith("//")) "https:$encoded" else encoded)
        val uri = if(candidate.isAbsolute) candidate else URI(base.trimEnd('/') + "/").resolve(candidate)
        if(uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) "" else uri.toASCIIString()
    }.getOrDefault("")
}

object CatalogParser {
    fun entry(o: JSONObject, kind: String, base: String): Entry = Entry(
        o.optString(if(kind == "series") "series_id" else "stream_id"), o.optString("name", "Sans titre"),
        o.optString("category_id"), kind,
        CatalogRules.safeExtension(o.optString("container_extension"), if(kind == "live") "ts" else "mp4"),
        o.optInt("num"), o.optInt("is_adult") == 1 || o.optBoolean("is_adult"),
        ArtworkRules.url(o.optString(if(kind == "series") "cover" else "stream_icon"), base))
}
