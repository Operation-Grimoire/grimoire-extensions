package io.grimoire.extension.ar.mtlarabic

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MTLArabicTest {
    @Test
    fun `Android JSON nulls do not create bogus metadata or chapter names`() {
        val data = object : JSONObject("""{"slug":"book","name":"Book","image":null,"description":null,"type":null}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        val source = MTLArabic()
        val novel = source.novel(data)
        assertNull(novel.thumbnailUrl)
        assertNull(novel.description)
        assertTrue(novel.genres.isEmpty())
        val chapter = object : JSONObject("""{"number":1,"title":null}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        assertEquals("الفصل 1", source.parseChapters(JSONArray().put(chapter), novel.url).single().name)
    }

    @Test
    fun `blank chapter paragraphs fail instead of returning zero pages`() {
        assertThrows(IllegalStateException::class.java) {
            MTLArabic().parsePages(Jsoup.parse("<div id=chapterText><p> </p><p>&nbsp;</p></div>"))
        }
    }

    @Test
    fun `incomplete reader TOC is attempted once before paged fallback`() = runBlocking {
        var readerRequests = 0
        val source = object : MTLArabic() {
            override val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url
                val body = if (url.encodedPath.startsWith("/api/")) {
                    val page = url.queryParameter("page")!!.toInt()
                    val chapters = JSONArray().apply {
                        for (number in ((page - 1) * 100 + 1)..(page * 100)) put(JSONObject().put("number", number))
                    }
                    JSONObject().put("chapters", chapters).put("pagination", JSONObject().put("totalPages", 3)).toString()
                } else {
                    if (url.encodedPath == "/book/1") readerRequests++
                    """<script id="__NOVEL__">{"id":1,"slug":"book","name":"Book","totalChapters":201,"chapters":[{"number":1}]}</script>"""
                }
                Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                    .body(body.toResponseBody()).build()
            }.build()
        }
        val novel = Novel("https://mtlarabic.com/book", "Book", Language.AR)
        assertEquals(100, source.getChapterList(novel, 1).size)
        assertEquals(100, source.getChapterList(novel, 2).size)
        assertEquals(1, readerRequests)
    }

    @Test
    fun `refreshing novel details invalidates the embedded chapter cache`() = runBlocking {
        var count = 2
        var requests = 0
        val source = object : MTLArabic() {
            override val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val chapters = JSONArray().apply {
                    for (number in 1..count) put(JSONObject().put("number", number).put("title", "Chapter $number"))
                }
                val data = JSONObject().put("id", 1).put("slug", "book").put("name", "Book")
                    .put("chapters", chapters).put("totalChapters", count)
                Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                    .body("<script id=\"__NOVEL__\">$data</script>".toResponseBody()).build()
            }.build()
        }
        val novel = Novel("https://mtlarabic.com/book", "Book", Language.AR)
        assertEquals(2, source.getChapterList(novel, 1).size)
        count = 3
        source.getNovelDetails(novel)
        assertEquals(3, source.getChapterList(novel, 1).size)
        assertEquals(2, requests)
    }

    @Test
    fun `special chapters retain their own identity and server order`() {
        val chapters = MTLArabic().parseChapters(JSONArray("""[
            {"number":1,"title":"First","approvalDate":"2026-10-01T00:00:00Z"},
            {"number":1,"isSpecial":true,"specialId":91,"specialPosition":1,"title":"Extra"},
            {"number":2,"title":"Second"}
        ]"""), "https://mtlarabic.com/example")
        assertEquals(listOf("https://mtlarabic.com/example/1", "https://mtlarabic.com/example/special-91", "https://mtlarabic.com/example/2"), chapters.map { it.url })
        assertEquals(-1f, chapters[1].chapterNumber)
        assertTrue(chapters[0].uploadDate > 0)
        assertEquals(0L, chapters[2].uploadDate)
    }
}
