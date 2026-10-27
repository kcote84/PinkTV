package ca.pinktv

import android.view.View
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.ScrollView

/** Back parks at the toolbar; Down restores the selected row and its viewport. */
class HeaderNavigation(private val headers: List<View>, private val content: View,
    preferred: View? = null, private val list: AbsListView? = content as? AbsListView,
    private val downHeaders: List<View> = headers, private val itemKey: ((Int) -> String?)? = null) {
    var parked = false
        private set
    private var lastHeader = preferred ?: headers.firstOrNull()
    private var lastContent: View? = null
    private var lastTag: Any? = null
    private var position = 0
    private var hasPosition = false
    private var selectedKey: String? = null
    private var rowTop = 0
    private var scrollY = 0
    private var observer: ViewTreeObserver? = null
    private val listener = ViewTreeObserver.OnGlobalFocusChangeListener { old, next ->
        if(old != null && inside(content, old) && headers.none { inside(it, old) }) remember()
        val header = headers.firstOrNull { next != null && inside(it, next) }
        if(header != null) lastHeader = header
        else if(next != null && inside(content, next)) {
            parked = false
            if(next !== content) { lastContent = next; lastTag = next.tag }
        }
    }

    init {
        headers.forEach { if(it.id == View.NO_ID) it.id = View.generateViewId() }
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attach() }
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(listener); observer = null
            }
        })
        if(content.isAttachedToWindow) attach()
    }
    private fun attach() {
        if(observer != null) return
        observer = content.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(listener) }
    }
    private fun available(view: View?) = view != null && view.isShown && view.isEnabled && view.isFocusable
    private fun remember() {
        list?.let { rows ->
            val selected = rows.selectedItemPosition
            if(selected >= 0) {
                position = selected
                hasPosition = true
                selectedKey = itemKey?.invoke(selected)
                rowTop = (rows.getChildAt(selected - rows.firstVisiblePosition)?.top ?: rows.listPaddingTop) - rows.listPaddingTop
            }
        }
        if(content is ScrollView) scrollY = content.scrollY
    }
    fun handleBack(): Boolean {
        if(headers.any { it.hasFocus() }) return false
        val target = lastHeader?.takeIf { available(it) } ?: headers.firstOrNull { available(it) } ?: return false
        remember()
        val moved = target.requestFocus()
        if(moved) parked = true
        return moved
    }
    fun beginContentRequest() { parked = false }
    fun holdsHeaderFocus() = headers.any { it.hasFocus() }
    fun handleDown(): Boolean {
        if(!downHeaders.any { it.hasFocus() }) return false
        val rows = list?.takeIf { available(it) && it.count > 0 }
        if(rows != null) {
            val restored = selectedKey?.let { key -> (0 until rows.count).firstOrNull { itemKey?.invoke(it) == key } }
            val selected = (restored ?: if(hasPosition) position else rows.selectedItemPosition.coerceAtLeast(0)).coerceIn(0, rows.count - 1)
            val top = if(hasPosition) rowTop else (rows.getChildAt(selected - rows.firstVisiblePosition)?.top ?: rows.listPaddingTop) - rows.listPaddingTop
            if(!rows.requestFocus()) return false
            parked = false
            rows.setSelectionFromTop(selected, top)
            rows.post {
                if(rows.isAttachedToWindow && rows.hasFocus() && !parked) rows.setSelectionFromTop(selected, top)
            }
            return true
        }
        val target = lastContent?.takeIf { available(it) && inside(content,it) }
            ?: lastTag?.let { content.findViewWithTag<View>(it) }?.takeIf { available(it) }
            ?: content.getFocusables(View.FOCUS_DOWN).firstOrNull { it !== content && available(it) && headers.none { h -> inside(h,it) } }
            ?: return false
        if(headers.any { inside(it,target) }) return false
        val y = scrollY
        if(!target.requestFocus()) return false
        parked = false
        if(content is ScrollView) content.post { if(!parked && content.isAttachedToWindow) content.scrollTo(0,y) }
        return true
    }
    private fun inside(parent: View, child: View): Boolean {
        var current: View? = child
        while(current != null) { if(current === parent) return true; current = current.parent as? View }
        return false
    }
}
