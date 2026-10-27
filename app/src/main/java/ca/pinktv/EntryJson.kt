package ca.pinktv

import org.json.JSONObject

/** Backward compatible with the favourite_details format shipped since the first APK. */
object EntryJson {
    fun write(e: Entry) = JSONObject().put("id", e.id).put("name", e.name).put("category", e.category)
        .put("kind", e.kind).put("extension", e.extension).put("number", e.number).put("adult", e.adult).put("poster", e.poster)
        .put("seriesId", e.seriesId).put("seriesName", e.seriesName).put("season", e.season).put("episode", e.episode).put("seriesPoster", e.seriesPoster)
    fun read(o: JSONObject) = Entry(o.getString("id"), o.getString("name"), o.optString("category"), o.getString("kind"),
        CatalogRules.safeExtension(o.optString("extension"), if(o.optString("kind") == "live") "ts" else "mp4"), o.optInt("number"), o.optBoolean("adult"), o.optString("poster"),
        o.optString("seriesId"), o.optString("seriesName"), o.optInt("season"), o.optInt("episode"), o.optString("seriesPoster"))
}
