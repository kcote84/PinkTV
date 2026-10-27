package ca.pinktv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns the guide's view, transient navigation state and a bounded in-memory schedule cache. */
class TvGuideScreen(private val activity: Activity, val account: Account?, private val store: Store,
    private val watch: (Entry) -> Unit, private val connect: () -> Unit, private val record: (Entry, Programme?) -> Unit) {
    val view = LinearLayout(activity)
    private val grid = TvGuideGrid(activity)
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val api = account?.let { Xtream(it) }
    private val pending = mutableMapOf<String, Future<*>>()
    private val tickets = mutableMapOf<String, Int>()
    private var nextTicket = 0
    private var catalogue: Future<*>? = null
    private val filterWorker = Executors.newSingleThreadExecutor()
    private var filterTask: Future<*>? = null
    private var filterRequest = 0
    private var filtering = false
    private var headerFocus: View? = null
    private var keepHeaderFocus = false
    private var active = false
    private var generation = 0
    private var all = emptyList<Entry>()
    private var categories = emptyList<Category>()
    private var filter = if(store.favourites().isEmpty()) "Toutes" else "★ Favoris"
    private var category = ""
    private var query = ""
    private var focusId: String? = null
    private var loadedAt = 0L
    private data class Cached(val programmes: List<Programme>, val loaded: Long, val failed: Boolean)
    private val cache = object : LinkedHashMap<String, Cached>(96, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 96
    }
    private val demoFavourites = mutableSetOf<String>()
    private val bg = TvTheme.background
    private val panel = TvTheme.panel
    private val accent = TvTheme.accent
    private val muted = TvTheme.muted
    private val time = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)
    private val date = SimpleDateFormat("EEE d MMM · HH:mm", Locale.CANADA_FRENCH)
    private val heading: TextView
    private val title: TextView
    private val description: TextView
    private val clock: TextView
    private val categoryButton: Button
    private val searchButton: Button
    private val loading = LinearLayout(activity)
    private val filters = mutableMapOf<String, Button>()
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            updateClock()
            if (grid.windowStart + GuideWindow.SPAN <= System.currentTimeMillis()) grid.now()
            grid.refresh(); loadVisible(); main.postDelayed(this, 60000)
        }
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun text(size: Float, color: Int = Color.WHITE) = TextView(activity).apply {
        textSize = size; setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
    }
    private fun shape(color: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(8).toFloat(); if(border) setStroke(dp(2), accent)
    }
    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        id = View.generateViewId(); text = label; textSize = 14f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0); setTextColor(Color.WHITE); background = shape(panel)
        defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, focused ->
            if(focused) headerFocus = this
            background = shape(if(focused) accent else panel, focused || isSelected)
            if(focused) (background as GradientDrawable).setStroke(dp(2), Color.WHITE)
            setTextColor(if(focused) bg else Color.WHITE)
        }
        setOnClickListener { keepHeaderFocus = false; action() }
    }
    private fun row(height: Int) = LinearLayout(activity).also { view.addView(it, LinearLayout.LayoutParams(-1, dp(height))) }
    private fun add(row: LinearLayout, button: Button, weight: Float = 1f) {
        row.addView(button, LinearLayout.LayoutParams(if(DeviceLayout.compact(activity)) dp(105) else 0, dp(48), if(DeviceLayout.compact(activity)) 0f else weight).apply { setMargins(0, dp(3), dp(6), dp(3)) })
    }
    init {
        view.orientation = LinearLayout.VERTICAL; view.setBackgroundColor(bg); TvSafeArea.apply(view, activity, store.prefs.getBoolean("wide_safe_area", false))
        val top = row(42)
        top.addView(text(27f, accent).apply { setText(R.string.guide_title); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -1, 1f))
        clock = text(if(DeviceLayout.compact(activity)) 11f else 14f, muted).apply { text = date.format(Date()) }; top.addView(clock)
        val tools = LinearLayout(activity)
        view.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false; addView(tools,android.view.ViewGroup.LayoutParams(if(DeviceLayout.compact(activity)) -2 else activity.resources.displayMetrics.widthPixels - 2 * TvSafeArea.insets(activity).horizontal,dp(50))) },LinearLayout.LayoutParams(-1,dp(50)))
        listOf("★ Favoris", "Québec", "Toutes", "Adultes").forEach { label ->
            val b = button(label) { filter = label; category = ""; applyFilters(); grid.requestFocus() }
            filters[label] = b; add(tools, b)
        }
        categoryButton = button("Catégories") { chooseCategory() }; add(tools, categoryButton, 1.15f)
        searchButton = button("Rechercher") { search() }; add(tools, searchButton, 1.15f)
        add(tools, button("? Aide") { help() }, .75f)
        val detail = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(5), 0, dp(4)) }
        heading = text(12f, accent); title = text(22f).apply { setTypeface(null, Typeface.BOLD) }; description = text(14f, muted)
        detail.addView(heading, LinearLayout.LayoutParams(-1, dp(20)))
        detail.addView(title, LinearLayout.LayoutParams(-1, dp(30)))
        detail.addView(description, LinearLayout.LayoutParams(-1, dp(24)))
        view.addView(detail, LinearLayout.LayoutParams(-1, dp(84)))
        grid.id = View.generateViewId()
        grid.programmes = { cache[it.id]?.programmes ?: emptyList() }
        grid.favourite = { isFavourite(it) }
        grid.status = { entry -> when {
            cache[entry.id]?.failed == true -> "Horaire indisponible · OK pour regarder"
            cache.containsKey(entry.id) -> "Horaire non fourni · OK pour regarder"
            else -> "Chargement de l’horaire…"
        } }
        grid.onSelection = { selection(); loadVisible() }
        grid.onWindow = { updateClock() }
        grid.onOpen = { entry, programme -> open(entry, programme) }
        grid.onFavourite = { options() }
        grid.setOnFocusChangeListener { _, focused -> if(focused) keepHeaderFocus = false }
        val content = FrameLayout(activity)
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        loading.apply {
            id = View.generateViewId(); orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isFocusable = true; visibility = View.GONE
            nextFocusUpId = filters.getValue("★ Favoris").id
            addView(ProgressBar(activity).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(16f, muted).apply {
                setText(R.string.guide_loading); gravity = Gravity.CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(-2, dp(46)))
        }
        content.addView(loading, FrameLayout.LayoutParams(-1, -1))
        view.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        for (i in 0 until tools.childCount) tools.getChildAt(i).nextFocusDownId = grid.id
        for (i in 0 until top.childCount) top.getChildAt(i).nextFocusDownId = grid.id
        grid.nextFocusUpId = filters.getValue("★ Favoris").id
        grid.nextFocusDownId = grid.id
        updateClock()
    }
    fun resume(channelId: String? = null) {
        active = true
        keepHeaderFocus = false
        TvSafeArea.apply(view, activity, store.prefs.getBoolean("wide_safe_area", false))
        if (channelId != null) { focusId = channelId; filter = "Toutes"; category = ""; query = "" }
        if (all.isEmpty() || System.currentTimeMillis() - loadedAt > 300000) loadCatalogue()
        else { applyFilters(); grid.refresh(); loadVisible() }
        main.removeCallbacks(ticker); main.postDelayed(ticker, 60000)
        grid.post { if(!keepHeaderFocus) grid.requestFocus() }
    }
    /** First Back focuses the toolbar without changing the selected channel or time window. */
    fun handleBack(): Boolean {
        if(!active || headerFocus?.hasFocus() == true) return false
        keepHeaderFocus = true
        val target = headerFocus?.takeIf { it.isEnabled && it.visibility == View.VISIBLE }
            ?: if(category.isNotEmpty()) categoryButton else filters.getValue(filter)
        return target.requestFocus()
    }
    fun pause() {
        active = false; generation++; main.removeCallbacks(ticker)
        filterRequest++; filterTask?.cancel(true); filterTask = null
        catalogue?.cancel(true); catalogue = null
        pending.values.forEach { it.cancel(true) }; pending.clear(); tickets.clear(); pool.purge()
    }
    fun favouritesChanged() { if(active) { applyFilters(); grid.refresh(); selection() } }
    fun close() { pause(); pool.shutdownNow(); filterWorker.shutdownNow(); main.removeCallbacksAndMessages(null) }
    private fun isFavourite(entry: Entry) = if (account == null) entry.key in demoFavourites else entry.key in store.favourites()
    private fun loadCatalogue() {
        if (!active || catalogue != null) return
        filterRequest++; filterTask?.cancel(true); filterTask = null
        showLoading(true)
        heading.setText(if(account == null) R.string.guide_demo_label else R.string.guide_tagline)
        title.setText(R.string.guide_loading); description.setText(R.string.guide_loading_hint)
        val token = generation
        catalogue = pool.submit {
            val result = runCatching { if(api == null) demoCatalogue() else api.categories("live") to api.entries("live").distinctBy { it.id } }
            main.post {
                if (!active || generation != token) return@post
                catalogue = null
                result.onSuccess { (cats, entries) ->
                    categories = cats; all = entries; loadedAt = System.currentTimeMillis()
                    applyFilters()
                }.onFailure {
                    android.util.Log.w("Pink TVGuide", "Catalogue: ${it.javaClass.simpleName}")
                    grid.setChannels(emptyList()); showLoading(false)
                    title.setText(R.string.guide_connection_failed)
                    description.setText(R.string.guide_connection_retry)
                    AlertDialog.Builder(activity).setTitle("Connexion au guide").setMessage("Le fournisseur ne répond pas. Tes favoris et tes réglages sont conservés.")
                        .setPositiveButton("Réessayer") { _, _ -> loadCatalogue() }.setNegativeButton("Fermer", null).show()
                }
            }
        }
    }
    private fun categoryName(e: Entry) = categories.firstOrNull { it.id == e.category }?.name.orEmpty()
    private fun showLoading(value: Boolean) {
        val wasFocused = grid.hasFocus()
        filtering = value
        grid.visibility = if(value) View.INVISIBLE else View.VISIBLE
        loading.visibility = if(value) View.VISIBLE else View.GONE
        if(value) {
            heading.text = filter
            title.setText(R.string.guide_loading); description.setText(R.string.guide_loading_hint)
            if(wasFocused) loading.requestFocus()
        }
    }
    private fun applyFilters() {
        if(!active) return
        val request = ++filterRequest
        filterTask?.cancel(true); filterTask = null
        pending.values.forEach { it.cancel(true) }; pending.clear(); tickets.clear(); pool.purge()
        showLoading(true)
        filters.forEach { (label, b) ->
            b.isSelected = label == filter && category.isEmpty()
            b.text = if(b.isSelected) "✓ $label" else label
            b.background = shape(if(b.hasFocus()) accent else panel, b.isSelected || b.hasFocus())
            if(b.hasFocus()) (b.background as GradientDrawable).setStroke(dp(2), Color.WHITE)
            b.setTextColor(if(b.hasFocus()) bg else Color.WHITE)
        }
        categoryButton.text = if(category.isEmpty()) "Catégories" else "Catégorie ✓"
        searchButton.text = if(query.isBlank()) "Rechercher" else "Recherche ✓"
        if(catalogue != null) return
        val token = generation
        val entries = all; val names = categories.associate { it.id to it.name }
        val selectedFilter = filter; val selectedCategory = category; val terms = query
        val favourites = if(account == null) demoFavourites.toSet() else store.favourites()
        filterTask = filterWorker.submit {
            val result = runCatching { GuideFilters.select(entries, names, favourites, selectedFilter, selectedCategory, terms) }
            if(Thread.currentThread().isInterrupted) return@submit
            main.post {
                if(!active || generation != token || filterRequest != request) return@post
                filterTask = null
                result.onSuccess { shown ->
                    val restoreFocus = loading.hasFocus() || categoryButton.hasFocus() || searchButton.hasFocus() || filters.values.any { it.hasFocus() }
                    grid.setChannels(shown, focusId); focusId = null
                    showLoading(false); selection(); updateClock(); loadVisible()
                    if(restoreFocus && !keepHeaderFocus) grid.requestFocus()
                }.onFailure {
                    grid.setChannels(emptyList()); showLoading(false)
                    title.setText(R.string.guide_connection_failed); description.setText(R.string.guide_connection_retry)
                }
            }
        }
    }
    private fun loadVisible() {
        if (!active || filtering || all.isEmpty()) return
        val visible = grid.visible().map { it.id }.toSet()
        pending.keys.filter { it !in visible }.toList().forEach { pending.remove(it)?.cancel(true); tickets.remove(it) }; pool.purge()
        grid.visible().forEach { entry ->
            val cached = cache[entry.id]
            if (pending.containsKey(entry.id) || cached != null && System.currentTimeMillis() - cached.loaded < 180000) return@forEach
            val token = generation
            val ticket = ++nextTicket
            tickets[entry.id] = ticket
            pending[entry.id] = pool.submit {
                val now = System.currentTimeMillis()
                val result = runCatching { if(api == null) demoProgrammes(entry, now) else api.guide(entry) }
                if (Thread.currentThread().isInterrupted) return@submit
                main.post {
                    if (!active || token != generation || tickets[entry.id] != ticket) return@post
                    pending.remove(entry.id); tickets.remove(entry.id)
                    cache[entry.id] = Cached(result.getOrDefault(emptyList()).filter { it.end > now - GuideWindow.SPAN && it.start < now + 24 * 60 * 60 * 1000 }.take(256), now, result.isFailure)
                    grid.refresh()
                }
            }
        }
    }
    private fun selection() {
        if(filtering) return
        val entry = grid.selectedEntry(); val p = grid.selectedProgramme(); val now = System.currentTimeMillis()
        heading.text = if(account == null) "DÉMO · EXEMPLE D’HORAIRE" else entry?.let { "${if(isFavourite(it)) "★ " else ""}${it.name}  ·  ${categoryName(it)}" } ?: "GUIDE TV"
        title.text = p?.title ?: if (entry == null) "Aucune chaîne dans cette sélection" else entry.name
        description.text = p?.let {
            "${time.format(Date(it.start))} – ${time.format(Date(it.end))} · ${if(it.isNow(now)) "En cours" else if(it.start > now) "À venir" else "Terminé"} · ${it.description.ifBlank { "OK pour les détails" }}"
        } ?: if(entry == null && filter == "★ Favoris") "Choisis Toutes, puis Menu → Ajouter aux favoris sur une chaîne." else if(entry == null) "Choisis Toutes, change de catégorie ou efface la recherche." else grid.status(entry)
    }
    private fun updateClock() {
        clock.text = activity.getString(R.string.guide_schedule_window, date.format(Date(grid.windowStart)))
    }
    private fun toggleFavourite() {
        val entry = grid.selectedEntry() ?: return
        val added = if(account == null) { if(demoFavourites.remove(entry.key)) false else { demoFavourites.add(entry.key); true } } else store.toggle(entry)
        fun refresh() { if(filter == "★ Favoris") applyFilters() else { grid.refresh(); selection() } }
        refresh()
        FavouriteFeedback.show(activity, entry, added) {
            if(account == null) demoFavourites.add(entry.key) else store.setFavourite(entry, true)
            focusId = entry.id; refresh()
        }
    }
    private fun chooseCategory() {
        val choices = listOf(Category("", "Toutes les catégories")) + categories
        AlertDialog.Builder(activity).setTitle("Choisir une catégorie").setItems(choices.map { it.name }.toTypedArray()) { _, which ->
            category = choices[which].id; filter = "Toutes"; applyFilters(); grid.requestFocus()
        }.setNegativeButton("Retour", null).show()
    }
    private fun search() {
        val input = EditText(activity).apply { setSingleLine(); hint = "Nom ou numéro de chaîne"; setText(query); selectAll() }
        AlertDialog.Builder(activity).setTitle("Trouver une chaîne").setView(input)
            .setPositiveButton("Rechercher") { _, _ -> query = input.text.toString().trim(); applyFilters(); grid.requestFocus() }
            .setNeutralButton("Tout effacer") { _, _ -> query = ""; applyFilters(); grid.requestFocus() }
            .setNegativeButton("Retour", null).show()
    }
    fun findChannel() { filter = "Toutes"; category = ""; query = ""; applyFilters(); search() }
    private fun options() {
        val entry=grid.selectedEntry() ?: return
        AlertDialog.Builder(activity).setTitle(entry.name).setItems(arrayOf("Regarder cette chaîne","Enregistrer l’émission","Détails de l’émission",if(isFavourite(entry)) "★ Retirer des favoris" else "☆ Ajouter aux favoris","Revenir à maintenant","Choisir les chaînes affichées","Choisir une catégorie","Rechercher une chaîne","Comment utiliser le guide")) { _,which ->
            when(which) {
                0 -> play(entry)
                1 -> record(entry,grid.selectedProgramme())
                2 -> grid.selectedProgramme()?.let { details(entry,it) }
                3 -> toggleFavourite()
                4 -> grid.now()
                5 -> AlertDialog.Builder(activity).setTitle("Quelles chaînes ?").setItems(arrayOf("★ Favoris","Québec","Toutes","Adultes")) { _,i ->
                    filter=listOf("★ Favoris","Québec","Toutes","Adultes")[i];category="";applyFilters();grid.requestFocus()
                }.setNegativeButton("Retour",null).show()
                6 -> chooseCategory()
                7 -> search()
                8 -> help()
            }
        }.setNegativeButton("Retour au guide",null).show()
    }
    private fun open(entry: Entry, p: Programme?) {
        if(DeviceLayout.compact(activity) && p!=null) details(entry,p)
        else if(p == null || p.isNow(System.currentTimeMillis())) play(entry)
        else details(entry, p)
    }
    private fun details(entry: Entry, p: Programme) {
        AlertDialog.Builder(activity).setTitle(p.title)
            .setMessage("${entry.name}\n${time.format(Date(p.start))} – ${time.format(Date(p.end))}\n\n${p.description.ifBlank { "Aucune description fournie." }}\n\nLa chaîne démarre toujours en direct. Cette émission n’est pas une reprise.")
            .setPositiveButton("Regarder la chaîne") { _, _ -> play(entry) }.setNeutralButton("Enregistrer") { _, _ -> record(entry,p) }.setNegativeButton("Retour au guide", null).show()
    }
    private fun play(entry: Entry) {
        if(account == null) AlertDialog.Builder(activity).setTitle("Tu explores le mode démo")
            .setMessage("Ces émissions sont des exemples. Connecte ton compte pour voir tes vraies chaînes et leurs horaires.")
            .setPositiveButton("Connecter mon compte") { _, _ -> connect() }.setNegativeButton("Continuer la démo", null).show()
        else watch(entry)
    }
    private fun help() {
        AlertDialog.Builder(activity).setTitle("Le guide en 3 gestes")
            .setMessage("1. ↑ ↓ : choisis une chaîne.\n2. ← → : regarde ce qui joue plus tard.\n3. OK : regarde l’émission en cours ou ouvre les détails d’une émission à venir.\n\nMenu ouvre les options : favoris, recherche et retour à maintenant.\n\n★ Favoris est le premier onglet. Québec regroupe les chaînes québécoises; Toutes conserve le catalogue complet.\n\nRetour dans les chaînes rejoint les onglets du haut sans perdre ta place. ↓ retrouve la chaîne sélectionnée. Retour depuis les onglets ramène à l’accueil. Après la lecture, Retour retrouve ta place dans le guide.\n\nL’horaire vient de ton fournisseur. Une chaîne reste accessible même sans horaire. Jusqu’à 12 h à venir, selon les données reçues.")
            .setPositiveButton("C’est compris", null).show()
    }
    private fun demoCatalogue(): Pair<List<Category>, List<Entry>> {
        val cats = listOf(Category("qc", "Québec · Français"), Category("sport", "Sports · Français"), Category("en", "English"))
        val names = listOf("Télé-Québec", "ICI Télé", "TVA", "Noovo", "RDS", "TV5", "ICI RDI", "English TV")
        return cats to names.mapIndexed { i, name -> Entry("demo$i", name, if(i == 7) "en" else if(i == 4) "sport" else "qc", "live", number = i + 1) }
    }
    private fun demoProgrammes(e: Entry, now: Long): List<Programme> {
        val start = GuideWindow.floor(now) - GuideWindow.HALF_HOUR
        val titles = listOf("Le rendez-vous du soir", "Un été au Québec", "Les nouvelles", "La grande escapade", "Cinéma en famille", "À table !")
        val offset = e.number % titles.size
        return (0..27).map { i -> Programme(titles[(i + offset) % titles.size], "Exemple d’émission pour essayer le guide. Aucun horaire réel ni flux inclus.", start + i * GuideWindow.HALF_HOUR, start + (i + 1) * GuideWindow.HALF_HOUR) }
    }
}
