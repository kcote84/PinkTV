package ca.pinktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.URI
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private val work = Executors.newSingleThreadExecutor()
    private val rowsWorker = Executors.newSingleThreadExecutor()
    private var rowsTask: java.util.concurrent.Future<*>? = null
    private var rowsRequest = 0
    private var rowsLoading: View? = null
    private val posters = PosterLoader()
    private val main = Handler(Looper.getMainLooper())
    private var requestId = 0
    private var screen = "home"
    private var current: Entry? = null
    private val house by lazy { HouseUi(this,store,{ guideScreen?.close();guideScreen=null;home() },{ play(it) }) }
    private var flowDialog: AlertDialog? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var playerStage: FrameLayout? = null
    private var playerHeading: TextView? = null
    private var playerActions: View? = null
    private var playerFavourite: Button? = null
    private var miniGuide: MiniGuideOverlay? = null
    private var playbackPosition = 0L
    private var playbackStarted = false
    private val progressTick = object : Runnable {
        override fun run() { if(player?.isPlaying == true) saveProgress(); if(player != null) main.postDelayed(this, 5000) }
    }
    private var stopped = false
    private var audioRestored = false
    private var compatibleLive = false
    private var compatibilityCheck: Pair<ExoPlayer, String>? = null
    private var retries = 0
    private var retryTask: Runnable? = null
    private var busy = false
    private var pendingInstall: File? = null
    private var playbackEndDialog: AlertDialog? = null
    private var browseTitle = ""
    private var browseKind = "live"
    private var browseSection = ""
    private var browseCategory: String? = null
    private var categories = listOf<Category>()
    private var items = listOf<Entry>()
    private var list: AbsListView? = null
    private var pageNavigation: HeaderNavigation? = null
    private var search: EditText? = null
    private var gallery = false
    private var listTitle = ""
    private var listSection = ""
    private var listFilters = false
    private var shownEntries = emptyList<Entry>()
    private var lastListSelection = 0
    private data class ListState(val title: String, val section: String, val filters: Boolean, val screen: String,
        val categories: List<Category>, val entries: List<Entry>, val query: String, val selected: Int, val gallery: Boolean,
        val libraryKind: String?, val favouriteKind: String?, val continuing: Boolean)
    private var playerOrigin: ListState? = null
    private var seriesOrigin: ListState? = null
    private var libraryKind: String? = null
    private var favouriteKind: String? = null
    private var continuing = false
    private var continueOrigin: ListState? = null
    private var detailOrigin: ListState? = null
    private var mediaDetails: MediaDetails? = null
    private var mediaScreen: MediaScreen? = null
    private var mediaSeason: String? = null
    private var mediaEpisode: String? = null
    private var guideScreen: TvGuideScreen? = null
    private var returnToGuide = false
    private val bg = TvTheme.background
    private val panel = TvTheme.panel
    private val accent = TvTheme.accent
    private val muted = TvTheme.muted
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        RecordingService.onRecordingStarting = { if(player != null && current?.kind != "recording") noFlow() }
        Recordings.reconcile(this)
        if(DeviceLayout.isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        home()
    }
    override fun onStart() {
        super.onStart()
        if (screen == "guide") guideScreen?.resume()
        if (screen == "player" && player == null && current != null && flowDialog == null) play(current!!, playbackPosition)
        stopped = false
        if (screen == "settings" && pendingInstall != null) settings()
    }
    override fun onStop() {
        guideScreen?.pause()
        stopped = true
        playbackPosition = player?.currentPosition ?: playbackPosition
        releasePlayer()
        super.onStop()
    }
    override fun onPause() { saveProgress(); super.onPause() }
    override fun onDestroy() { requestId++; RecordingService.onRecordingStarting=null; store.onFavouritesEdited=null; guideScreen?.close(); releasePlayer(); posters.close(); work.shutdownNow(); rowsWorker.shutdownNow();house.close();main.removeCallbacksAndMessages(null); super.onDestroy() }
    @Deprecated("TV back navigation")
    override fun onBackPressed() {
        if(pageNavigation?.handleBack() == true) return
        if(screen == "details" && mediaScreen?.handleBack() == true) return
        if(screen == "recordings" && house.handleBack() == true) return
        when (screen) {
            "home" -> finish()
            "player" -> if(miniGuide?.isOpen == true) miniGuide?.close() else leavePlayer()
            "guide" -> if(guideScreen?.handleBack() != true) { releasePlayer(); current = null; home() }
            "episodes" -> seriesOrigin?.let { restoreList(it) } ?: home()
            "details" -> leaveDetails()
            "continuing" -> continueOrigin?.let { restoreList(it) } ?: home()
            "sound", "safe", "advanced" -> settings()
            else -> { releasePlayer(); current = null; home() }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            (pageNavigation?.handleDown() == true || screen == "details" && mediaScreen?.handleDown() == true || screen == "recordings" && house.handleDown() == true)) return true
        if (screen == "player" && keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { player?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
        if (screen == "player" && keyCode == KeyEvent.KEYCODE_MENU) { if(current?.kind == "live") openMiniGuide() else audioMenu(); return true }
        if (list?.hasFocus() == true && keyCode == KeyEvent.KEYCODE_MENU) {
            shownEntries.getOrNull(list!!.selectedItemPosition)?.let { entryMenu(it) }; return true
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun shape(color: Int, border: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); if (border != null) setStroke(dp(2), border)
    }
    private fun label(value: String, size: Float = 18f, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5)) }
    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        minHeight = dp(48); setPadding(dp(18), dp(8), dp(18), dp(8)); background = shape(panel)
        defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, focus -> background = shape(if (focus) accent else panel, if(focus) Color.WHITE else null); setTextColor(if (focus) bg else Color.WHITE) }
        setOnClickListener { action() }
    }
    private fun addButton(parent: LinearLayout, title: String, action: () -> Unit) = button(title, action).also {
        parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
    }
    private fun page(name: String, subtitle: String, prominentTitle: Boolean = false): LinearLayout {
        if (name != "guide") guideScreen?.pause()
        requestId++; busy = false; screen = name; list = null; pageNavigation = null; search = null; gallery = false; posters.cancelPending()
        rowsRequest++; rowsTask?.cancel(true); rowsLoading = null; shownEntries = emptyList()
        libraryKind = null; favouriteKind = null; continuing = false
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; TvSafeArea.apply(this, this@MainActivity, store.prefs.getBoolean("wide_safe_area", false)); setBackgroundColor(bg) }
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Pink TV", 28f, accent).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if(!DeviceLayout.compact(this)) header.addView(label("À TON RYTHME", 12f, muted))
        root.addView(header)
        root.addView(label(subtitle, if(prominentTitle) 22f else 16f, if(prominentTitle) Color.WHITE else muted).apply {
            if(prominentTitle) setTypeface(null, Typeface.BOLD)
        })
        return root
    }
    private fun home() {
        playerOrigin = null; seriesOrigin = null; returnToGuide = false
        detailOrigin = null; mediaDetails = null; mediaScreen = null; continueOrigin = null
        page("home", "Ta soirée, tout simplement.")
        if (store.account() == null) root.addView(label("Bienvenue · Ajoute ton compte Xtream dans Réglages.", 14f, muted))
        fun addTile(row: LinearLayout, tile: View, weight: Float = 1f) {
            row.addView(tile, LinearLayout.LayoutParams(0, -1, weight).apply {
                if(row.childCount > 0) leftMargin = dp(12)
            })
        }
        val primary = LinearLayout(this).apply { isBaselineAligned = false }
        val guide = homeTile("Télé", R.drawable.home_guide, true) { openTvGuide() }
        addTile(primary, guide, 1.25f)
        listOf("Films" to R.drawable.home_films, "Séries" to R.drawable.home_series).forEach { (title, icon) ->
            addTile(primary, homeTile(title, icon) {
                if(store.account() == null) demo(title) else browse(title, if(title == "Films") "vod" else "series", title)
            })
        }
        addTile(primary, homeTile("Mes favoris", R.drawable.home_favourites) { if(store.account() == null) demo("Favoris") else favourites() })
        root.addView(primary, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addTile(footer, button("Rechercher") { searchHome() })
        addTile(footer, button("Enregistrements") { recordings() })
        addTile(footer, button("Plus") {
            AlertDialog.Builder(this).setTitle("Plus de choix").setItems(arrayOf("Sports", "Adultes 18+")) { _, which ->
                if(which == 1) adults() else if(store.account() == null) demo("Sports") else browse("Sports", "live", "Sports")
            }.setNegativeButton("Retour", null).show()
        })
        addTile(footer, button("Réglages") { settings() })
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(16) })
        if(DeviceLayout.compact(this)) {
            primary.visibility = View.GONE; footer.visibility = View.GONE
            val mobile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            listOf("Télé" to R.drawable.home_guide, "Films" to R.drawable.home_films, "Séries" to R.drawable.home_series, "Mes favoris" to R.drawable.home_favourites).chunked(2).forEach { pair ->
                val row = LinearLayout(this)
                pair.forEach { (title, icon) -> row.addView(homeTile(title,icon) { when(title) { "Télé" -> openTvGuide(); "Films" -> if(store.account()==null) demo(title) else browse(title,"vod",title); "Séries" -> if(store.account()==null) demo(title) else browse(title,"series",title); else -> favourites() } }, LinearLayout.LayoutParams(0,dp(140),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) }) }
                mobile.addView(row)
            }
            addButton(mobile,"Rechercher") { searchHome() }; addButton(mobile,"Mes enregistrements") { recordings() }
            addButton(mobile,"Sports et autres catégories") { adultsOrSports() }; addButton(mobile,"Réglages") { settings() }
            root.addView(ScrollView(this).apply { addView(mobile) },LinearLayout.LayoutParams(-1,0,1f))
        } else guide.requestFocus()
    }
    private fun homeTile(title: String, iconId: Int, prominent: Boolean = false, action: () -> Unit): View = LinearLayout(this).apply {
        id = View.generateViewId(); orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        isFocusable = true; isClickable = true; defaultFocusHighlightEnabled = false
        contentDescription = title; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        }
        setPadding(dp(18), dp(12), dp(18), dp(12))
        val icon = getDrawable(iconId)!!.mutate().apply { setTint(accent) }
        addView(ImageView(this@MainActivity).apply {
            setImageDrawable(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(if(prominent) 42 else 32), dp(if(prominent) 42 else 32)))
        val caption = label(title, if(prominent) 27f else 21f).apply {
            setTypeface(null, Typeface.BOLD); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setOnFocusChangeListener { _, focused ->
            background = shape(if(focused) accent else panel, if(focused) Color.WHITE else TvTheme.live)
            caption.setTextColor(if(focused) bg else Color.WHITE); icon.setTint(if(focused) bg else accent)
        }
        background = shape(panel, TvTheme.live)
        setOnClickListener { action() }
    }
    private fun adultsOrSports() { AlertDialog.Builder(this).setTitle("Plus de choix").setItems(arrayOf("Sports", "Adultes 18+")) { _, i -> if(i==1) adults() else if(store.account()==null) demo("Sports") else browse("Sports","live","Sports") }.show() }
    private fun adults() {
        if (store.account() == null) { demo("Adultes 18+"); return }
        AlertDialog.Builder(this).setTitle("Adultes 18+")
            .setItems(arrayOf("Chaînes en direct", "Films", "Séries")) { _, choice ->
                browse("Adultes · ${listOf("Chaînes", "Films", "Séries")[choice]}", listOf("live", "vod", "series")[choice], "Adultes")
            }.setNegativeButton("Retour", null).show()
    }
    private fun openTvGuide(channelId: String? = null, searching: Boolean = false) {
        releasePlayer(); current = null
        val account = store.account()
        if (guideScreen == null || guideScreen?.account != account) {
            guideScreen?.close()
            guideScreen = TvGuideScreen(this, account, store, { entry -> returnToGuide = true; playerOrigin = null; play(entry) }, { login() }, { entry, programme -> house.record(entry,programme) })
        }
        page("guide", "")
        root.removeAllViews(); root.setPadding(0, 0, 0, 0)
        val guide = guideScreen!!
        (guide.view.parent as? ViewGroup)?.removeView(guide.view)
        root.addView(guide.view, LinearLayout.LayoutParams(-1, -1))
        guide.resume(channelId)
        if(searching) guide.findChannel()
    }
    private fun searchHome() {
        AlertDialog.Builder(this).setTitle("Que veux-tu trouver ?").setItems(arrayOf("Une chaîne", "Un film", "Une série")) { _, which ->
            if(which == 0) openTvGuide(searching = true)
            else if(store.account() == null) demo(if(which == 1) "Films" else "Séries")
            else browse(if(which == 1) "Films" else "Séries", if(which == 1) "vod" else "series", "", searching = true)
        }.setNegativeButton("Retour", null).show()
    }
    private fun demo(section: String) {
        page("demo", "$section · Mode démo")
        root.addView(label("Ton espace $section", 28f))
        root.addView(label("Ajoute ton compte Xtream pour retrouver tes contenus.\nAucune chaîne n’est fournie avec Pink TV.", 18f, muted))
        addButton(root, "Ajouter un compte Xtream") { login() }
        addButton(root, "Retour à l’accueil") { home() }
    }
    private fun settings() {
        page("settings", "Une fois réglé, on profite.")
        val scroll = ScrollView(this); val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(body); root.addView(scroll)
        body.addView(label("Le quotidien", 18f, accent))
        if(store.account() == null) addButton(body, "Ajouter un compte Xtream") { login() }
        addButton(body, "Son : ${audioModeLabel()}") { soundSettings() }
        addButton(body, "Image coupée sur les bords ?") { safeAreaSettings() }
        addButton(body, "Mise à jour · ${BuildConfig.VERSION_NAME}") { checkUpdate() }
        if (pendingInstall != null) addButton(body, "Installer la mise à jour") { installUpdate() }
        addButton(body, "Options avancées") { advancedSettings() }
        addButton(body, "Retour") { home() }
        body.addView(label("Ton compte et tes favoris sont conservés lors des mises à jour.", 14f, muted))
        (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<Button>().firstOrNull()?.let {
            pageNavigation = HeaderNavigation(listOf(it), scroll)
        }
    }
    private fun safeAreaSettings() {
        page("safe", "Tout doit rester à l’intérieur du cadre rose.")
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = shape(panel, accent)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(12) })
        preview.addView(label("Vois-tu les quatre côtés ?", 22f, accent))
        preview.addView(label("Si un bord disparaît, choisis Plus de marge.", 16f, muted))
        val wide = store.prefs.getBoolean("wide_safe_area", false)
        addButton(preview, if(wide) "✓ Plus de marge" else "Plus de marge") {
            store.prefs.edit().putBoolean("wide_safe_area", true).apply(); safeAreaSettings()
        }
        addButton(preview, if(!wide) "✓ Marge normale" else "Marge normale") {
            store.prefs.edit().putBoolean("wide_safe_area", false).apply(); safeAreaSettings()
        }
        addButton(root, "Terminé") { settings() }
    }
    private fun soundSettings() {
        page("sound", "Le son, à ton goût.")
        addButton(root, "Sortie préférée : ${audioModeLabel()}") {
            AlertDialog.Builder(this).setTitle("Préférence audio").setItems(arrayOf("Automatique", "Stéréo", "Cinéma / 5.1")) { _, which ->
                store.prefs.edit().putString("audio_mode", listOf("auto", "stereo", "surround")[which]).apply(); soundSettings()
            }.show()
        }
        addButton(root, "Éviter la narration : ${if(store.prefs.getBoolean("avoid_description", true)) "oui" else "non"}") {
            store.prefs.edit().putBoolean("avoid_description", !store.prefs.getBoolean("avoid_description", true)).apply(); soundSettings()
        }
        root.addView(label("Stéréo adapte le son de tout ce que tu regardes : chaînes, films, séries et enregistrements. Le choix est propre à cette TV.\nUne narration déjà mélangée au son ne peut pas être retirée.", 16f, muted))
        addButton(root, "Retour") { settings() }
    }
    private fun advancedSettings() {
        page("advanced", "Options avancées")
        addButton(root, if(store.account() == null) "Ajouter un compte Xtream" else "Modifier mon compte Xtream") { login() }
        root.addView(label("Connexion directe au fournisseur. Compte chiffré, favoris et historique conservés sur cet appareil.",16f,muted))
        addButton(root, "Licences audio") { message("Licences audio", assets.open("audio-licenses.txt").bufferedReader().use { it.readText() }) }
        addButton(root, "Retour") { settings() }
    }
    private fun audioMode() = store.prefs.getString("audio_mode", if(store.prefs.getBoolean("stereo", false)) "stereo" else "auto") ?: "auto"
    private fun localStereo() = AudioCompatibility.localStereo(audioMode(), FfmpegLibrary.isAvailable())
    private fun audioModeLabel() = when(audioMode()) { "stereo" -> "Stéréo"; "surround" -> "Cinéma / 5.1"; else -> "Automatique" }
    private fun field(hintText: String, value: String = "", secret: Boolean = false) = EditText(this).apply {
        hint = hintText; setText(value); textSize = 16f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(muted)
        inputType = if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setPadding(dp(12), dp(8), dp(12), dp(8)); background = shape(panel); contentDescription = hintText
    }
    private fun login() { page("login","Mon compte Xtream");root.addView(house.login()) }
    private fun recordings() {
        if(store.account()==null) { login();return }
        returnToGuide=false;playerOrigin=null;mediaDetails=null
        page("recordings","Mes enregistrements",prominentTitle=true)
        root.addView(house.recordings(),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun noFlow() {
        if(flowDialog!=null) return
        val entry=current ?: return
        val position=player?.currentPosition ?: playbackPosition
        releasePlayer()
        flowDialog=AlertDialog.Builder(this).setTitle("Aucun flux disponible actuellement")
            .setMessage("La connexion est utilisée par un enregistrement ou refusée par ton fournisseur. Arrête l’enregistrement ou réessaie plus tard.")
            .setNeutralButton("Mes enregistrements") { _,_ -> flowDialog=null;recordings() }
            .setPositiveButton("Réessayer") { _,_ -> flowDialog=null;play(entry,position) }
            .setNegativeButton("Retour") { _,_ -> flowDialog=null;leavePlayer() }
            .setCancelable(false).show()
    }
    private fun favourites(kind: String = "live") {
        page("browse", "Mes favoris", prominentTitle = true)
        favouriteKind = kind
        items = store.favouriteEntries().filter { it.kind == kind }; categories = emptyList()
        buildList(when(kind) { "vod" -> "Mes films"; "series" -> "Mes séries"; "episode" -> "Mes épisodes"; else -> "Mes chaînes" }, "Favoris", false, kind in listOf("vod", "series"))
    }
    private fun browse(title: String, kind: String, section: String, category: String? = null, searching: Boolean = false) {
        browseTitle = title; browseKind = kind; browseSection = section; browseCategory = category
        page("browse", if(kind in listOf("vod", "series")) "$title · En français" else "$title · Choisis une catégorie ou cherche un titre.")
        libraryKind = kind.takeIf { it in listOf("vod", "series") }
        async("Chargement du catalogue…", {
            val api = Xtream(store.account() ?: error("Connecte ton compte."))
            api.categories(kind) to api.entries(kind, category)
        }) { (cats, entries) -> categories = cats; items = entries; buildList(title, section, true, kind in listOf("vod", "series"), !searching); if(searching) search?.requestFocus() }
    }
    private fun buildList(title: String, section: String, filters: Boolean, covers: Boolean = false, focusResults: Boolean = true) {
        listTitle = title; listSection = section; listFilters = filters; gallery = covers
        val headerTargets = mutableListOf<View>()
        if(favouriteKind != null) {
            val tabs = LinearLayout(this)
            listOf("live" to "Chaînes", "vod" to "Films", "series" to "Séries", "episode" to "Épisodes").forEach { (kind, name) ->
                val tab = button(if(favouriteKind == kind) "✓ $name" else name) { favourites(kind) }.apply {
                    isSelected = favouriteKind == kind
                    fun style(focused: Boolean) {
                        background = shape(if(focused) accent else if(isSelected) TvTheme.selectedChannel else panel, if(focused) Color.WHITE else if(isSelected) accent else null)
                        setTextColor(if(focused) bg else Color.WHITE)
                    }
                    style(false); setOnFocusChangeListener { _, focused -> style(focused) }
                }
                tabs.addView(tab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if(kind != "episode") rightMargin = dp(8) })
                headerTargets.add(tab)
            }
            root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(12) })
        } else if(!gallery) root.addView(label(title, 25f))
        if(libraryKind != null && !continuing) {
            val kind = libraryKind!!
            val recent = continuingRecords(kind, section == "Adultes", categories)
            if(recent.isNotEmpty()) {
                val row = LinearLayout(this)
                val first = recent.first()
                val entry = if(kind == "series") first.seriesEntry() else first.entry
                row.addView(button("Continuer · ${entry.name}") { openMedia(entry) }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.addView(button("Tout reprendre (${recent.size})") { continueWatching(kind, section == "Adultes") }, LinearLayout.LayoutParams(dp(if(DeviceLayout.compact(this)) 125 else 200), dp(48)).apply { leftMargin = dp(8) })
                root.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if(favouriteKind != null) { setPadding(dp(8), dp(4), dp(8), dp(4)); background = shape(TvTheme.row) }
        }
        val query = field(if(favouriteKind != null) "Rechercher dans mes favoris" else if(continuing) "Rechercher dans Continuer" else if(libraryKind != null) "Rechercher ${if(libraryKind == "vod") "un film" else "une série"} en français" else "Rechercher…"); search = query
        controls.addView(query, LinearLayout.LayoutParams(0, dp(46), 1f))
        controls.addView(button("Chercher") { renderRows(section) }, LinearLayout.LayoutParams(dp(110), dp(46)).apply { leftMargin = dp(8) })
        if(gallery || favouriteKind != null) controls.addView(button("Effacer") { query.setText(""); renderRows(section, 0) }, LinearLayout.LayoutParams(dp(90), dp(46)).apply { leftMargin = dp(8) })
        if(filters && !gallery) controls.addView(button("Catégories") { chooseCategory() }, LinearLayout.LayoutParams(dp(130), dp(46)).apply { leftMargin = dp(8) })
        if(gallery) controls.addView(button("Options") { selectedEntry()?.let { entryMenu(it) } ?: toast("Choisis d’abord un titre.") }, LinearLayout.LayoutParams(dp(110), dp(46)).apply { leftMargin = dp(8) })
        if(DeviceLayout.compact(this)) {
            controls.removeView(query)
            root.addView(query,LinearLayout.LayoutParams(-1,dp(50)))
            root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(controls) })
        } else root.addView(controls)
        if(headerTargets.isEmpty()) headerTargets.addAll((0 until controls.childCount).map { controls.getChildAt(it) })
        if(!gallery) root.addView(label("OK : ouvrir  ·  Menu : favoris  ·  Appui long OK : favori", 13f, muted))
        list = if(gallery) GridView(this).apply {
            numColumns = LayoutRules.posterColumns(resources.configuration.screenWidthDp)
            horizontalSpacing = dp(12); verticalSpacing = dp(14); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(0, dp(12), 0, dp(4)); clipToPadding = false
        } else ListView(this).apply { dividerHeight = dp(6); setPadding(0, dp(4), 0, 0); clipToPadding = false }
        list?.isFocusable = true
        list?.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        lastListSelection = 0
        list?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { lastListSelection = position }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val content = FrameLayout(this)
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        val empty = label("", 18f, muted).apply { gravity = Gravity.CENTER; isFocusable = true; visibility = View.GONE }
        content.addView(empty, FrameLayout.LayoutParams(-1, -1)); list?.emptyView = empty
        rowsLoading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true
            addView(ProgressBar(this@MainActivity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(label("Un instant…", 16f, muted))
        }
        content.addView(rowsLoading, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { if(favouriteKind != null) topMargin = dp(4) })
        val selectedHeader = headerTargets.firstOrNull { it.isSelected }
        pageNavigation = HeaderNavigation(headerTargets, content, selectedHeader, list,
            (0 until controls.childCount).map { controls.getChildAt(it) },
            itemKey = { shownEntries.getOrNull(it)?.key })
        selectedHeader?.requestFocus()
        renderRows(section, focus = focusResults)
    }
    private fun renderRows(section: String, selection: Int = lastListSelection, focus: Boolean = true, quiet: Boolean = false) {
        if(focus) pageNavigation?.beginContentRequest()
        val query = search?.text.toString()
        val names = categories.associate { it.id to it.name }
        val favourites = store.favourites()
        val source = items; val covers = gallery; val frenchOnly = covers && favouriteKind == null && !continuing; val target = list ?: return
        val token = ++rowsRequest; val pageToken = requestId
        val hadFocus = target.hasFocus()
        val selectedKey = if(quiet) shownEntries.getOrNull(target.selectedItemPosition)?.key else null
        rowsTask?.cancel(true)
        if(!quiet) { rowsLoading?.visibility = View.VISIBLE; target.visibility = View.INVISIBLE; target.emptyView?.visibility = View.GONE }
        if(focus && hadFocus) rowsLoading?.requestFocus()
        val focusAtStart = currentFocus
        rowsTask = rowsWorker.submit {
            val result = runCatching {
                val selected = CatalogueSelection.select(source, names, favourites, section, frenchOnly, query)
                val labels = if(covers) emptyList() else selected.map { "${if(it.key in favourites) "★  " else ""}${it.name}\n${names[it.category] ?: when(it.kind) { "live" -> "Chaîne en direct"; "vod" -> "Film"; "series" -> "Série"; else -> "Épisode" }}" }
                selected to labels
            }
            if(Thread.currentThread().isInterrupted) return@submit
            main.post {
                if(isFinishing || isDestroyed || pageToken != requestId || token != rowsRequest || list !== target) return@post
                rowsTask = null; rowsLoading?.visibility = View.GONE
                val (shown, labels) = result.getOrElse { message("Catalogue", "La liste n’a pas pu être affichée. Réessaie ta recherche."); emptyList<Entry>() to emptyList<String>() }
                shownEntries = shown
                (list?.emptyView as? TextView)?.text = when {
                    section == "Favoris" && query.isNotBlank() -> "Aucun favori ne correspond. Efface la recherche pour tout revoir."
                    section == "Favoris" -> "Tes favoris apparaîtront ici. Ajoute une étoile depuis une fiche ou le guide."
                    continuing && query.isNotBlank() -> "Aucun résultat dans Continuer. Efface la recherche pour tout revoir."
                    continuing -> "Rien à reprendre pour le moment. Choisis un titre et commence à regarder."
                    gallery -> "Aucun titre en français dans cette sélection. Essaie une autre recherche."
                    else -> "Aucun résultat. Essaie une autre recherche ou catégorie."
                }
                if(gallery) {
                    posters.cancelPending()
                    val inset = TvSafeArea.insets(this, store.prefs.getBoolean("wide_safe_area", false))
                    val columns = (list as GridView).numColumns
                    val width = (resources.displayMetrics.widthPixels - 2 * inset.horizontal - (columns - 1) * dp(12)) / columns
                    list?.adapter = PosterAdapter(this, shown, favourites, posters, ((width - dp(8)) * 1.5f).toInt(), progressLabels())
                } else {
                    list?.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
                            textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(16), dp(8), dp(16), dp(8)); minHeight = dp(68)
                            background = android.graphics.drawable.StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_selected), shape(TvTheme.selected, accent))
                                addState(intArrayOf(android.R.attr.state_pressed), shape(TvTheme.selected, accent))
                                addState(intArrayOf(), shape(panel))
                            }
                        }
                    }
                }
                if(shown.isEmpty()) { list?.setOnItemClickListener(null); list?.setOnItemLongClickListener(null) }
                else {
                    list?.setOnItemClickListener { _, _, index, _ -> lastListSelection = index; openEntry(shown[index]) }
                    list?.setOnItemLongClickListener { _, _, index, _ -> changeFavourite(shown[index]) { renderRows(section, index) }; true }
                }
                target.visibility = if(shown.isEmpty()) View.GONE else View.VISIBLE
                target.emptyView?.visibility = if(shown.isEmpty()) View.VISIBLE else View.GONE
                lastListSelection = (selectedKey?.let { key -> shown.indexOfFirst { it.key == key }.takeIf { it >= 0 } } ?: selection).coerceIn(0, (shown.size - 1).coerceAtLeast(0)); target.setSelection(lastListSelection)
                if(quiet && hadFocus && pageNavigation?.parked != true && pageNavigation?.holdsHeaderFocus() != true && shown.isNotEmpty()) target.requestFocus()
                if(focus && pageNavigation?.parked != true && (currentFocus == null || currentFocus === focusAtStart || currentFocus === rowsLoading)) {
                    if(shown.isEmpty()) target.emptyView?.requestFocus() else target.requestFocus()
                }
            }
        }
    }
    private fun selectedEntry() = shownEntries.getOrNull(list?.selectedItemPosition?.takeIf { it >= 0 } ?: lastListSelection)
    private fun captureList(): ListState? = if (screen in listOf("browse", "episodes", "continuing") && list != null)
        ListState(listTitle, listSection, listFilters, screen, categories, items, search?.text.toString(), lastListSelection, gallery, libraryKind, favouriteKind, continuing) else null
    private fun restoreList(state: ListState) {
        page(state.screen, when { state.favouriteKind != null -> "Mes favoris"; state.continuing -> state.title; state.gallery -> "${state.title} · En français"; else -> state.title }, prominentTitle = state.favouriteKind != null); categories = state.categories; items = state.entries
        libraryKind = state.libraryKind; favouriteKind = state.favouriteKind; continuing = state.continuing
        if(continuing) items = continueEntries(libraryKind!!, state.section == "Adultes")
        buildList(state.title, state.section, state.filters, state.gallery); search?.setText(state.query); renderRows(state.section, state.selected)
    }
    private fun leavePlayer() {
        val entry = current
        releasePlayer(); current = null
        if(entry?.kind == "recording") { recordings();return }
        if(mediaDetails?.entry?.key == entry?.key || entry?.kind == "episode" && mediaDetails?.entry?.id == entry.seriesId) renderMedia()
        else if(returnToGuide) openTvGuide() else playerOrigin?.let { restoreList(it) } ?: home()
    }
    private fun entryMenu(entry: Entry) {
        val options = mutableListOf(if(entry.kind in listOf("series", "vod")) "Voir la fiche" else "Regarder", if(entry.key in store.favourites()) "Retirer des favoris" else "Ajouter aux favoris")
        if(entry.kind == "live") options.add("Guide TV · cette chaîne")
        if(entry.kind == "episode" && entry.seriesId.isNotEmpty()) options.add("Voir la série")
        AlertDialog.Builder(this).setTitle(entry.name).setItems(options.toTypedArray()) { _, which -> when(which) {
            0 -> openEntry(entry)
            1 -> changeFavourite(entry) { renderRows(listSection) }
            2 -> if(entry.kind == "episode") openMedia(WatchProgress(entry, 0, 0, false, 0).seriesEntry(), preferredEpisode = entry.id, preferredSeason = entry.season.toString()) else openTvGuide(entry.id)
        } }.setNegativeButton("Fermer", null).show()
    }
    private fun changeFavourite(entry: Entry, changed: () -> Unit) {
        val added = store.toggle(entry); changed()
        FavouriteFeedback.show(this, entry, added) { store.setFavourite(entry, true); changed() }
    }
    private fun chooseCategory() {
        val options = listOf(Category("", "Toutes les catégories")) + categories
        AlertDialog.Builder(this).setTitle("Catégories du fournisseur").setItems(options.map { it.name }.toTypedArray()) { _, which -> browse(browseTitle, browseKind, if(browseSection == "Adultes") "Adultes" else "", options[which].id.ifEmpty { null }) }.setNegativeButton("Annuler", null).show()
    }
    private fun progressLabels(): Map<String, String> {
        val records = store.history()
        val result = records.associate { it.entry.key to WatchHistory.badge(it) }.toMutableMap()
        records.filter { it.entry.seriesId.isNotEmpty() }.distinctBy { it.entry.seriesId }.forEach {
            result["series:${it.entry.seriesId}"] = "${if(it.completed) "Vu" else "En cours"} · S${it.entry.season} E${it.entry.episode}"
        }
        return result
    }
    private fun continuingRecords(kind: String, adults: Boolean, sourceCategories: List<Category>): List<WatchProgress> {
        val names = sourceCategories.associate { it.id to it.name }
        return WatchHistory.continuing(store.history(), kind).filter {
            it.entry.adult == adults && CatalogRules.isFrenchVod(names[it.entry.category].orEmpty(), if(kind == "series") it.entry.seriesName else it.entry.name)
        }
    }
    private fun continueEntries(kind: String, adults: Boolean) = continuingRecords(kind, adults, continueOrigin?.categories.orEmpty())
        .map { if(kind == "series") it.seriesEntry() else it.entry }
    private fun continueWatching(kind: String, adults: Boolean) {
        continueOrigin = captureList()
        page("continuing", if(kind == "vod") "Continuer mes films" else "Continuer mes séries")
        libraryKind = kind; continuing = true
        categories = emptyList(); items = continueEntries(kind, adults)
        buildList(if(kind == "vod") "Mes films en cours" else "Mes séries en cours", if(adults) "Adultes" else "", false, true)
    }
    private fun openEntry(entry: Entry) {
        when(entry.kind) {
            "vod", "series" -> openMedia(entry.copy(adult = entry.adult || listSection == "Adultes" || CatalogRules.isAdult(categories.firstOrNull { it.id == entry.category }?.name.orEmpty(), entry.name)))
            "episode" -> {
                val progress = store.history().firstOrNull { it.entry.key == entry.key }
                if(progress?.resumable == true) AlertDialog.Builder(this).setTitle(entry.name).setItems(arrayOf("Continuer à ${WatchHistory.stamp(progress.positionMs)}", "Recommencer")) { _, which -> play(entry, if(which == 0) progress.positionMs else 0) }.setNegativeButton("Retour", null).show()
                else play(entry)
            }
            else -> play(entry)
        }
    }
    private fun openMedia(entry: Entry, origin: ListState? = captureList(), preferredSeason: String? = null, preferredEpisode: String? = null) {
        detailOrigin = origin; playerOrigin = null; returnToGuide = false
        mediaDetails = null; mediaScreen = null; mediaSeason = preferredSeason; mediaEpisode = preferredEpisode
        page("details", if(entry.kind == "series") "Série · Saisons et épisodes" else "Film")
        async("Chargement de la fiche…", {
            runCatching { Xtream(store.account()!!).details(entry) }.getOrElse { MediaDetails(entry, unavailable = true) }
        }) { details -> mediaDetails = details; renderMedia() }
    }
    private fun renderMedia() {
        val details = mediaDetails ?: return
        page("details", if(details.entry.kind == "series") "Série · Saisons et épisodes" else "Film")
        mediaScreen = MediaScreen(this, details, store, posters, mediaSeason, mediaEpisode,
            { entry, restart ->
                mediaSeason = mediaScreen?.seasonId; mediaEpisode = entry.id
                val position = if(restart) 0 else store.history().firstOrNull { it.entry.key == entry.key }?.takeIf { it.resumable }?.positionMs ?: 0
                play(entry, position)
            }, { entry, changed -> changeFavourite(entry, changed) },
            { openMedia(details.entry, detailOrigin, mediaScreen?.seasonId, mediaScreen?.selectedEpisodeId) }, { leaveDetails() })
        root.addView(mediaScreen!!.view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun leaveDetails() {
        val origin = detailOrigin
        mediaScreen = null; mediaDetails = null; detailOrigin = null
        if(origin != null) restoreList(origin) else home()
    }
    private fun saveProgress(ended: Boolean = false) {
        val entry = current?.takeIf { it.kind in listOf("vod", "episode") } ?: return
        val p = player ?: return
        if(!playbackStarted) return
        val previous = store.history().firstOrNull { it.entry.key == entry.key }
        val duration = p.duration.takeIf { it > 0 } ?: previous?.durationMs ?: 0
        val position = p.currentPosition.coerceAtLeast(0).let { if(duration > 0) it.coerceAtMost(duration) else it }
        val episodes = mediaDetails?.takeIf { it.entry.kind == "series" && it.entry.id == entry.seriesId }?.episodes
        val next = if(episodes != null) episodes.dropWhile { it.entry.key != entry.key }.drop(1).firstOrNull()?.entry else previous?.next
        store.remember(WatchProgress(entry, position, duration, WatchHistory.completed(position, duration, ended || p.playbackState == Player.STATE_ENDED), System.currentTimeMillis(), next))
    }
    private fun releasePlayer() {
        compatibilityCheck=null
        miniGuide?.dispose(); miniGuide = null
        playerStage = null; playerHeading = null; playerActions = null; playerFavourite = null
        saveProgress(); main.removeCallbacks(progressTick)
        playbackEndDialog?.dismiss(); playbackEndDialog = null
        retryTask?.let { main.removeCallbacks(it) }; retryTask = null; playerView?.player = null; player?.release(); player = null; playerView = null
    }
    private fun openMiniGuide() {
        if(screen != "player" || current?.kind != "live" || player == null) return
        if(miniGuide?.isOpen == true) { miniGuide?.close(); return }
        val stage = playerStage ?: return
        val account = store.account() ?: return
        playerView?.hideController(); playerHeading?.visibility = View.GONE; playerActions?.visibility = View.GONE
        if(miniGuide == null) miniGuide = MiniGuideOverlay(this, account, store, stage, { current }, { switchLiveChannel(it) }) {
            playerView?.hideController(); playerView?.requestFocus()
        }
        miniGuide?.open()
    }
    private fun switchLiveChannel(entry: Entry) {
        val p = player ?: return
        if(Recordings.isRunning(this)) { noFlow(); return }
        if(screen != "player" || current?.kind != "live" || entry.kind != "live") return
        if(current?.key == entry.key && p.playerError == null && p.playbackState !in listOf(Player.STATE_IDLE, Player.STATE_ENDED)) return
        retryTask?.let { main.removeCallbacks(it) }; retryTask = null
        current = entry; playbackPosition = 0; playbackStarted = false; audioRestored = false; retries = 0
        compatibilityCheck = null; compatibleLive = false
        playerHeading?.text = entry.name
        playerFavourite?.text = if(entry.key in store.favourites()) "★ Favori" else "☆ Favori"
        p.setMediaItem(MediaItem.fromUri(Xtream(store.account() ?: return).stream(entry, false, compatibleLive)))
        p.prepare(); p.play()
    }
    private fun play(entry: Entry, position: Long = 0L) {
        captureList()?.let { playerOrigin = it }
        releasePlayer(); current = entry; playbackPosition = position; playbackStarted = false; audioRestored = false; retries = 0
        compatibilityCheck = null; compatibleLive = false
        if(entry.kind == "episode") {
            mediaSeason = mediaDetails?.seasons?.firstOrNull { season -> season.episodes.any { it.entry.key == entry.key } }?.id
            mediaEpisode = entry.id
        }
        if(entry.kind != "recording" && Recordings.isRunning(this)) { noFlow(); return }
        page("player", entry.name)
        root.removeAllViews(); root.setPadding(0, 0, 0, 0)
        val stage = FrameLayout(this)
        playerStage = stage
        root.addView(stage, LinearLayout.LayoutParams(-1, -1))
        val inset = TvSafeArea.insets(this, store.prefs.getBoolean("wide_safe_area", false))
        val video = PlayerView(this).apply { id = View.generateViewId(); useController = true; controllerShowTimeoutMs = 5000; setShowSubtitleButton(true); setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        stage.addView(video, FrameLayout.LayoutParams(-1, -1)); playerView = video
        val heading = label(entry.name, 20f, accent).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            background = shape(Color.argb(200, 25, 19, 31))
        }
        playerHeading = heading
        stage.addView(heading, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP).apply { leftMargin = inset.horizontal; rightMargin = inset.horizontal; topMargin = inset.vertical })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); background = shape(Color.argb(210, 25, 19, 31)) }
        playerActions = row
        val favouriteIndex = 2
        fun favouriteLabel() = if((current ?: entry).key in store.favourites()) "★ Favori" else "☆ Favori"
        val playbackActions = mutableListOf<Pair<String, () -> Unit>>("Son" to { audioMenu() }, "Sous-titres" to { subtitleMenu() }, favouriteLabel() to {
            changeFavourite(current ?: entry) { (row.getChildAt(favouriteIndex) as? Button)?.text = favouriteLabel() }
        }, "Plus" to { playbackSettings() })
        if(entry.kind == "live") { playbackActions.add("Enregistrer" to { house.record(current ?: entry,null) }); playbackActions.add("Guide TV" to { openMiniGuide() }) }
        playbackActions.add("Retour" to { leavePlayer() })
        playbackActions.forEachIndexed { index, (t, f) -> row.addView(button(t, f).apply {
            id = View.generateViewId(); textSize = 14f; setPadding(dp(6), 0, dp(6), 0); nextFocusUpId = video.id
            setOnFocusChangeListener { _, focus ->
                background = shape(if(focus) accent else panel, if(focus) Color.WHITE else null); setTextColor(if(focus) bg else Color.WHITE)
                video.controllerShowTimeoutMs = if(focus) 0 else 5000
                if(focus) video.showController()
            }
        }, LinearLayout.LayoutParams(if(DeviceLayout.compact(this)) dp(105) else 0, dp(48), if(DeviceLayout.compact(this)) 0f else 1f).apply { if(index < playbackActions.lastIndex) rightMargin = dp(6) }) }
        playerFavourite = row.getChildAt(favouriteIndex) as? Button
        val actionBar: View = if(DeviceLayout.compact(this)) HorizontalScrollView(this).apply { addView(row); isHorizontalScrollBarEnabled=false } else row
        stage.addView(actionBar, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply { leftMargin = inset.horizontal; rightMargin = inset.horizontal; bottomMargin = inset.vertical })
        video.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.let { controller ->
            controller.layoutParams = (controller.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = inset.horizontal; rightMargin = inset.horizontal; topMargin = inset.vertical
                bottomMargin = inset.vertical + dp(72)
            }
        }
        // Media3 pop-ups can align to the window edge; use our centered TV dialogs instead.
        video.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { playbackSettings() }
        video.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener { subtitleMenu() }
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            val chrome = if(miniGuide?.isOpen != true && (visibility == View.VISIBLE || row.hasFocus())) View.VISIBLE else View.GONE
            heading.visibility = chrome; actionBar.visibility = chrome
        })
        val useHls = store.prefs.getBoolean("hls", false)
        val factory=DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setUserAgent("PinkTV/${BuildConfig.VERSION_NAME}").setAllowCrossProtocolRedirects(true)))
        val renderers = if(localStereo()) StereoRenderersFactory(this) else DefaultRenderersFactory(this)
        val p = ExoPlayer.Builder(this,renderers).setMediaSourceFactory(factory).build(); player = p; video.player = p
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("fr").setMaxAudioChannelCount(if(audioMode() == "stereo" && !localStereo()) 2 else Int.MAX_VALUE).build()
        var endHandled = false
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { if(player === p && !ensureCompatibleAudio(p,tracks)) restoreAudio(tracks) }
            override fun onPlaybackStateChanged(state: Int) {
                if(player !== p) return
                if(state == Player.STATE_READY) { retries = 0; playbackStarted = true; saveProgress() }
                if(state == Player.STATE_ENDED && entry.kind != "live" && !endHandled) {
                    endHandled = true; saveProgress(true)
                    main.post { if(player === p && screen == "player") {
                        if(entry.kind in listOf("vod","recording")) leavePlayer()
                        else {
                            val next = store.history().firstOrNull { it.entry.key == entry.key }?.next
                            val dialog = AlertDialog.Builder(this@MainActivity).setTitle("Épisode terminé")
                                .setMessage(next?.let { "À suivre : ${it.name}" } ?: "Tu peux revenir à la liste pour choisir la suite.")
                                .setNegativeButton("Retour aux épisodes") { _, _ -> leavePlayer() }
                            if(next != null) dialog.setPositiveButton("Épisode suivant") { _, _ -> play(next) }
                            playbackEndDialog = dialog.show()
                        }
                    } }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if(!isPlaying && player === p) saveProgress() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { if(player === p) saveProgress() }
            override fun onPlayerError(error: PlaybackException) {
                if (screen != "player" || player !== p) return
                if(PlaybackErrors.httpStatus(error)==409) { noFlow();return }
                if (retries < 3 && PlaybackErrors.shouldRetry(PlaybackErrors.httpStatus(error))) {
                    retries++; toast("Reconnexion $retries/3…")
                    retryTask = Runnable { if(player === p && screen == "player") { p.prepare(); p.play() } }.also { main.postDelayed(it, 1500L * retries) }
                } else playbackError(if(entry.kind == "live") current ?: entry else entry, useHls, error)
            }
        })
        val url = if(entry.kind == "recording") Recordings.uri(this, entry.id) else Xtream(store.account()!!).stream(entry, useHls)
        p.setMediaItem(MediaItem.fromUri(url)); if(entry.kind != "live") p.seekTo(position); p.prepare(); p.play(); video.requestFocus()
        main.postDelayed(progressTick, 5000)
    }
    private fun playbackError(entry: Entry, hls: Boolean, error: PlaybackException) {
        val status = PlaybackErrors.httpStatus(error)
        val details = buildString {
            append(PlaybackErrors.explanation(status))
            if(entry.kind == "live") append("\nFormat du direct : ${if(hls) "HLS" else "MPEG-TS"}")
            status?.let { append("\nHTTP : $it") }
            append("\nCode : ${error.errorCodeName}")
            if(hls) append("\n\nPasser en MPEG-TS change le format pour toutes les chaînes en direct.")
        }
        val dialog = AlertDialog.Builder(this).setTitle(if(entry.kind == "live") "Cette chaîne ne répond pas" else "Lecture interrompue").setMessage("${entry.name} est indisponible pour le moment. Tu peux réessayer ou revenir choisir autre chose. Tes favoris sont conservés.")
            .setNegativeButton(if(entry.kind == "live") "Autre chaîne" else "Autre choix") { _, _ ->
                if(entry.kind == "live") { if(miniGuide?.isOpen != true) openMiniGuide() } else leavePlayer()
            }
            .setNeutralButton("Détails") { _, _ ->
                val info = AlertDialog.Builder(this).setTitle("Détails de lecture").setMessage(details).setNegativeButton("Fermer", null)
                if(hls) info.setPositiveButton("Passer en MPEG-TS") { _, _ -> store.prefs.edit().putBoolean("hls", false).apply(); play(entry) }
                info.show()
            }
            .setPositiveButton("Réessayer") { _, _ -> val position = player?.currentPosition ?: playbackPosition; play(entry, position) }
        dialog.show()
    }
    private fun fingerprint(f: Format): String = listOf(f.language ?: "", f.label ?: "", f.sampleMimeType ?: "", f.channelCount.toString(), f.roleFlags.toString()).joinToString("|")
    private fun ensureCompatibleAudio(p: ExoPlayer, tracks: Tracks, force: Boolean = false): Boolean = false
    private fun restoreAudio(tracks: Tracks) {
        if(audioRestored) return
        val p = player ?: return; val key = current?.key ?: return
        val options = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        if(options.isEmpty()) return
        val saved = store.prefs.getString("audio:$key", null)
        val choices = options.map { (g, i) -> val f = g.getTrackFormat(i); AudioChoice(fingerprint(f), f.language, f.label, f.channelCount,
            f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 || AudioRules.isDescription(f.label)) }
        val chosen = AudioRules.choose(choices, saved, audioMode(), store.prefs.getBoolean("avoid_description", true))?.let { options[it] }
        audioRestored = true
        if(choices.all { it.described } && store.prefs.getBoolean("avoid_description", true)) toast("Le fournisseur propose seulement des pistes avec audiodescription.")
        chosen?.let { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(it.first.mediaTrackGroup, it.second)).build() }
    }
    private fun playbackSettings() {
        AlertDialog.Builder(this).setTitle("Lecture").setItems(arrayOf("Son", "Sous-titres", "Vitesse", "Détails du flux", "Réessayer")) { _, which ->
            when(which) {
                0 -> audioMenu()
                1 -> subtitleMenu()
                3 -> streamInfo()
                4 -> { retryTask?.let { main.removeCallbacks(it) }; retries = 0; player?.prepare(); player?.play() }
                2 -> {
                    val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
                    AlertDialog.Builder(this).setTitle("Vitesse de lecture").setSingleChoiceItems(
                        speeds.map { if(it == 1f) "Normale" else "${it}×" }.toTypedArray(), speeds.indexOfFirst { it == (player?.playbackParameters?.speed ?: 1f) }
                    ) { dialog, index -> player?.setPlaybackSpeed(speeds[index]); dialog.dismiss() }.setNegativeButton("Fermer", null).show()
                }
            }
        }.setNegativeButton("Fermer", null).show()
    }
    private fun subtitleMenu() {
        val p = player ?: return
        val options = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val titles = listOf("Désactivés") + options.map { (group, index) ->
            val format = group.getTrackFormat(index)
            "${if(group.isTrackSelected(index)) "✓ " else ""}${format.label ?: format.language ?: "Piste ${index + 1}"}"
        }
        AlertDialog.Builder(this).setTitle("Sous-titres").setItems(titles.toTypedArray()) { _, which ->
            val selection = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, which == 0)
            if(which > 0) options[which - 1].let { (group, index) -> selection.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)) }
            p.trackSelectionParameters = selection.build()
        }.setNegativeButton("Fermer", null).show()
    }
    private fun audioMenu() {
        val p = player ?: return
        val options = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).map { g to it } }
        if(options.isEmpty()) { message("Audio", "Aucune piste détectée pour le moment. Attends le démarrage de la chaîne."); return }
        val titles = options.mapIndexed { number, (g,i) -> val f = g.getTrackFormat(i)
            "${if(g.isTrackSelected(i)) "✓ " else ""}${AudioRules.title(f.language, f.label, f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 || AudioRules.isDescription(f.label), number)}${if(!g.isTrackSupported(i)) " · indisponible" else ""}"
        }
        val dialog = AlertDialog.Builder(this).setTitle("Choisir le son")
        if(options.size == 1) dialog.setCustomTitle(label("Choisir le son\n\nUne seule piste reçue. Aucune autre piste à choisir; une narration mélangée au son ne peut pas être retirée.", 16f).apply { setPadding(dp(24), dp(20), dp(24), dp(12)) })
        dialog.setItems(titles.toTypedArray()) { _, which ->
            val (g,i) = options[which]
            if(!g.isTrackSupported(i)) { toast("Ce format n’est pas pris en charge par cet appareil."); return@setItems }
            store.prefs.edit().putString("audio:${current!!.key}", fingerprint(g.getTrackFormat(i))).apply()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
        }.setNeutralButton(if(compatibleLive) "Son d’origine" else "Automatique") { _, _ ->
            if(compatibleLive && current?.kind == "live") {
                val entry=current!!
                compatibleLive=false;audioRestored=false;compatibilityCheck=p to entry.key
                store.prefs.edit().remove("compatible_audio:${entry.key}").apply()
                p.stop();p.clearMediaItems();p.setMediaItem(MediaItem.fromUri(Xtream(store.account()!!).stream(entry,false)))
                p.prepare();p.play()
                return@setNeutralButton
            }
            store.prefs.edit().remove("audio:${current!!.key}").apply()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            audioRestored = false; restoreAudio(p.currentTracks)
        }.setNegativeButton("Détails") { _, _ -> streamInfo() }
        dialog.show()
    }
    private fun streamInfo() {
        val p = player ?: return
        val info = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }.joinToString("\n") { "${it.sampleMimeType} · ${it.channelCount} canaux · ${it.language ?: "langue inconnue"}" }
        message("Infos du flux", "${current?.name}\n$info${if(localStereo()) "\nMode Stéréo · adaptation sur cette TV" else ""}\nVidéo : ${p.videoFormat?.width ?: "?"} × ${p.videoFormat?.height ?: "?"}\nCes données décrivent le flux reçu, pas la sortie HDMI réelle.")
    }
    private fun checkUpdate() {
        if(busy) return
        val updater = Updater(this)
        async("Recherche d’une mise à jour…", { updater.check() }) { update ->
            if(update == null) message("Pink TV ${BuildConfig.VERSION_NAME}", "Tu as la dernière version publiée.")
            else AlertDialog.Builder(this).setTitle("Pink TV ${update.version}").setMessage(update.notes).setNegativeButton("Plus tard", null).setPositiveButton("Télécharger") { _, _ ->
                async("Téléchargement et vérification…", { updater.download(update) }) { file -> pendingInstall = file; installUpdate() }
            }.show()
        }
    }
    private fun installUpdate() {
        val file = pendingInstall ?: return
        if(!file.isFile) {
            pendingInstall = null
            settings()
            message("Téléchargement à reprendre", "Le fichier de mise à jour n’est plus disponible. Choisis Vérifier les mises à jour pour le télécharger de nouveau.")
            return
        }
        val updater = Updater(this)
        if(!updater.canInstall()) AlertDialog.Builder(this).setTitle("Autoriser la mise à jour")
            .setMessage("Autorise Pink TV à installer des applications. Reviens ensuite dans Réglages et choisis Installer la mise à jour téléchargée.")
            .setNegativeButton("Plus tard", null).setPositiveButton("Ouvrir les réglages") { _, _ -> runCatching { updater.requestPermission() }.onFailure { message("Autorisation", "Va dans Ma Fire TV → Options développeur → Installer des applications inconnues, puis autorise Pink TV.") } }.show()
        else runCatching { updater.install(file) }.onFailure { message("Installation", "L’installateur n’a pas pu s’ouvrir sur cet appareil.") }
    }
    private fun <T> async(status: String, task: () -> T, success: (T) -> Unit) {
        val token = requestId; busy = true
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)))
        val statusView = label(status, 14f, muted); root.addView(statusView)
        work.execute {
            val result = runCatching { task() }
            main.post {
                if(isFinishing || isDestroyed || token != requestId) return@post
                busy = false; root.removeView(progress); root.removeView(statusView)
                result.onSuccess(success).onFailure { error ->
                    val text = when {
                        error is java.net.SocketTimeoutException -> "Le serveur met trop de temps à répondre. Réessaie."
                        error is java.net.UnknownHostException -> "Adresse introuvable. Vérifie le serveur et la connexion Internet."
                        error is org.json.JSONException -> "Réponse du serveur non reconnue. Vérifie l’adresse Xtream."
                        error is java.io.IOException -> if(screen == "settings") "La mise à jour n’a pas pu être récupérée. Vérifie Internet et réessaie dans un moment." else "Le fournisseur ne répond pas. Vérifie Internet ou réessaie dans un moment. Tes favoris sont conservés."
                        else -> "L’opération n’a pas pu être terminée. Réessaie dans un moment."
                    }
                    AlertDialog.Builder(this).setTitle("Connexion interrompue").setMessage(text)
                        .setPositiveButton("Réessayer") { _, _ -> async(status, task, success) }
                        .setNegativeButton(if(screen in listOf("browse", "episodes")) "Accueil" else "Fermer") { _, _ ->
                            if(screen in listOf("browse", "episodes")) home()
                        }.show()
                }
            }
        }
    }
    private fun message(title: String, text: String) { if(!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show() }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
}
