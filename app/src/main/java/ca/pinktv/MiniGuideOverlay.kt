package ca.pinktv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A separate focus surface; opening/closing never changes or pauses the player. */
class MiniGuideOverlay(private val activity: Activity, account: Account, private val store: Store,
    private val stage: FrameLayout, private val current: () -> Entry?, private val tune: (Entry) -> Unit,
    private val dismissed: () -> Unit) {
    private val api = Xtream(account)
    private val main = Handler(Looper.getMainLooper())
    private val catalogueWorker = Executors.newSingleThreadExecutor()
    private val filterWorker = Executors.newSingleThreadExecutor()
    private val scheduleWorker = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private var catalogueTask: Future<*>? = null
    private var filterTask: Future<*>? = null
    private val pending = mutableMapOf<String, Future<*>>()
    private val scheduleTickets = mutableMapOf<String, Int>()
    private var nextTicket = 0
    private var session = 0
    private var request = 0
    private var loadedAt = 0L
    private var failed = false
    private var catalogue = emptyList<Entry>()
    private var categories = emptyMap<String, String>()
    private var shown = emptyList<Entry>()
    private var favouriteKeys = emptySet<String>()
    private var filter = "★ Favoris"
    private var selectedKey: String? = null
    private data class Cached(val programmes: List<Programme>, val at: Long, val failed: Boolean)
    private val schedules = object : LinkedHashMap<String, Cached>(96, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 96
    }
    private val time = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)
    private val panel = object : LinearLayout(activity) {
        override fun focusSearch(focused: View?, direction: Int): View? {
            val next = super.focusSearch(focused, direction)
            var node = next
            while(node != null) { if(node === this) return next; node = node.parent as? View }
            return focused
        }
    }
    private val tabs = linkedMapOf<String, Button>()
    private val list = ListView(activity)
    private val empty = LinearLayout(activity)
    private val spinner = ProgressBar(activity)
    private val message = text(17f, TvTheme.muted)
    private val emptyAction = button("Réessayer") { loadCatalogue() }
    var isOpen = false
        private set
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); border?.let { setStroke(dp(2), it) }
    }
    private fun text(size: Float, color: Int = Color.WHITE) = TextView(activity).apply {
        textSize = size; setTextColor(color); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        id = View.generateViewId(); text = label; textSize = 15f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE); background = shape(TvTheme.panel); defaultFocusHighlightEnabled = false
        setOnFocusChangeListener { _, focus ->
            background = shape(if(focus) TvTheme.accent else TvTheme.panel, if(focus) Color.WHITE else if(isSelected) TvTheme.accent else null)
            setTextColor(if(focus) TvTheme.background else Color.WHITE)
        }
        setOnClickListener { action() }
    }
    private class Row(val view: LinearLayout, val name: TextView, val now: TextView, val next: TextView)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.tag as? Row ?: run {
                val layout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(102)
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_selected), shape(TvTheme.selected, Color.WHITE))
                        addState(intArrayOf(android.R.attr.state_pressed), shape(TvTheme.selected, Color.WHITE))
                        addState(intArrayOf(), shape(TvTheme.row))
                    }
                }
                val name = text(18f).apply { setTypeface(null, Typeface.BOLD) }
                val now = text(14f); val next = text(13f, TvTheme.muted)
                listOf(name, now, next).forEach { layout.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) }
                Row(layout, name, now, next).also { layout.tag = it }
            }
            val entry = shown[position]; val saved = schedules[entry.id]
            val schedule = MiniGuideRules.schedule(saved?.programmes.orEmpty(), System.currentTimeMillis())
            row.name.text = activity.getString(R.string.mini_guide_channel,
                if(entry.key in favouriteKeys) "★ " else "", if(entry.number > 0) "${entry.number} · " else "", entry.name,
                if(entry.key == current()?.key) activity.getString(R.string.mini_guide_watching) else "")
            row.now.text = schedule.now?.let { "${time.format(Date(it.start))}–${time.format(Date(it.end))} · ${it.title}" }
                ?: when { saved == null -> "Chargement de l’horaire…"; saved.failed -> "Horaire indisponible"; else -> "Horaire non fourni pour ce moment" }
            row.next.text = schedule.next?.let { "Ensuite · ${time.format(Date(it.start))} · ${it.title}" } ?: "OK pour regarder en direct"
            row.view.contentDescription = "${row.name.text}. ${row.now.text}. ${row.next.text}"
            return row.view
        }
    }
    private val ticker = object : Runnable {
        override fun run() { if(isOpen) { adapter.notifyDataSetChanged(); loadVisible(); main.postDelayed(this, 30000) } }
    }
    init {
        panel.orientation = LinearLayout.VERTICAL; panel.setPadding(dp(14), dp(12), dp(14), dp(12))
        panel.background = shape(Color.argb(245, 25, 19, 31), TvTheme.accent)
        panel.elevation = dp(12).toFloat()
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(24f, TvTheme.accent).apply { setText(R.string.guide_title); setTypeface(null, Typeface.BOLD); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }, LinearLayout.LayoutParams(0, dp(40), 1f))
        heading.addView(text(13f, TvTheme.muted).apply { setText(R.string.mini_guide_close_hint); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES })
        panel.addView(heading)
        val controls = LinearLayout(activity)
        listOf("★ Favoris", "Québec", "Toutes").forEach { label ->
            val b = button(label) { filter = label; selectedKey = current()?.key; render(true) }
            tabs[label] = b; controls.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, dp(4), dp(6), dp(6)) })
        }
        panel.addView(controls)
        panel.addView(text(13f, TvTheme.muted).apply { setText(R.string.mini_guide_keys); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }, LinearLayout.LayoutParams(-1, dp(24)))
        val content = FrameLayout(activity)
        list.id = View.generateViewId(); list.adapter = adapter; list.dividerHeight = dp(6)
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.isFocusable = true
        list.setOnKeyListener { _, code, event ->
            if(code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                tabs.getValue(filter).requestFocus(); true
            } else false
        }
        list.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { tune(it); close() } }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedKey = shown.getOrNull(position)?.key; loadVisible() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) { loadVisible() }
        })
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.orientation = LinearLayout.VERTICAL; empty.gravity = Gravity.CENTER
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(TvTheme.accent)
        empty.addView(spinner, LinearLayout.LayoutParams(dp(42), dp(42)))
        message.maxLines = 4; message.gravity = Gravity.CENTER; message.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        message.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        empty.addView(message, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(12)) })
        empty.addView(emptyAction, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        tabs.values.forEach { b -> b.nextFocusDownId = list.id; b.nextFocusUpId = b.id }
        list.nextFocusUpId = tabs.getValue("★ Favoris").id; list.nextFocusDownId = list.id
    }
    fun open() {
        if(isOpen) return
        isOpen = true; session++; filter = "★ Favoris"; selectedKey = current()?.key
        val inset = TvSafeArea.insets(activity, store.prefs.getBoolean("wide_safe_area", false))
        val width = ((activity.resources.displayMetrics.widthPixels - 2 * inset.horizontal) * if(DeviceLayout.compact(activity)) 1f else .58f).toInt()
        stage.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.END).apply {
            rightMargin = inset.horizontal; topMargin = inset.vertical; bottomMargin = inset.vertical
        })
        tabs.getValue("★ Favoris").requestFocus()
        render(true)
        if(System.currentTimeMillis() - loadedAt > 300000) loadCatalogue()
        main.postDelayed(ticker, 30000)
    }
    fun close(notify: Boolean = true) {
        if(!isOpen) return
        isOpen = false; session++; request++; main.removeCallbacks(ticker)
        catalogueTask?.cancel(true); catalogueTask = null; filterTask?.cancel(true); filterTask = null
        pending.values.forEach { it.cancel(true) }; pending.clear(); scheduleTickets.clear(); scheduleWorker.purge()
        stage.removeView(panel)
        if(notify) dismissed()
    }
    fun dispose() { close(false); catalogueWorker.shutdownNow(); filterWorker.shutdownNow(); scheduleWorker.shutdownNow(); main.removeCallbacksAndMessages(null) }
    private fun loadCatalogue(focusResults: Boolean = false) {
        if(!isOpen || catalogueTask != null) return
        val token = session; failed = false
        catalogueTask = catalogueWorker.submit {
            val result = runCatching { api.categories("live").associate { it.id to it.name } to api.entries("live") }
            main.post {
                if(!isOpen || session != token) return@post
                catalogueTask = null
                result.onSuccess { (names, entries) -> categories = names; catalogue = entries; loadedAt = System.currentTimeMillis() }
                    .onFailure { failed = true }
                render(focusResults || list.hasFocus() || emptyAction.hasFocus())
            }
        }
        if(filter != "★ Favoris" && catalogue.isEmpty()) showEmpty(true)
    }
    fun favouritesChanged() { if(isOpen) render(list.hasFocus()) }
    private fun render(focus: Boolean) {
        if(!isOpen) return
        val token = session; val id = ++request; filterTask?.cancel(true)
        val selectedFilter = filter; val entries = catalogue; val names = categories
        val saved = store.favouriteEntries(); val favourites = store.favourites(); favouriteKeys = favourites
        tabs.forEach { (name, b) ->
            b.isSelected = name == filter; b.text = if(b.isSelected) "✓ $name" else name
            b.background = shape(if(b.hasFocus()) TvTheme.accent else TvTheme.panel, if(b.hasFocus()) Color.WHITE else if(b.isSelected) TvTheme.accent else null)
        }
        list.visibility = View.INVISIBLE; showEmpty(true)
        filterTask = filterWorker.submit {
            val result = runCatching { MiniGuideRules.channels(entries, saved, names, favourites, selectedFilter) }
            if(Thread.currentThread().isInterrupted) return@submit
            main.post {
                if(!isOpen || session != token || request != id) return@post
                shown = result.getOrDefault(emptyList()); filterTask = null
                adapter.notifyDataSetChanged()
                list.visibility = if(shown.isEmpty()) View.GONE else View.VISIBLE
                empty.visibility = if(shown.isEmpty()) View.VISIBLE else View.GONE
                if(shown.isEmpty()) { showEmpty(selectedFilter != "★ Favoris" && catalogueTask != null); if(focus) tabs.getValue(selectedFilter).requestFocus() }
                else {
                    list.setSelection(shown.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0))
                    if(focus) list.requestFocus()
                    list.post { if(isOpen && session == token) loadVisible() }
                }
            }
        }
    }
    private fun showEmpty(loading: Boolean) {
        empty.visibility = View.VISIBLE; spinner.visibility = if(loading) View.VISIBLE else View.GONE
        emptyAction.visibility = if(loading) View.GONE else View.VISIBLE
        message.text = when {
            loading -> "Un instant…"
            filter == "★ Favoris" -> "Tes chaînes favorites apparaîtront ici. Tu peux aussi choisir Québec ou Toutes."
            failed -> "La liste des chaînes ne répond pas. Tes favoris restent disponibles."
            else -> "Aucune chaîne dans cette sélection."
        }
        emptyAction.text = when { filter == "★ Favoris" -> "Voir Québec"; failed -> "Réessayer"; else -> "Voir toutes les chaînes" }
        emptyAction.setOnClickListener {
            when { filter == "★ Favoris" -> { filter = "Québec"; render(true) }
                failed -> loadCatalogue(true)
                else -> { filter = "Toutes"; render(true) } }
        }
    }
    private fun loadVisible() {
        if(!isOpen || list.visibility != View.VISIBLE) return
        val start = list.firstVisiblePosition.coerceAtLeast(0)
        val visible = shown.drop(start).take((list.childCount + 1).coerceIn(1, 5))
        val keys = visible.map { it.id }.toSet()
        pending.keys.filter { it !in keys }.forEach { id -> pending.remove(id)?.cancel(true); scheduleTickets.remove(id) }
        scheduleWorker.purge()
        visible.forEach { entry ->
            val cached = schedules[entry.id]; val age = System.currentTimeMillis() - (cached?.at ?: 0)
            if(entry.id in pending || cached != null && age < if(cached.failed) 60000 else 300000) return@forEach
            val token = session
            val ticket = ++nextTicket; scheduleTickets[entry.id] = ticket
            pending[entry.id] = scheduleWorker.submit {
                val result = runCatching { api.guide(entry) }
                main.post {
                    if(!isOpen || session != token || scheduleTickets[entry.id] != ticket) return@post
                    pending.remove(entry.id); scheduleTickets.remove(entry.id)
                    val now = System.currentTimeMillis()
                    schedules[entry.id] = Cached(result.getOrDefault(emptyList()).filter { it.end > now }.take(16).map { it.copy(description = "") }, now, result.isFailure)
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }
}
