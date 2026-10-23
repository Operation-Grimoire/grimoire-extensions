package io.grimoire.extension.ar.golden

import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.feature.PopularSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GoldenTest {
    private val source = Golden()

    @Test
    fun `a mixed-media featured selection is not advertised as novel popularity`() {
        assertFalse(PopularSource::class.java.isAssignableFrom(Golden::class.java))
    }

    @Test
    fun `latest continues through manga-only and duplicate-only release pages`() = runBlocking {
        val requested = mutableListOf<Int>()
        val paged = releaseSource(mapOf(
            1 to listOf(1 to true, 2 to false),
            2 to listOf(2 to false),
            3 to listOf(1 to true),
            4 to listOf(3 to true),
        ), requested)
        assertEquals(listOf("Book 1", "Book 3"), paged.getLatestUpdates(1).map { it.title })
        assertTrue(paged.getLatestUpdates(2).isEmpty())
        assertTrue(paged.getLatestUpdates(3).isEmpty())
        assertEquals(listOf(1, 2, 3, 4), requested)
    }

    @Test
    fun `latest packs distinct novels into stable pages and reuses fetched releases`() = runBlocking {
        val requested = mutableListOf<Int>()
        val paged = releaseSource(mapOf(
            1 to (1..20).map { it to true },
            2 to (15..35).map { it to true },
        ), requested)
        val first = paged.getLatestUpdates(1)
        val second = paged.getLatestUpdates(2)
        assertEquals((1..12).map { "Book $it" }, first.map { it.title })
        assertEquals((13..24).map { "Book $it" }, second.map { it.title })
        assertEquals(first, paged.getLatestUpdates(1))
        assertEquals(listOf(1, 2), requested)
    }

    @Test
    fun `a sparse release scan is bounded and retry continues without false EOF`() {
        val requested = mutableListOf<Int>()
        val pages = (1..20).associateWith { emptyList<Pair<Int, Boolean>>() }.toMutableMap()
        pages[21] = listOf(77 to true)
        val paged = releaseSource(pages, requested)
        assertThrows(IllegalStateException::class.java) { runBlocking { paged.getLatestUpdates(1) } }
        assertEquals(20, requested.size)
        assertEquals(listOf("Book 77"), runBlocking { paged.getLatestUpdates(1) }.map { it.title })
        assertEquals((1..21).toList(), requested)
    }

    @Test
    fun `a short sparse page continues to later novels instead of ending the feed`() = runBlocking {
        val requested = mutableListOf<Int>()
        val pages = (1..21).associateWith { emptyList<Pair<Int, Boolean>>() }.toMutableMap()
        pages[1] = listOf(1 to true)
        pages[21] = listOf(2 to true)
        val paged = releaseSource(pages, requested)
        assertEquals(listOf("Book 1"), paged.getLatestUpdates(1).map { it.title })
        assertEquals(listOf("Book 2"), paged.getLatestUpdates(2).map { it.title })
        assertTrue(paged.getLatestUpdates(3).isEmpty())
        assertEquals((1..21).toList(), requested)
    }

    @Test
    fun `a direct later-page request refreshes expired results from one new snapshot`() = runBlocking {
        var now = 0L
        val requested = mutableListOf<Int>()
        val pages = mutableMapOf(1 to (1..20).map { it to true }, 2 to (21..40).map { it to true })
        val paged = releaseSource(pages, requested) { now }
        assertEquals((13..24).map { "Book $it" }, paged.getLatestUpdates(2).map { it.title })
        pages[1] = (101..120).map { it to true }
        pages[2] = (121..140).map { it to true }
        now = 300_001
        assertEquals((113..124).map { "Book $it" }, paged.getLatestUpdates(2).map { it.title })
        assertEquals((101..112).map { "Book $it" }, paged.getLatestUpdates(1).map { it.title })
        assertEquals(listOf(1, 2, 1, 2), requested)
    }

    private fun releaseSource(pages: Map<Int, List<Pair<Int, Boolean>>>, requested: MutableList<Int>, clock: () -> Long = System::currentTimeMillis): Golden = object : Golden() {
        override fun nowMillis(): Long = clock()

        override val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("/api/releases", url.encodedPath)
            val page = url.queryParameter("page")!!.toInt()
            requested += page
            val entries = JSONArray()
            pages.getValue(page).forEach { (id, isNovel) ->
                entries.put(JSONObject().put("manga", JSONObject().put("id", id).put("title", "Book $id").put("is_novel", isNovel)))
            }
            val data = JSONObject().put("totalReleases", pages.size * 50).put("releases", entries)
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(data.toString().toResponseBody()).build()
        }.build()
    }

    @Test
    fun `Android JSON nulls do not become descriptions or chapter names`() {
        val data = object : JSONObject("""{"id":1,"title":"Book","is_novel":true,"summary":null}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        assertNull(source.novel(data).description)
        val chapter = object : JSONObject("""{"chapter":1,"title":null,"teams":[]}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        assertEquals("الفصل 1", source.parseChapters(JSONArray().put(chapter), "https://golden.rest/mangas/1/book").single().name)
    }

    @Test
    fun `homepage feeds exclude manga and deduplicate novel releases`() {
        val list = source.novelsInReleases(JSONArray("""[
          {"manga":{"id":1,"title":"Novel","is_novel":true}},
          {"manga":{"id":2,"title":"Comic","is_novel":false}},
          {"manga":{"id":1,"title":"Novel","is_novel":true}}
        ]"""))
        assertEquals(1, list.size)
        assertEquals("Novel", list.single().title)
    }

    @Test
    fun `alternative teams have distinct chapter urls and restrictions survive`() {
        val chapters = source.parseChapters(JSONArray("""[
          {"chapter":1,"teams":[{"name":"Team A"}],"link_control":0},
          {"chapter":1,"teams":[{"name":"Team B"}],"has_rev_link":true}
        ]"""), "https://golden.rest/mangas/1/book")
        assertEquals(2, chapters.map { it.url }.distinct().size)
        assertTrue(chapters[0].url.endsWith("/1/team-a"))
        assertTrue(chapters[1].locked)
    }

    @Test
    fun `novel content stays plaintext even with encoded flag`() {
        val data = JSONObject("""{"readerDataAction":{"readerData":{
          "manga":{"is_novel":true},"release":{"showable":true,"encoded":true,"content":"First\n\nSecond"}
        }}}""")
        assertEquals(listOf("First", "Second"), source.parsePages(data).map { (it.content as PageContent.Text).text })
        data.getJSONObject("readerDataAction").getJSONObject("readerData").getJSONObject("release").put("showable", false)
        assertThrows(IllegalStateException::class.java) { source.parsePages(data) }
    }
}
