package ca.pinktv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object RecordingRules {
    val active = setOf("pending", "running")
    fun overlap(start: Long, end: Long, otherStart: Long, otherEnd: Long) = start < otherEnd && end > otherStart
    fun validId(id: String) = id.matches(Regex("[a-f0-9-]{36}"))
    fun finalStatus(bytes: Long, reachedEnd: Boolean) = if(bytes > 0 && reachedEnd) "complete" else if(bytes > 0) "incomplete" else "failed"
}

object Recordings {
    private fun prefs(c: Context) = c.getSharedPreferences("recordings", Context.MODE_PRIVATE)
    @Synchronized fun jobs(c: Context): List<JSONObject> = runCatching {
        val a = JSONArray(prefs(c).getString("jobs", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    private fun write(c: Context, jobs: List<JSONObject>) { check(prefs(c).edit().putString("jobs", JSONArray(jobs).toString()).commit()) { "Impossible de sauvegarder les enregistrements." } }
    @Synchronized fun update(c: Context, id: String, edit: (JSONObject) -> Unit) { val all=jobs(c); all.firstOrNull { it.optString("id")==id }?.let(edit); write(c,all) }
    fun directory(c: Context) = File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: c.filesDir, "PinkTV").apply { mkdirs() }
    fun file(c: Context, id: String): File { require(RecordingRules.validId(id)); return File(directory(c), "$id.ts") }
    fun uri(c: Context, id: String): String { val f=file(c,id); require(f.isFile && f.length()>0) { "Fichier local indisponible." }; return Uri.fromFile(f).toString() }
    fun isRunning(c: Context) = jobs(c).any { it.optString("status")=="running" }
    fun hasActive(c: Context) = jobs(c).any { it.optString("status") in RecordingRules.active }
    fun exactAllowed(c: Context) = Build.VERSION.SDK_INT < 31 || (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    private fun pending(c: Context,id: String) = PendingIntent.getBroadcast(c,0,Intent(c,RecordingAlarm::class.java).setData(Uri.parse("pinktv://recording/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun alarm(c: Context, job: JSONObject) {
        val am=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        require(exactAllowed(c)) { "Autorise les alarmes et rappels pour programmer une émission." }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(System.currentTimeMillis()+1000,job.getLong("start")),pending(c,job.getString("id")))
    }
    @Synchronized fun schedule(c: Context, entry: Entry, title: String, start: Long, end: Long): String {
        val now=System.currentTimeMillis(); val begin=maxOf(now,start)
        require(end>begin && end-begin<=4*60*60*1000L) { "Choisis une émission à venir ou en cours, d’une durée maximale de 4 heures." }
        require(directory(c).usableSpace>150L*1024*1024) { "Espace insuffisant. Libère au moins 150 Mo sur cet appareil." }
        val account=Store(c).account() ?: error("Ajoute ton compte Xtream.")
        val all=jobs(c)
        require(all.none { it.optString("status") in RecordingRules.active && RecordingRules.overlap(begin,end,it.getLong("start"),it.getLong("end")) }) { "Un autre enregistrement est déjà prévu à cet horaire. Une seule connexion est utilisée à la fois." }
        val id=UUID.randomUUID().toString()
        val job=JSONObject().put("id",id).put("title",title).put("channel",EntryJson.write(entry)).put("start",begin).put("end",end).put("status","pending").put("owner",FavouriteProfiles.identity(account.server,account.username)).put("message","")
        if(begin>now+2000) require(exactAllowed(c)) { "Autorise les alarmes et rappels dans Android, puis programme l’émission de nouveau." }
        write(c,all+job)
        try { if(begin<=now+2000) start(c,id) else alarm(c,job) } catch(e: Exception) { update(c,id) { it.put("status","failed").put("message","Android n’a pas pu démarrer ou programmer l’enregistrement. Ouvre l’application et réessaie.") }; throw IllegalStateException("Enregistrement non démarré. Vérifie les autorisations Android.") }
        return id
    }
    fun start(c: Context,id: String) { c.startForegroundService(Intent(c,RecordingService::class.java).putExtra("id",id)) }
    @Synchronized fun cancel(c: Context,id: String) {
        update(c,id) { it.put("status","cancelled").put("message","Arrêté à ta demande. Le fichier déjà reçu reste disponible.") }
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c,id))
        RecordingService.cancel(id)
    }
    @Synchronized fun delete(c: Context,id: String) {
        require(RecordingService.activeId!=id) { "L’enregistrement est en cours d’arrêt. Réessaie dans un instant." }
        require(jobs(c).none { it.optString("id")==id && it.optString("status") in RecordingRules.active })
        require(!file(c,id).exists() || file(c,id).delete()) { "Impossible de supprimer le fichier." }
        write(c,jobs(c).filterNot { it.optString("id")==id })
    }
    fun reconcile(c: Context) {
        val now=System.currentTimeMillis()
        jobs(c).forEach { job ->
            val id=job.getString("id")
            when {
                job.optString("status")=="running" && RecordingService.activeId!=id -> update(c,id) { it.put("status","incomplete").put("message","L’appareil ou Android a interrompu l’enregistrement. Le fichier reçu est conservé.") }
                job.optString("status")=="pending" && job.getLong("end")<=now -> update(c,id) { it.put("status","failed").put("message","L’appareil n’était pas disponible à l’heure prévue.") }
                job.optString("status")=="pending" -> runCatching { alarm(c,job) }.onFailure { update(c,id) { it.put("message","Alarmes désactivées. Autorise les alarmes et rappels, puis rouvre Pink TV.") } }
            }
        }
    }
}
class RecordingAlarm: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) { val id=intent.getStringExtra("id") ?: return
        if(Recordings.jobs(context).none { it.optString("id")==id && it.optString("status")=="pending" }) return
        runCatching { Recordings.start(context,id) }.onFailure { Recordings.update(context,id) { it.put("status","failed").put("message","Android a refusé le démarrage en arrière-plan. Ouvre Pink TV pour réessayer.") } }
    }
}
class RecordingBoot: BroadcastReceiver() { override fun onReceive(context: Context,intent: Intent) {
    if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) Recordings.reconcile(context)
} }
