package ca.pinktv

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService: Service() {
    companion object {
        @Volatile var activeId: String? = null; private set
        @Volatile private var activeService: RecordingService? = null
        var onRecordingStarting: (() -> Unit)? = null
        fun cancel(id: String) { if(activeId==id) activeService?.let { it.cancelled.set(true); it.transport?.disconnect() } }
    }
    @Volatile private var transport: HttpURLConnection? = null
    private val cancelled=AtomicBoolean(false)
    private var ownId: String? = null
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("recording","Enregistrements",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(title: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,RecordingService::class.java).setAction("stop").putExtra("id",activeId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"recording").setSmallIcon(R.drawable.ic_pinktv).setContentTitle("Pink TV · Enregistrement").setContentText(title).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") { activeId?.let { Recordings.cancel(this,it) }; return START_NOT_STICKY }
        val id=intent?.getStringExtra("id")
        if(activeId!=null) { if(id!=null && id!=activeId) Recordings.update(this,id) { it.put("status","failed").put("message","Un autre enregistrement est en cours.") }; return START_NOT_STICKY }
        startForeground(71,notification("Préparation…"))
        val job=Recordings.jobs(this).firstOrNull { it.optString("id")==id && it.optString("status")=="pending" }
        if(job==null || job.getLong("end")<=System.currentTimeMillis()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        activeId=id; ownId=id; activeService=this; cancelled.set(false)
        onRecordingStarting?.invoke()
        Recordings.update(this,id!!) { it.put("status","running").put("message","") }
        getSystemService(NotificationManager::class.java).notify(71,notification(job.getString("title")))
        wake=(getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PinkTV:recording").apply { acquire((job.getLong("end")-System.currentTimeMillis()+60000).coerceIn(1000,4*60*60*1000L+60000)) }
        val end=Runnable { if(activeId==id) transport?.disconnect() }
        main.postDelayed(end,(job.getLong("end")-System.currentTimeMillis()).coerceAtLeast(0))
        worker.execute {
            val file=Recordings.file(this,id)
            var reason=""
            try {
                val account=Store(this).account() ?: error("account")
                check(FavouriteProfiles.identity(account.server,account.username)==job.getString("owner"))
                val url=Xtream(account).stream(EntryJson.read(job.getJSONObject("channel")),false)
                val c=Network.connection(url).apply { readTimeout=15000 }; transport=c
                check(c.responseCode in 200..299)
                c.inputStream.use { input -> file.outputStream().use { output ->
                    val bytes=ByteArray(64*1024)
                    while(!cancelled.get() && System.currentTimeMillis()<job.getLong("end")) {
                        check(file.parentFile!!.usableSpace>64L*1024*1024) { "space" }
                        val count=input.read(bytes); if(count<0) break
                        output.write(bytes,0,count)
                    }
                } }
            } catch(e: Exception) { reason=if(e.message=="space") "Espace de stockage insuffisant. Le fichier reçu est conservé." else "Le flux ou l’appareil a interrompu l’enregistrement. Le fichier reçu est conservé." }
            finally {
                transport?.disconnect(); transport=null
                val reached=System.currentTimeMillis()>=job.getLong("end")
                Recordings.update(this,id) { if(it.optString("status")=="running") it.put("status",RecordingRules.finalStatus(file.length(),reached)).put("message",if(reached && file.length()>0) "" else reason.ifBlank { "Le flux s’est arrêté avant la fin de l’émission." }) }
                main.post { main.removeCallbacks(end); wake?.let { if(it.isHeld) it.release() }; wake=null; ownId=null; if(activeService===this) { activeId=null; activeService=null }; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int,fgsType: Int) {
        ownId?.let { id -> Recordings.update(this,id) { it.put("status","incomplete").put("message","La limite Android d’enregistrement en arrière-plan a été atteinte.") } }; cancelled.set(true); transport?.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        cancelled.set(true); transport?.disconnect()
        ownId?.let { id -> Recordings.update(this,id) { if(it.optString("status")=="running") it.put("status","incomplete").put("message","Android a arrêté le service. Le fichier reçu est conservé.") } }
        if(activeService===this) { activeId=null; activeService=null }; wake?.let { if(it.isHeld) it.release() }; wake=null; main.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy()
    }
}
