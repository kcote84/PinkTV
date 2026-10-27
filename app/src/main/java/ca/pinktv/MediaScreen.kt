package ca.pinktv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** One TV sheet for a film or series. The Activity owns playback and the return path. */
class MediaScreen(private val activity: Activity, private val details: MediaDetails, private val store: Store,
    loader: PosterLoader, preferredSeason: String?, preferredEpisode: String?,
    private val play: (Entry, Boolean) -> Unit, private val favourite: (Entry, () -> Unit) -> Unit,
    private val reload: () -> Unit, private val back: () -> Unit) {
    val view = LinearLayout(activity)
    private val entry = details.entry
    private val isSeries = entry.kind == "series"
    private val episodeList = ListView(activity)
    private var seasonButton: Button? = null
    private var primary: Button? = null
    private var restartButton: Button? = null
    private var favouriteButton: Button? = null
    private var navigation: HeaderNavigation? = null
    fun handleBack() = navigation?.handleBack() == true
    fun handleDown() = navigation?.handleDown() == true
    fun favouritesChanged() { favouriteButton?.text = favouriteTitle() }
    var seasonId: String = preferredEpisode?.let { id -> details.seasons.firstOrNull { season -> season.episodes.any { it.entry.id == id } }?.id }
        ?: preferredSeason?.takeIf { id -> details.seasons.any { it.id == id } }
        ?: WatchHistory.recommend(details, store.history())?.entry?.season?.let { number -> details.seasons.firstOrNull { it.number == number }?.id }
        ?: details.seasons.firstOrNull { it.number > 0 }?.id ?: details.seasons.firstOrNull()?.id.orEmpty()
        private set
    var selectedEpisodeId: String? = preferredEpisode
        private set
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f, color: Int = Color.WHITE) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); if(focused) setStroke(dp(2), Color.WHITE)
    }
    private fun button(title: String, action: () -> Unit) = Button(activity).apply {
        id = View.generateViewId(); text = title; textSize = 15f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setTextColor(Color.WHITE); setPadding(dp(12), dp(4), dp(12), dp(4)); background = shape(TvTheme.panel)
        defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, focus -> background = shape(if(focus) TvTheme.accent else TvTheme.panel, focus); setTextColor(if(focus) TvTheme.background else Color.WHITE) }
        setOnClickListener { action() }
    }
    private fun row(parent: LinearLayout, height: Int = 46) = LinearLayout(activity).also {
        parent.addView(it, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(7) })
    }
    private fun add(row: LinearLayout, button: Button, weight: Float = 1f) {
        row.addView(button, LinearLayout.LayoutParams(0, -1, weight).apply { if(row.childCount > 0) leftMargin = dp(8) })
    }
    init {
        view.orientation = if(DeviceLayout.compact(activity)) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; view.setPadding(0, dp(8), 0, 0)
        val poster = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE; setBackgroundColor(TvTheme.panel); setColorFilter(TvTheme.muted)
            setImageResource(if(isSeries) R.drawable.home_series else R.drawable.home_films)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        view.addView(poster, LinearLayout.LayoutParams(if(DeviceLayout.compact(activity)) -1 else dp(172), dp(if(DeviceLayout.compact(activity)) 110 else 258)).apply { rightMargin = dp(24) })
        loader.bind(poster, entry.poster)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        view.addView(body, LinearLayout.LayoutParams(if(DeviceLayout.compact(activity)) -1 else 0, if(DeviceLayout.compact(activity)) 0 else -1, 1f))
        body.addView(text(entry.name, 27f).apply { setTypeface(null, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val meta = listOf(if(isSeries) "Série" else "Film", details.year, if(details.durationMs > 0) WatchHistory.stamp(details.durationMs) else "", details.genre).filter { it.isNotBlank() }.joinToString("  ·  ")
        body.addView(text(meta, 14f, TvTheme.accent).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val synopsis = if(details.unavailable) "Les renseignements du fournisseur ne sont pas disponibles pour le moment." else details.plot.ifBlank { "Résumé non fourni par le fournisseur." }
        if(isSeries) body.addView(text(synopsis, 15f, TvTheme.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        else {
            val scroll = ScrollView(activity).apply { isFocusable = true; isFillViewport = false }
            scroll.addView(text(synopsis + listOf(details.cast.takeIf { it.isNotBlank() }?.let { "\n\nAvec $it" },
                details.director.takeIf { it.isNotBlank() }?.let { "\nRéalisation : $it" }).filterNotNull().joinToString(""), 17f, TvTheme.muted))
            body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12); bottomMargin = dp(8) })
        }
        val actions = row(body)
        primary = button(primaryTitle()) {
            if(isSeries) WatchHistory.recommend(details, store.history())?.let { open(it.entry, false) } ?: if(details.unavailable) reload() else Unit
            else open(entry, false)
        }
        add(actions, primary!!, 1.5f)
        val star = button(favouriteTitle()) {}
        favouriteButton = star
        star.setOnClickListener { favourite(entry) { star.text = favouriteTitle() } }
        add(actions, star)
        if(!isSeries && store.history().any { it.entry.key == entry.key && it.resumable }) {
            restartButton = button("Recommencer") { play(entry, true) }; add(actions, restartButton!!)
        }
        val secondary = row(body, 40)
        if(isSeries) {
            seasonButton = button(seasonTitle()) { chooseSeason() }
            seasonButton!!.isEnabled = details.seasons.isNotEmpty()
            add(secondary, seasonButton!!, 1.2f)
        }
        add(secondary, button("Plus") { more() })
        add(secondary, button("Retour") { back() })
        if(isSeries) {
            episodeList.id = View.generateViewId(); episodeList.dividerHeight = dp(6)
            episodeList.divider = android.graphics.drawable.ColorDrawable(TvTheme.background)
            episodeList.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            episodeList.defaultFocusHighlightEnabled = false
            body.addView(episodeList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            episodeList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) { selectedEpisodeId = episodes().getOrNull(position)?.entry?.id }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            episodeList.setOnItemClickListener { _, _, index, _ -> episodes().getOrNull(index)?.let { open(it.entry) } }
            episodeList.setOnItemLongClickListener { _, _, index, _ -> episodes().getOrNull(index)?.let { episodeOptions(it) }; true }
            episodeList.setOnKeyListener { _, code, event ->
                if(code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { episodes().getOrNull(episodeList.selectedItemPosition)?.let { episodeOptions(it) }; true } else code == KeyEvent.KEYCODE_MENU
            }
            renderEpisodes()
            val headers = listOf(actions, secondary).flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
            navigation = HeaderNavigation(headers, episodeList, seasonButton,
                downHeaders = (0 until secondary.childCount).map { secondary.getChildAt(it) },
                itemKey = { episodes().getOrNull(it)?.entry?.key })
        }
        primary!!.isEnabled = !isSeries || details.episodes.isNotEmpty() || details.unavailable
        primary!!.requestFocus()
    }
    private fun episodes() = details.seasons.firstOrNull { it.id == seasonId }?.episodes.orEmpty()
    private fun favouriteTitle() = if(entry.key in store.favourites()) "★ Favori" else "☆ Favori"
    private fun seasonTitle() = details.seasons.firstOrNull { it.id == seasonId }?.title?.let { "$it ▾" } ?: "Aucune saison"
    private fun primaryTitle(): String {
        val records = store.history()
        val target = if(isSeries) WatchHistory.recommend(details, records)?.entry else entry
        if(target == null) return if(details.unavailable) "Réessayer" else "Aucun épisode"
        val p = records.firstOrNull { it.entry.key == target.key }
        val prefix = when { p?.resumable == true -> "Continuer · ${WatchHistory.stamp(p.positionMs)}"; p?.completed == true -> "Revoir"; isSeries && records.any { it.entry.seriesId == entry.id } -> "Continuer"; else -> "Regarder" }
        return if(isSeries) "$prefix · S${target.season} E${target.episode}" else prefix
    }
    private fun open(target: Entry, ask: Boolean = true) {
        val progress = store.history().firstOrNull { it.entry.key == target.key }
        if(progress?.resumable == true && ask) AlertDialog.Builder(activity).setTitle(target.name)
            .setItems(arrayOf("Continuer à ${WatchHistory.stamp(progress.positionMs)}", "Recommencer")) { _, which -> play(target, which == 1) }
            .setNegativeButton("Retour", null).show()
        else play(target, progress?.resumable != true)
    }
    private fun chooseSeason() {
        if(details.seasons.isEmpty()) return
        AlertDialog.Builder(activity).setTitle("Choisir une saison").setSingleChoiceItems(details.seasons.map { "${it.title} · ${it.episodes.size} épisode${if(it.episodes.size > 1) "s" else ""}" }.toTypedArray(), details.seasons.indexOfFirst { it.id == seasonId }) { dialog, index ->
            seasonId = details.seasons[index].id; selectedEpisodeId = null; seasonButton?.text = seasonTitle(); renderEpisodes()
            dialog.dismiss(); if(episodes().isNotEmpty()) episodeList.requestFocus()
        }.setNegativeButton("Retour", null).show()
    }
    private fun renderEpisodes() {
        val rows = episodes(); val progress = store.history().associateBy { it.entry.key }
        val titles = if(rows.isEmpty()) listOf("Aucun épisode disponible dans cette saison.") else rows.map { e ->
            val state = progress[e.entry.key]?.let { WatchHistory.badge(it) }.orEmpty()
            "${e.entry.episode}. ${e.title}\n${listOf(state, if(e.durationMs > 0) WatchHistory.stamp(e.durationMs) else "").filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "OK pour regarder" }}"
        }
        episodeList.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, titles) {
            override fun isEnabled(position: Int) = rows.isNotEmpty()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(12), dp(8), dp(12), dp(8)); minHeight = dp(62)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), shape(TvTheme.selected, true))
                    addState(intArrayOf(android.R.attr.state_pressed), shape(TvTheme.selected, true))
                    addState(intArrayOf(), shape(TvTheme.panel))
                }
            }
        }
        episodeList.setSelection(rows.indexOfFirst { it.entry.id == selectedEpisodeId }.coerceAtLeast(0))
    }
    private fun episodeOptions(episode: Episode) {
        val p = store.history().firstOrNull { it.entry.key == episode.entry.key }
        AlertDialog.Builder(activity).setTitle(episode.title).setItems(arrayOf("Regarder", if(p?.completed == true) "Marquer non vu" else "Marquer comme vu", "Résumé")) { _, which ->
            when(which) {
                0 -> open(episode.entry)
                1 -> { store.remember(WatchProgress(episode.entry, 0, episode.durationMs, p?.completed != true, p?.updatedAt ?: System.currentTimeMillis(), details.episodes.dropWhile { it.entry.key != episode.entry.key }.drop(1).firstOrNull()?.entry)); renderEpisodes(); primary?.text = primaryTitle() }
                2 -> AlertDialog.Builder(activity).setTitle(episode.title).setMessage(episode.plot.ifBlank { "Résumé non fourni." }).setPositiveButton("Fermer", null).show()
            }
        }.setNegativeButton("Retour", null).show()
    }
    private fun more() {
        val p = store.history().firstOrNull { it.entry.key == entry.key }
        val hidden = if(isSeries) store.history().firstOrNull { it.entry.seriesId == entry.id }?.hidden == true else p?.hidden == true
        val continuation = if(hidden) "Remettre dans Continuer" else "Retirer de Continuer"
        val options = if(isSeries) arrayOf("Résumé complet", continuation, "Actualiser la fiche") else arrayOf("Résumé complet", if(p?.completed == true) "Marquer non vu" else "Marquer comme vu", continuation, "Actualiser la fiche")
        AlertDialog.Builder(activity).setTitle(entry.name).setItems(options) { _, which ->
            if(which == 0) AlertDialog.Builder(activity).setTitle(entry.name).setMessage(listOf(details.plot.ifBlank { "Résumé non fourni." }, details.cast.takeIf { it.isNotBlank() }?.let { "Avec $it" }, details.director.takeIf { it.isNotBlank() }?.let { "Réalisation : $it" }, details.rating.takeIf { it.isNotBlank() }?.let { "Note du fournisseur : $it" }).filterNotNull().joinToString("\n\n")).setPositiveButton("Fermer", null).show()
            else if(which == options.lastIndex) reload()
            else {
                if(isSeries || which == 2) {
                    store.setContinueHidden(entry, !hidden)
                    Toast.makeText(activity, if(hidden) "Remis dans Continuer" else "Retiré de Continuer · progression conservée", Toast.LENGTH_SHORT).show()
                }
                else store.remember(WatchProgress(entry, 0, details.durationMs, p?.completed != true, p?.updatedAt ?: System.currentTimeMillis()))
                primary?.text = primaryTitle(); if(isSeries) renderEpisodes()
                restartButton?.visibility = if(store.history().any { it.entry.key == entry.key && it.resumable }) View.VISIBLE else View.GONE
            }
        }.setNegativeButton("Retour", null).show()
    }
}
