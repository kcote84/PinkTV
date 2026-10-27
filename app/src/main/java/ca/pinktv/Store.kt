package ca.pinktv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(val server: String, val username: String, val password: String)

class Store(context: Context) {
    val prefs = context.getSharedPreferences("pinktv", Context.MODE_PRIVATE)
    var onFavouritesEdited: (() -> Unit)? = null
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("pinktv.account", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pinktv.account", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(account: Account) {
        val raw = JSONObject().put("server", account.server).put("username", account.username).put("password", account.password).toString()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(raw.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val edit = prefs.edit().putString("account", enc)
        val old = account()
        if(old != null && FavouriteProfiles.identity(old.server, old.username) != FavouriteProfiles.identity(account.server, account.username)) {
            val selected = FavouriteProfiles.switch(prefs.getString("favourite_profiles", "{}")!!,
                FavouriteProfiles.identity(old.server, old.username), FavouriteProfiles.identity(account.server, account.username),
                favourites(), prefs.getString("favourite_details", "{}")!!)
            edit.putString("favourite_profiles", selected.archive).putStringSet("favourites", selected.keys)
                .putString("favourite_details", selected.details)
        }
        edit.apply()
    }
    fun account(): Account? = runCatching {
        val parts = (prefs.getString("account", null) ?: return null).split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))) }
        val o = JSONObject(String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        Account(o.getString("server"), o.getString("username"), o.getString("password"))
    }.getOrNull()
    fun clearAccount() { prefs.edit().remove("account").apply() }
    @Synchronized fun favourites(): Set<String> = prefs.getStringSet("favourites", emptySet())!!.toSet()
    @Synchronized fun clearFavourites() { favouriteEntries().forEach { setFavourite(it, false) } }
    @Synchronized fun favouriteEntries(): List<Entry> = runCatching {
        val all = JSONObject(prefs.getString("favourite_details", "{}")!!)
        favourites().mapNotNull { id -> all.optJSONObject(id)?.let { EntryJson.read(it) } }.sortedBy { it.name }
    }.getOrDefault(emptyList())
    fun toggle(entry: Entry): Boolean = setFavourite(entry, entry.key !in favourites())
    @Synchronized fun setFavourite(entry: Entry, wanted: Boolean): Boolean {
        val values = favourites().toMutableSet()
        val details = JSONObject(prefs.getString("favourite_details", "{}")!!)
        val added = if (!wanted) { values.remove(entry.key); details.remove(entry.key); false } else {
            values.add(entry.key)
            details.put(entry.key, EntryJson.write(entry))
            true
        }
        val edit = prefs.edit().putStringSet("favourites", values).putString("favourite_details", details.toString())
        edit.apply()
        onFavouritesEdited?.invoke()
        return added
    }
    private fun progressKey(): String? = account()?.let { "watch_history:" + FavouriteProfiles.identity(it.server, it.username) }
    fun history(): List<WatchProgress> = progressKey()?.let { WatchHistory.decode(prefs.getString(it, "[]")!!) }.orEmpty()
    fun remember(progress: WatchProgress) {
        val key = progressKey() ?: return
        prefs.edit().putString(key, WatchHistory.encode(WatchHistory.update(history(), progress))).apply()
    }
    fun setContinueHidden(entry: Entry, hidden: Boolean) {
        val key = progressKey() ?: return
        prefs.edit().putString(key, WatchHistory.encode(history().map {
            if(it.entry.key == entry.key || entry.kind == "series" && it.entry.seriesId == entry.id) it.copy(hidden = hidden) else it
        })).apply()
    }
}
