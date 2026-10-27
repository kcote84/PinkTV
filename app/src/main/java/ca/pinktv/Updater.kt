package ca.pinktv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Update(val version: String, val code: Int, val notes: String, val url: String, val sha256: String)
class Updater(private val activity: Activity) {
    fun check(): Update? {
        val release=JSONObject(Network.text("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest"))
        val assets=release.getJSONArray("assets")
        val manifest=(0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name")=="pinktv-update.json" } ?: return null
        val manifestUrl=manifest.getString("browser_download_url")
        require(manifestUrl.startsWith("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/"))
        val o=JSONObject(Network.text(manifestUrl))
        val code=o.getInt("versionCode")
        if(code<=BuildConfig.VERSION_CODE) return null
        val u=o.getString("apkUrl")
        require(u.startsWith("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/") && u.endsWith(".apk")) { "Adresse de mise à jour invalide." }
        val digest = o.getString("sha256").lowercase()
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Empreinte de mise à jour absente." }
        return Update(o.getString("versionName"), code, o.optString("notes", "Améliorations et corrections."), u, digest)
    }
    @Suppress("DEPRECATION")
    fun download(update: Update): File {
        // Android may purge cache while the user enables installation or the installer starts.
        // Keep the one staged APK in private files until the next download replaces it.
        val dir = File(activity.filesDir, "updates").apply { mkdirs() }
        val partial = File(dir, "download.part")
        val target = File(dir, "pinktv.apk")
        val c = Network.connection(update.url)
        try {
            require(c.responseCode == 200) { "Téléchargement indisponible." }
            c.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 128L*1024*1024) { "Fichier trop volumineux." }; output.write(buffer, 0, n) }
            } }
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { input -> val buf = ByteArray(8192); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "Le téléchargement est incomplet ou a été modifié." }
            val pm = activity.packageManager
            val archive = pm.getPackageArchiveInfo(partial.path, PackageManager.GET_SIGNATURES) ?: error("APK invalide.")
            val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
            require(archive.packageName == activity.packageName && archive.versionCode == update.code) { "Version ou application incorrecte." }
            val expected = installed.signatures?.map { it.toCharsString() }?.toSet()
            require(!expected.isNullOrEmpty() && archive.signatures?.map { it.toCharsString() }?.toSet() == expected) { "La signature de cette mise à jour ne correspond pas à Pink TV." }
            if (target.exists()) target.delete()
            require(partial.renameTo(target)) { "Impossible de préparer la mise à jour." }
            return target
        } finally { c.disconnect(); partial.delete() }
    }
    fun install(file: File) {
        require(file.isFile && file.length() > 0) { "Le fichier de mise à jour doit être téléchargé de nouveau." }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun canInstall(): Boolean = activity.packageManager.canRequestPackageInstalls()
    fun requestPermission() { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
}
