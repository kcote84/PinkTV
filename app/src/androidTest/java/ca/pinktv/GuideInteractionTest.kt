package ca.pinktv

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuideInteractionTest {
    private fun descendants(view: View): List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Suppress("DEPRECATION")
    private fun withLayout(tv: Boolean,widthDp: Int,fontScale: Float=1f,verify: (MainActivity,Float) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val resources=activity.resources
                val previous=Configuration(resources.configuration)
                val metrics=DisplayMetrics().apply { setTo(resources.displayMetrics) }
                val config=Configuration(previous).apply {
                    uiMode=(uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or if(tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                    screenWidthDp=widthDp; screenHeightDp=if(tv) 540 else 850
                    this.fontScale=fontScale
                }
                resources.updateConfiguration(config,metrics)
                try { verify(activity,resources.displayMetrics.density) }
                finally { resources.updateConfiguration(previous,metrics) }
            }
        }
    }
    private fun assertLabelsFit(buttons: List<Button>,density: Float) {
        assertEquals(7,buttons.size)
        buttons.forEach { button ->
            val layout=button.layout ?: error("Label was not laid out")
            assertEquals(button.text.toString(),1,layout.lineCount)
            assertEquals(button.text.toString(),0,layout.getEllipsisCount(0))
            assertTrue("Full label fits: ${button.text}",layout.getLineWidth(0)<=button.width-button.compoundPaddingLeft-button.compoundPaddingRight+1)
            assertTrue("Button height is not clipped",button.height>=48*density)
        }
    }
    private fun toolbar(widthDp: Int,fontScale: Float,verify: (HorizontalScrollView,List<Button>) -> Unit) = withLayout(true,widthDp,fontScale) { activity,density ->
        val guide=TvGuideScreen(activity,null,Store(activity),{},{},{_,_->})
        try {
            val width=(widthDp*density).toInt(); val height=(540*density).toInt()
            guide.view.setPadding(width/20,height/20,width/20,height/20)
            guide.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            guide.view.layout(0,0,width,height)
            val scroll=descendants(guide.view).filterIsInstance<HorizontalScrollView>().single()
            val buttons=descendants(scroll).filterIsInstance<Button>()
            assertLabelsFit(buttons,density)
            verify(scroll,buttons)
        } finally { guide.close() }
    }
    @Test fun tvToolbarUsesTheFullWidthWithoutCuttingLabels() = toolbar(960,1f) { scroll,buttons ->
        assertFalse("All seven tabs fit the TV viewport",scroll.canScrollHorizontally(1))
        assertTrue(buttons.last().right<=scroll.width)
        assertTrue("Unused horizontal space is distributed",buttons.last().right>scroll.width*.9f)
    }
    @Test fun narrowTvWithLargeTextScrollsInsteadOfCuttingLabels() = toolbar(640,1.5f) { scroll,buttons ->
        assertTrue("Large labels remain reachable",scroll.canScrollHorizontally(1))
        scroll.scrollTo(buttons.last().right,0)
        assertTrue(scroll.scrollX>0)
        assertTrue(buttons.last().right-scroll.scrollX<=scroll.width)
    }
    @Test fun mobileSwipesBrowseTimeAndChannelsWithoutStartingPlayback() = withLayout(false,400) { activity,density ->
        val grid=TvGuideGrid(activity)
        val width=(400*density).toInt(); val height=(850*density).toInt()
        grid.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        grid.layout(0,0,width,height)
        val start=grid.windowStart
        grid.programmes={ (0..24).map { i -> Programme("Émission $i","",start+i*GuideWindow.HALF_HOUR,start+(i+1)*GuideWindow.HALF_HOUR) } }
        grid.setChannels((1..15).map { Entry(it.toString(),"Chaîne $it","","live") })
        var opened=0; grid.onOpen={ _,_-> opened++ }
        fun swipe(x1: Float,y1: Float,x2: Float,y2: Float) {
            val time=android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x1*density,y1*density,0),MotionEvent.obtain(time,time+150,MotionEvent.ACTION_UP,x2*density,y2*density,0)).forEach { event -> grid.onTouchEvent(event); event.recycle() }
        }
        swipe(340f,300f,60f,300f)
        assertEquals(start+GuideWindow.SPAN,grid.windowStart)
        assertEquals("Émission 4",grid.selectedProgramme()?.title)
        assertEquals("1",grid.selectedEntry()?.id)
        assertEquals(0,opened)
        swipe(60f,300f,340f,300f)
        assertEquals(start,grid.windowStart)
        swipe(60f,300f,340f,300f)
        assertEquals("Cannot browse before the current window",start,grid.windowStart)
        swipe(200f,650f,200f,250f)
        assertTrue(grid.first>0)
        assertEquals(start,grid.windowStart)
        assertEquals(0,opened)
    }
}
