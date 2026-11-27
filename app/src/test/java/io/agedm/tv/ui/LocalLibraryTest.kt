package io.agedm.tv.ui

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import io.agedm.tv.R
import io.agedm.tv.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalLibraryTest {
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun castCodeCanBeScannedAfterScaling() {
        val url = "http://192.168.1.101:9900/"
        val bitmap = createQrBitmap(url)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val image = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        assertEquals(url, com.google.zxing.qrcode.QRCodeReader().decode(image).text)
    }

    @Test fun indexedTitlesAndAliasesWorkOfflineOnOlderTv() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = PersistentJsonStore(AppStorageDatabase.get(context))
        val names = listOf(AgeBangumiLookupMetadata(13, "進擊的巨人", "Attack on Titan", listOf("进击巨人")),
            AgeBangumiLookupMetadata(17, "GATE 奇幻自卫队 第二季"), AgeBangumiLookupMetadata(18, "86－不存在的战区"))
        for (item in names) store.write("age_lookup_${item.animeId}", Json.encodeToString(item))
        val client = OkHttpClient.Builder().addInterceptor { error("offline") }.build()
        val index = AgeRepository(persistentStore = store, client = client).loadInitialsIndex()
        for (query in listOf("jjdjr","jjjr","AOT")) assertEquals(listOf(13L), index.search(query).map { it.animeId })
        assertEquals(17L, index.search("QHZWD").single().animeId)
        assertEquals(18L, index.search("86BCZ").single().animeId)
        assertTrue(index.search("").isEmpty())
    }

    @Test fun freshInstallCanSearchBundledCatalogWithoutNetwork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val titles = context.resources.openRawResource(R.raw.age_title_index).bufferedReader().use {
            Json.decodeFromString<List<AgeBangumiLookupMetadata>>(it.readText())
        }
        assertEquals(titles.size, titles.distinctBy { it.animeId }.size)
        val index = AgeRepository(client = OkHttpClient.Builder().addInterceptor { error("offline") }.build(),
            bundledTitles = { titles }).loadInitialsIndex()
        assertTrue(index.search("HYRZ").any { it.title == "火影忍者" })
        assertTrue(index.search("QHZWD").any { "自卫队" in it.title })
        assertTrue(index.search("YZNZ").any { it.title == "星际牛仔" })
    }

    @Test
    @Config(application = io.agedm.tv.AgeTvApplication::class)
    fun versionOpensLibraryOnThirdPressAndPostersOpenPrivateDetail() {
        val app = RuntimeEnvironment.getApplication() as io.agedm.tv.AgeTvApplication
        app.hiddenLibrary.hide(listOf(AnimeCard(23, "测试动画", "")))
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val version = settings.get().findViewById<android.widget.Button>(R.id.versionButton)
        repeat(2) { version.performClick() }
        assertNull(shadowOf(settings.get()).nextStartedActivity)
        version.performClick()
        val intent = shadowOf(settings.get()).nextStartedActivity
        assertEquals(HiddenLibraryActivity::class.java.name, intent.component!!.className)
        val library = Robolectric.buildActivity(HiddenLibraryActivity::class.java, intent).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        val card = library.get().window.decorView.findViewWithTag<android.view.View>(MainActivity.animeFocusTag(23))
        assertNotNull(card)
        card.performClick()
        val detail = shadowOf(library.get()).nextStartedActivity
        assertEquals(DetailActivity::class.java.name, detail.component!!.className)
        assertTrue(detail.getBooleanExtra(DetailActivity.EXTRA_FROM_LIBRARY, false))
        library.pause().stop().destroy()
        settings.pause().stop().destroy()
    }

    @Test fun hidingAndRestoringPreservesTheSamePlaybackRecord() {
        val context = RuntimeEnvironment.getApplication()
        val library = HiddenLibrary(context)
        val playback = PlaybackStore(context)
        val episode = PlaybackRecord(7, "动画", "", "a", "线路", 2, "第3集", 51000, 90000, 123456)
        playback.saveRecord(episode)
        val cards = listOf(AnimeCard(7, "动画", "cover", badge = "在看"), AnimeCard(8, "另一部", ""))
        library.hide(cards)
        val reopened = HiddenLibrary(context)
        assertEquals(setOf(7L,8L), reopened.cards().map { it.animeId }.toSet())
        assertTrue(reopened.cards().all { it.badge.isEmpty() })
        playback.saveRecord(episode.copy(positionMs = 60000))
        reopened.restore(7)
        assertFalse(library.contains(7))
        assertTrue(library.contains(8))
        assertEquals(60000L, PlaybackStore(context).getRecord(7)!!.positionMs)
    }

    @Test
    @Config(application = io.agedm.tv.AgeTvApplication::class, qualifiers = "w960dp-h540dp-land-television-mdpi")
    fun remoteKeyboardFindsAndHidesSeriesWithoutBangumiLogin() {
        val app = RuntimeEnvironment.getApplication() as io.agedm.tv.AgeTvApplication
        app.contentCache.put("home", """{"latest":[],"recommend":[],"week_list":{}}""")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val panel = activity.findViewById<android.widget.LinearLayout>(R.id.initialsPanel)
            assertEquals(0, activity.findViewById<android.widget.LinearLayout>(R.id.downloadsContent).childCount)
            val initialChildren = panel.childCount
            activity.findViewById<android.widget.Button>(R.id.navInitialsButton).performClick()
            assertEquals(initialChildren + 3, panel.childCount)
            fun press(text: String) {
                val views = ArrayList<android.view.View>()
                panel.findViewsWithText(views, text, android.view.View.FIND_VIEWS_WITH_TEXT)
                views.filterIsInstance<android.widget.Button>().single { it.text == text }.performClick()
            }
            "HYRZ".forEach { press(it.toString()) }
            val results = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.contentRecycler)
            val looper = shadowOf(Looper.getMainLooper())
            val deadline = System.nanoTime() + 12_000_000_000L
            while (results.childCount == 0 && System.nanoTime() < deadline) {
                Thread.sleep(15)
                looper.idleFor(java.time.Duration.ofMillis(32))
            }
            val poster = requireNotNull(results.getChildAt(0))
            val id = results.getChildViewHolder(poster).itemId
            assertTrue(poster.performLongClick())
            assertTrue(poster.performLongClick())
            looper.idle()
            val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(listOf("标记隐藏"), (0 until menu.listView.count).map { menu.listView.adapter.getItem(it) })
            menu.listView.performItemClick(menu.listView.getChildAt(0), 0, 0)
            looper.idleFor(java.time.Duration.ofMillis(32))
            assertTrue(app.hiddenLibrary.contains(id))
            assertNull(results.findViewHolderForItemId(id))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun continueHasVisibleDefaultFocusAndBothActionsWork() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_AgeTv)
        for (resume in listOf(true, false)) {
            var choice: Boolean? = null
            val dialog = showResumeDialog(activity, "第3集", "12:34") { choice = it }
            shadowOf(Looper.getMainLooper()).idle()
            val yes = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val no = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            assertTrue(yes.isFocused)
            assertNotEquals(yes.currentTextColor, no.currentTextColor)
            val action = if (resume) yes else no
            assertTrue(action.requestFocus())
            action.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(resume, choice)
        }
        controller.pause().stop().destroy()
    }
}
