package ca.pinktv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only five channel rows are drawn, regardless of the size of the supplier's catalogue. */
class TvGuideGrid(context: Context) : View(context) {
    var channels = emptyList<Entry>(); private set
    var selected = 0; private set
    var first = 0; private set
    var windowStart = GuideWindow.floor(System.currentTimeMillis()); private set
    private var cursor = System.currentTimeMillis()
    private var followingNow = true
    var programmes: (Entry) -> List<Programme> = { emptyList() }
    var status: (Entry) -> String = { "Chargement de l’horaire…" }
    var favourite: (Entry) -> Boolean = { false }
    var onSelection: () -> Unit = {}
    var onOpen: (Entry, Programme?) -> Unit = { _, _ -> }
    var onFavourite: () -> Unit = {}
    var onWindow: () -> Unit = {}
    private val density = resources.displayMetrics.density
    private fun px(n: Float) = n * density
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = TvTheme.accent
    private val muted = TvTheme.muted
    private val clock = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)
    private val dateScratch = Date(0)
    private val rectangle = RectF()
    private fun stamp(value: Long): String { dateScratch.time = value; return clock.format(dateScratch) }
    private fun rounded(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        rectangle.set(left, top, right, bottom); c.drawRoundRect(rectangle, px(7f), px(7f), fill)
    }
    private val column get() = minOf(px(184f), width * .25f)
    private val header get() = px(30f)
    private val visibleRows get() = if(DeviceLayout.compact(context)) ((height-header)/px(108f)).toInt().coerceIn(3,8) else 5
    private val rowHeight get() = (height - header) / visibleRows
    init {
        isFocusable = true; isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { channels.getOrNull(selected)?.let { onOpen(it, selectedProgramme()) } }
        setOnLongClickListener { onFavourite(); true }
    }
    fun setChannels(values: List<Entry>, focusId: String? = null) {
        val same = values == channels && focusId == null
        val old = focusId ?: channels.getOrNull(selected)?.id
        channels = values; selected = values.indexOfFirst { it.id == old }.coerceAtLeast(0)
        if (!same) first = (selected - visibleRows/2).coerceIn(0, maxOf(0, values.size - visibleRows))
        changed()
    }
    fun visible() = channels.drop(first).take(visibleRows)
    private fun slots(entry: Entry) = GuideWindow.slots(programmes(entry), windowStart, windowStart + GuideWindow.SPAN)
    fun selectedProgramme(): Programme? = channels.getOrNull(selected)?.let { GuideWindow.at(slots(it), cursor)?.programme }
    fun selectedEntry() = channels.getOrNull(selected)
    fun refresh() {
        if (followingNow) {
            cursor = System.currentTimeMillis()
            if (cursor !in windowStart until windowStart + GuideWindow.SPAN) { windowStart = GuideWindow.floor(cursor); onWindow() }
        }
        updateDescription(); invalidate(); onSelection()
    }
    fun now() { followingNow = true; windowStart = GuideWindow.floor(System.currentTimeMillis()); cursor = System.currentTimeMillis(); changed(); onWindow() }
    fun shift(direction: Int) {
        val base = GuideWindow.floor(System.currentTimeMillis())
        val next = (windowStart + direction * GuideWindow.SPAN).coerceIn(base, base + 5 * GuideWindow.SPAN)
        if (next != windowStart) { followingNow = false; windowStart = next; cursor = next; changed(); onWindow() }
    }
    private fun changed() { updateDescription(); invalidate(); onSelection() }
    private fun updateDescription() {
        val entry = selectedEntry()
        val p = selectedProgramme()
        contentDescription = if (entry == null) "Guide TV, aucune chaîne" else
            "${selected + 1} sur ${channels.size}. ${entry.name}. ${p?.let { "${it.title}, ${clock.format(Date(it.start))} à ${clock.format(Date(it.end))}" } ?: status(entry)}. OK pour regarder ou ouvrir les détails. Menu pour les options."
        if (hasFocus()) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); changed()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1
                val step = if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) visibleRows else 1
                val next = (selected + direction * step).coerceIn(0, channels.lastIndex)
                if (next == selected) return if(direction > 0) true else super.onKeyDown(keyCode, event)
                selected = next
                if (selected < first) first = selected
                if (selected >= first + visibleRows) first = selected - visibleRows + 1
                changed(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                followingNow = false
                val slots = slots(channels[selected]); val i = slots.indexOfFirst { cursor >= it.start && cursor < it.end }.coerceAtLeast(0)
                val next = i + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (next in slots.indices) { cursor = slots[next].start; changed() }
                else shift(if (next < 0) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_MENU -> { onFavourite(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    private var touchX=0f
    private var touchY=0f
    private var touchTime=0L
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.action==MotionEvent.ACTION_DOWN) { touchX=event.x; touchY=event.y; touchTime=event.eventTime; return true }
        if (event.action == MotionEvent.ACTION_UP && event.y >= header && rowHeight > 0) {
            val dx=event.x-touchX; val dy=event.y-touchY
            if(kotlin.math.abs(dy)>px(40f) && kotlin.math.abs(dy)>kotlin.math.abs(dx)) {
                first=(first + if(dy<0) visibleRows else -visibleRows).coerceIn(0,maxOf(0,channels.size-visibleRows))
                selected=first; changed(); return true
            }
            if(kotlin.math.abs(dx)>px(50f)) { shift(if(dx<0) 1 else -1); return true }
            val index = first + ((event.y - header) / rowHeight).toInt()
            if (index in channels.indices) {
                selected = index
                followingNow = event.x <= column
                cursor = if (event.x <= column) System.currentTimeMillis().coerceIn(windowStart, windowStart + GuideWindow.SPAN - 1)
                else (windowStart + (event.x - column) / (width - column) * GuideWindow.SPAN).toLong().coerceIn(windowStart, windowStart + GuideWindow.SPAN - 1)
                if(DeviceLayout.compact(context)) {
                    followingNow=windowStart==GuideWindow.floor(System.currentTimeMillis())
                    cursor=if(followingNow) System.currentTimeMillis() else windowStart
                }
                requestFocus(); changed(); if(event.eventTime-touchTime>=500) onFavourite() else performClick()
            }
            return true
        }
        return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }
    override fun performClick(): Boolean = super.performClick()
    private fun text(c: Canvas, value: String, x: Float, y: Float, available: Float, size: Float, color: Int, bold: Boolean = false) {
        if (available <= px(8f)) return
        ink.color = color; ink.textSize = px(size) * resources.configuration.fontScale.coerceAtMost(1.3f)
        ink.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(TextUtils.ellipsize(value.replace('\n', ' '), ink, available, TextUtils.TruncateAt.END).toString(), x, y, ink)
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= column || rowHeight <= 0) return
        if(DeviceLayout.compact(context)) { drawMobile(c); return }
        val timeline = width - column
        text(c, "CHAÎNES  ·  ${if(channels.isEmpty()) 0 else selected + 1}/${channels.size}", px(10f), px(20f), column - px(16f), 11f, muted, true)
        for (i in 0..3) text(c, stamp(windowStart + i * GuideWindow.HALF_HOUR), column + timeline * i / 4 + px(8f), px(20f), timeline / 4 - px(12f), 13f, muted)
        visible().forEachIndexed { row, entry ->
            val top = header + row * rowHeight; val bottom = top + rowHeight - px(4f)
            val active = first + row == selected
            fill.color = if (active) TvTheme.selectedChannel else TvTheme.row
            rounded(c, 0f, top, column - px(5f), bottom)
            val ordinal = entry.number.takeIf { it > 0 } ?: (first + row + 1)
            text(c, "${if(favourite(entry)) "★ " else ""}$ordinal", px(10f), top + rowHeight * .34f, column - px(20f), 11f, accent, true)
            text(c, entry.name, px(10f), top + rowHeight * .73f, column - px(20f), 16f, Color.WHITE, active)
            slots(entry).forEach { slot ->
                val left = column + timeline * (slot.start - windowStart).toFloat() / GuideWindow.SPAN
                val right = column + timeline * (slot.end - windowStart).toFloat() / GuideWindow.SPAN - px(4f)
                if (right <= left) return@forEach
                val focused = active && cursor >= slot.start && cursor < slot.end
                val live = slot.programme?.isNow(System.currentTimeMillis()) == true
                fill.style = Paint.Style.FILL
                fill.color = when { focused && hasFocus() -> TvTheme.selected; live -> TvTheme.live; else -> TvTheme.panel }
                rounded(c, left, top, right, bottom)
                if (focused) {
                    fill.style = Paint.Style.STROKE; fill.strokeWidth = px(if(hasFocus()) 2.5f else 1f); fill.color = if(hasFocus()) accent else muted
                    rounded(c, left + px(1.5f), top + px(1.5f), right - px(1.5f), bottom - px(1.5f)); fill.style = Paint.Style.FILL
                }
                text(c, slot.programme?.title ?: status(entry), left + px(10f), top + rowHeight * .43f, right - left - px(18f), 16f, if(slot.programme != null) Color.WHITE else muted, focused)
                slot.programme?.let { text(c, "${stamp(it.start)} – ${stamp(it.end)}", left + px(10f), top + rowHeight * .78f, right - left - px(18f), 11f, muted) }
            }
        }
        val now = System.currentTimeMillis()
        if (now >= windowStart && now < windowStart + GuideWindow.SPAN) {
            val x = column + timeline * (now - windowStart).toFloat() / GuideWindow.SPAN
            fill.color = accent; fill.strokeWidth = px(1f); c.drawLine(x, header, x, header + rowHeight * minOf(5, channels.size), fill)
        }
    }
    private fun drawMobile(c: Canvas) {
        text(c,"${channels.size} chaîne${if(channels.size>1) "s" else ""} · Glisse pour naviguer",px(4f),px(20f),width-px(8f),12f,muted)
        visible().forEachIndexed { row,entry ->
            val top=header+row*rowHeight; val bottom=top+rowHeight-px(8f)
            val active=first+row==selected
            fill.style=Paint.Style.FILL; fill.color=if(active) TvTheme.selectedChannel else TvTheme.panel
            rounded(c,0f,top,width.toFloat(),bottom)
            if(active) { fill.style=Paint.Style.STROKE; fill.strokeWidth=px(1.5f); fill.color=accent; rounded(c,px(1f),top+px(1f),width-px(1f),bottom-px(1f)); fill.style=Paint.Style.FILL }
            val p=GuideWindow.at(slots(entry),cursor)?.programme
            val next=p?.let { present -> programmes(entry).firstOrNull { it.start>=present.end } }
            val available=width-px(28f)
            text(c,"${if(favourite(entry)) "★ " else ""}${entry.name}",px(14f),top+px(23f),available,15f,accent,true)
            text(c,p?.title ?: "En direct",px(14f),top+px(49f),available,18f,Color.WHITE,true)
            text(c,p?.let { "${stamp(it.start)} – ${stamp(it.end)}" } ?: "Toucher pour regarder · Horaire non fourni",px(14f),top+px(71f),available,13f,muted)
            if(next!=null && rowHeight>px(100f)) text(c,"Ensuite · ${next.title}",px(14f),bottom-px(13f),available,13f,muted)
        }
    }
}
