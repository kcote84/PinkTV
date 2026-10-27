package ca.pinktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class HouseUi(private val activity: Activity, private val store: Store, private val home: () -> Unit, private val watch: (Entry) -> Unit) {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var closed=false
    private var navigation: HeaderNavigation?=null
    fun handleBack()=navigation?.handleBack()==true
    fun handleDown()=navigation?.handleDown()==true
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun text(value: String)=TextView(activity).apply { text=value; textSize=16f; setTextColor(TvTheme.muted); setPadding(dp(8),dp(8),dp(8),dp(8)) }
    private fun shape(color: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(12).toFloat() }
    private fun button(parent: LinearLayout,title: String,action: () -> Unit): Button = Button(activity).apply {
        text=title; textSize=16f; isAllCaps=false; setTextColor(Color.WHITE); background=shape(TvTheme.panel); minHeight=dp(52)
        setOnFocusChangeListener { _,focused -> background=shape(if(focused) TvTheme.accent else TvTheme.panel); setTextColor(if(focused) TvTheme.background else Color.WHITE) }
        setOnClickListener { action() }
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(4) })
    }
    private fun error(message: String) { if(!closed && !activity.isFinishing) AlertDialog.Builder(activity).setTitle("Pink TV").setMessage(message).setPositiveButton("Fermer",null).show() }
    fun login(): View {
        val form=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        form.addView(text("Ton abonnement, sur cet appareil. Entre les trois renseignements fournis avec ton compte Xtream Codes."))
        fun field(label: String,value: String,secret: Boolean=false)=EditText(activity).apply {
            hint=label; contentDescription=label; setText(value); setSingleLine(); textSize=17f; setTextColor(Color.WHITE); setHintTextColor(TvTheme.muted)
            inputType=InputType.TYPE_CLASS_TEXT or if(secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14),dp(12),dp(14),dp(12)); background=shape(TvTheme.panel)
            form.addView(this,LinearLayout.LayoutParams(-1,dp(58)).apply { topMargin=dp(12) })
        }
        val account=store.account()
        val server=field("Serveur · https://exemple.com:8080",account?.server.orEmpty())
        val username=field("Nom d’utilisateur",account?.username.orEmpty())
        val password=field("Mot de passe",account?.password.orEmpty(),true)
        val status=text(""); form.addView(status)
        var pending=false
        button(form,"Connecter mon compte") {
            if(pending) return@button
            if(Recordings.hasActive(activity)) { error("Arrête ou annule les enregistrements programmés avant de changer de compte."); return@button }
            val candidate=runCatching {
                Account(AccountRules.server(server.text.toString()),username.text.toString().trim(),password.text.toString()).also { require(it.username.isNotBlank() && it.password.isNotBlank()) { "Entre ton nom d’utilisateur et ton mot de passe." } }
            }.getOrElse { error(it.message ?: "Vérifie les trois champs."); return@button }
            pending=true; status.text="Vérification du compte…"
            worker.execute {
                val result=runCatching { Xtream(candidate).authenticate(); store.save(candidate) }
                main.post { if(!closed && !activity.isFinishing) { pending=false; result.onSuccess { Toast.makeText(activity,"Compte connecté sur cet appareil",Toast.LENGTH_SHORT).show(); home() }.onFailure { status.text="Connexion impossible. Vérifie l’URL, les identifiants et ton abonnement, puis réessaie." } } }
            }
        }
        button(form,"Retour",home)
        form.addView(text("Compte chiffré avec Android Keystore. Favoris et historique propres à ton compte, conservés ici. Aucune chaîne ni aucun abonnement n’est inclus. Si ton fournisseur propose HTTPS, utilise cette adresse."))
        return ScrollView(activity).apply { addView(form) }
    }
    fun record(entry: Entry,programme: Programme?) {
        if(store.account()==null) return
        if(programme==null) {
            AlertDialog.Builder(activity).setTitle("Enregistrer maintenant").setItems(arrayOf("30 minutes","1 heure","2 heures")) { _,i ->
                val now=System.currentTimeMillis(); confirm(entry,entry.name,now,now+listOf(30,60,120)[i]*60000L)
            }.setNegativeButton("Retour",null).show()
        } else confirm(entry,programme.title,programme.start,programme.end)
    }
    private fun confirm(entry: Entry,title: String,start: Long,end: Long) {
        if(end<=System.currentTimeMillis()) { error("Cette émission est déjà terminée. Choisis une émission à venir ou en cours."); return }
        if(start>System.currentTimeMillis()+2000 && !Recordings.exactAllowed(activity)) {
            AlertDialog.Builder(activity).setTitle("Autoriser la programmation")
                .setMessage("Android demande l’autorisation Alarmes et rappels pour démarrer à l’heure choisie. Active-la, puis reviens programmer ton émission.")
                .setPositiveButton("Ouvrir les réglages") { _,_ -> runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${activity.packageName}"))) }.onFailure { error("Ouvre les réglages Android → Applications → Pink TV → Alarmes et rappels.") } }.setNegativeButton("Retour",null).show()
            return
        }
        val stamp=SimpleDateFormat("EEE d MMM · HH:mm",Locale.CANADA_FRENCH)
        AlertDialog.Builder(activity).setTitle("Enregistrer sur cet appareil ?")
            .setMessage("$title\n${entry.name}\n${stamp.format(Date(maxOf(start,System.currentTimeMillis())))} → ${SimpleDateFormat("HH:mm",Locale.CANADA_FRENCH).format(Date(end))}\n\nLa lecture en direct sera arrêtée pendant l’enregistrement. Garde l’appareil allumé et connecté à Internet. Le début déjà diffusé ne peut pas être récupéré. Les fichiers utilisent l’espace de cet appareil.")
            .setNegativeButton("Annuler",null).setPositiveButton("Enregistrer") { _,_ ->
                if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),42)
                runCatching { Recordings.schedule(activity,entry,title,start,end) }.onSuccess { Toast.makeText(activity,"Ajouté à Mes enregistrements",Toast.LENGTH_LONG).show() }.onFailure { error(it.message ?: "Impossible de programmer cet enregistrement.") }
            }.show()
    }
    fun recordings(): View {
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val free=Recordings.directory(activity).usableSpace/(1024L*1024)
        body.addView(text("Sur cet appareil · $free Mo libres\nUn enregistrement à la fois. Les fichiers sont supprimés si tu désinstalles l’application."))
        val result=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(activity).apply { addView(result) }
        var signature=""
        var dialog: AlertDialog?=null
        fun refresh(force: Boolean=false) {
            val jobs=Recordings.jobs(activity).sortedByDescending { it.optLong("start") }
            val next=jobs.joinToString { "${it.optString("id")}:${it.optString("status")}:${Recordings.file(activity,it.getString("id")).length()/1000000}" }
            if((!force && next==signature) || dialog?.isShowing==true) return
            signature=next
            val focused=result.findFocus()?.tag; val y=scroll.scrollY
            result.removeAllViews()
            if(jobs.isEmpty()) result.addView(text("Aucun enregistrement. Choisis une émission dans le guide, ou Enregistrer pendant la lecture d’une chaîne."))
            jobs.forEach { job ->
                val id=job.getString("id"); val status=job.getString("status"); val file=Recordings.file(activity,id)
                val state=when(status) { "pending" -> "Programmé"; "running" -> "En cours"; "complete" -> "Prêt à regarder"; "incomplete" -> "Incomplet"; "cancelled" -> "Arrêté"; else -> "Échec" }
                val stamp=SimpleDateFormat("EEE d MMM · HH:mm",Locale.CANADA_FRENCH).format(Date(job.getLong("start")))
                button(result,"${job.getString("title")}\n$state · $stamp · ${file.length()/1000000} Mo") {
                    val actions=mutableListOf<String>()
                    if(file.length()>0 && status!="running" && RecordingService.activeId!=id) actions.add("Regarder")
                    if(status in RecordingRules.active) actions.add("Arrêter / annuler") else if(RecordingService.activeId!=id) actions.add("Supprimer")
                    actions.add("Détails")
                    dialog=AlertDialog.Builder(activity).setTitle(job.getString("title")).setItems(actions.toTypedArray()) { _,i -> when(actions[i]) {
                        "Regarder" -> watch(Entry(id,job.getString("title"),"","recording"))
                        "Détails" -> dialog=AlertDialog.Builder(activity).setTitle(state).setMessage(job.optString("message").ifBlank { "${file.length()/1000000} Mo · Stocké uniquement sur cet appareil.${if(status=="running") "\nLa lecture sera disponible une fois l’enregistrement terminé ou arrêté." else ""}" }).setPositiveButton("Fermer",null).show()
                        else -> dialog=AlertDialog.Builder(activity).setTitle("${actions[i]} ?").setNegativeButton("Retour",null).setPositiveButton("Confirmer") { _,_ ->
                            runCatching { if(status in RecordingRules.active) Recordings.cancel(activity,id) else Recordings.delete(activity,id) }.onFailure { error("Impossible de modifier cet enregistrement.") }; refresh(true)
                        }.show()
                    } }.setNegativeButton("Retour",null).show()
                }.tag=id
            }
            (0 until result.childCount).map { result.getChildAt(it) }.firstOrNull { it.tag==focused }?.requestFocus()
            scroll.post { scroll.scrollTo(0,y) }
        }
        val refreshButton=button(body,"Actualiser") { refresh(true) }
        body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val ticker=object: Runnable { override fun run() { if(!closed && scroll.isAttachedToWindow) { refresh(); main.postDelayed(this,2000) } } }
        scroll.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { refresh(true); main.post(ticker) }
            override fun onViewDetachedFromWindow(v: View) { main.removeCallbacks(ticker); dialog?.dismiss() }
        })
        navigation=HeaderNavigation(listOf(refreshButton),scroll)
        return body
    }
    fun close() { closed=true; worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
