package ca.pinktv

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.GridView
import java.io.File

/** Uses an in-process provider fixture; no subscription or private credentials. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun await(message: String,timeout: Long=15000,condition: () -> Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) { if(condition()) return; Thread.sleep(100) }
        assertTrue(message,condition())
    }
    private class Provider(private val sample: ByteArray): AutoCloseable {
        private val server=ServerSocket(0)
        private val pool=Executors.newCachedThreadPool()
        @Volatile private var closed=false
        val root="http://127.0.0.1:${server.localPort}"
        val seen=java.util.Collections.synchronizedList(mutableListOf<String>())
        init { pool.execute { while(!closed) { val socket=runCatching { server.accept() }.getOrNull() ?: break; pool.execute { handle(socket) } } } }
        private fun handle(socket: Socket) { socket.use {
            runCatching {
                val input=it.getInputStream().bufferedReader()
                val line=input.readLine() ?: return
                val path=line.split(' ')[1]; seen.add(path)
                while(!input.readLine().isNullOrEmpty()) { }
                val output=it.getOutputStream()
                if(path.startsWith("/live/")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
                    if(path.contains("/3.ts")) { output.write(sample); output.flush(); return }
                    var cursor=0
                    while(!closed) { val count=minOf(1316,sample.size-cursor); output.write(sample,cursor,count); output.flush(); cursor=(cursor+count)%sample.size; Thread.sleep(5) }
                } else {
                    val action=path.substringAfter("action=","").substringBefore('&')
                    val body=when(action) {
                        "get_live_categories","get_vod_categories","get_series_categories" -> """[{"category_id":"1","category_name":"Québec Français"}]"""
                        "get_live_streams" -> """[{"stream_id":1,"name":"Chaîne de test","category_id":"1"}]"""
                        "get_vod_streams" -> """[{"stream_id":2,"name":"Film VF","category_id":"1","container_extension":"mp4"}]"""
                        "get_series" -> """[{"series_id":7,"name":"Série VF","category_id":"1"}]"""
                        "get_series_info" -> """{"info":{"name":"Série VF","plot":"Résumé"},"episodes":{"1":[{"id":"8","title":"Épisode 1","season":1,"episode_num":1,"container_extension":"mp4"}]}}"""
                        "get_simple_data_table","get_short_epg" -> """{"epg_listings":[]}"""
                        else -> """{"user_info":{"auth":1,"status":"Active"},"server_info":{"timezone":"America/Toronto"}}"""
                    }
                    val bytes=body.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(bytes); output.flush()
                }
            }
        } }
        override fun close() { closed=true; server.close(); pool.shutdownNow() }
    }
    private fun fixture()=Provider(instrumentation.context.assets.open("sample.ts").use { it.readBytes() })
    private fun find(view: View,predicate: (View) -> Boolean): View? {
        if(predicate(view)) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),predicate)?.let { return it }
        return null
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500) // Software-rendered emulators can lag behind the view tree.
        val file=File(context.getExternalFilesDir(null),"qa/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun clear() {
        context.getSharedPreferences("pinktv",Context.MODE_PRIVATE).edit().clear().commit()
        Recordings.jobs(context).forEach { if(it.optString("status") in RecordingRules.active) Recordings.cancel(context,it.getString("id")) }
        await("writer stopped") { RecordingService.activeId==null }
        Recordings.jobs(context).forEach { Recordings.delete(context,it.getString("id")) }
    }
    @Test fun directXtreamEncryptedAccountAndLocalProfiles() {
        clear()
        fixture().use { fixture ->
            val account=Account(fixture.root,"test+user","dummy/&pass")
            val api=Xtream(account); api.authenticate()
            assertEquals("1",api.categories("live").single().id)
            assertEquals("1",api.entries("live").single().id)
            assertEquals("vod",api.entries("vod").single().kind)
            assertEquals("8",api.details(api.entries("series").single()).episodes.single().entry.id)
            assertTrue(fixture.seen.any { it.contains("test%2Buser") && it.contains("dummy%2F%26pass") })
            val store=Store(context); store.save(account)
            assertEquals(account,Store(context).account())
            val encrypted=store.prefs.getString("account","")!!
            assertFalse(encrypted.contains(account.password)); assertFalse(encrypted.contains(account.username))
            val entry=api.entries("live").single(); store.setFavourite(entry,true)
            store.save(account.copy(username="second")); assertTrue(store.favourites().isEmpty())
            store.save(account); assertEquals(setOf(entry.key),store.favourites())
            store.save(account.copy(password="changed")); assertEquals(setOf(entry.key),store.favourites())
            assertTrue(api.stream(entry,true).endsWith(".m3u8")); assertTrue(api.stream(entry,false).endsWith(".ts"))
            assertTrue("bundled stereo decoder loads",androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable())
            assertPlayable(api.stream(entry,false))
        }
    }
    @Test fun recordingCompletesPlaysLocallyAndCancellationKeepsPartialFile() {
        clear()
        fixture().use { fixture ->
            Store(context).save(Account(fixture.root,"test","dummy"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val now=System.currentTimeMillis()
                val id=Recordings.schedule(context,Entry("1","Chaîne de test","1","live"),"Capture complète",now,now+4000)
                await("capture started") { RecordingService.activeId==id }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                await("capture complete") { Recordings.jobs(context).first { it.getString("id")==id }.getString("status")=="complete" }
                await("writer closed") { RecordingService.activeId==null }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                assertTrue(Recordings.file(context,id).length()>0)
                assertPlayable(Recordings.uri(context,id))
                val start=System.currentTimeMillis()
                val partial=Recordings.schedule(context,Entry("1","Chaîne de test","1","live"),"Capture arrêtée",start,start+15000)
                await("partial bytes received") { Recordings.file(context,partial).length()>0 }
                Recordings.cancel(context,partial)
                await("cancel closes network") { RecordingService.activeId==null }
                assertEquals("cancelled",Recordings.jobs(context).first { it.getString("id")==partial }.getString("status"))
                assertTrue(Recordings.file(context,partial).length()>0)
                Recordings.delete(context,partial); assertFalse(Recordings.file(context,partial).exists())
                val start2=System.currentTimeMillis()
                val interrupted=Recordings.schedule(context,Entry("3","Flux interrompu","1","live"),"Capture interrompue",start2,start2+10000)
                await("EOF marked incomplete") { Recordings.jobs(context).first { it.getString("id")==interrupted }.getString("status")=="incomplete" }
                await("writer closed after EOF") { RecordingService.activeId==null }
                assertTrue(Recordings.file(context,interrupted).length()>0)
                val future=System.currentTimeMillis()+3000
                val scheduled=Recordings.schedule(context,Entry("1","Chaîne programmée","1","live"),"Capture à venir",future,future+3000)
                assertEquals("pending",Recordings.jobs(context).first { it.getString("id")==scheduled }.getString("status"))
                await("exact alarm starts and completes capture") { Recordings.jobs(context).first { it.getString("id")==scheduled }.getString("status")=="complete" }
                await("scheduled writer closed") { RecordingService.activeId==null }
            }
        }
    }
    @Test fun phoneScreensKeepCataloguesSeasonsAndGuideUsable() {
        clear()
        fixture().use { fixture ->
            Store(context).save(Account(fixture.root,"test","dummy"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                screenshot("phone-home")
                scenario.onActivity { activity -> find(activity.window.decorView) { it.contentDescription=="Séries" }!!.performClick() }
                await("series catalogue displayed") {
                    var present=false; scenario.onActivity { activity -> present=find(activity.window.decorView) { it is GridView && it.count==1 }!=null }; present
                }
                screenshot("phone-series")
                scenario.onActivity { activity ->
                    val grid=find(activity.window.decorView) { it is GridView } as GridView
                    assertEquals(2,grid.numColumns); grid.performItemClick(grid.getChildAt(0),0,0)
                }
                await("series season and episode displayed") {
                    var present=false; scenario.onActivity { activity -> present=find(activity.window.decorView) { it is TextView && it.text.contains("1. Épisode 1") }!=null }; present
                }
                screenshot("phone-seasons")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> find(activity.window.decorView) { it.contentDescription=="Télé" }!!.performClick() }
                await("guide loaded") {
                    var present=false; scenario.onActivity { activity -> present=find(activity.window.decorView) { it is TvGuideGrid && it.channels.isNotEmpty() }!=null }; present
                }
                screenshot("phone-guide")
            }
        }
    }
    private fun assertPlayable(uri: String) {
        val ready=CountDownLatch(1); var player: ExoPlayer?=null
        instrumentation.runOnMainSync {
            player=ExoPlayer.Builder(context,StereoRenderersFactory(context)).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context,DefaultHttpDataSource.Factory()))).build().apply {
                addListener(object: Player.Listener { override fun onPlaybackStateChanged(state: Int) { if(state==Player.STATE_READY) ready.countDown() } })
                setMediaItem(MediaItem.fromUri(uri)); prepare()
            }
        }
        try { assertTrue("direct or local stream reaches READY",ready.await(15,TimeUnit.SECONDS)) } finally { instrumentation.runOnMainSync { player?.release() } }
    }
}
