package io.grimoire.extension.ar.riwayatarab

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RiwayatarabTest {
    private val book = Novel("https://riwayatarab.com/novel/shadow-slave", "Book", Language.AR)

    private fun source(code: Int = 200, response: (Request) -> String): Riwayatarab = object : Riwayatarab() {
        override val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(code).message("test").protocol(Protocol.HTTP_1_1)
                .body(response(chain.request()).toResponseBody()).build()
        }.build()
    }

    private fun record(slug: String) = JSONObject().put("slug", slug).put("title", "عنوان الرواية")

    private fun chapters(page: Int, total: Int, numbers: List<Int>): JSONObject = JSONObject()
        .put("pagination", JSONObject().put("currentPage", page).put("totalCount", total).put("totalPages", (total + 49) / 50))
        .put("chapters", JSONArray().apply {
            numbers.forEach { number -> put(JSONObject().put("chapter_number", number).put("title", "الفصل $number")) }
        })

    @Test
    fun `popular latest and Arabic search use the API and preserve pagination and distinct URLs`() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val source = source { request ->
            requests += request
            val novels = if (request.url.queryParameter("page") == "3") JSONArray() else
                JSONArray().put(record("book-1")).put(record("book-2"))
            JSONObject().put("novels", novels).toString()
        }
        assertEquals(2, source.getPopularNovels(1).size)
        assertEquals(2, source.getPopularNovels(2).size)
        assertTrue(source.getPopularNovels(3).isEmpty())
        source.getLatestUpdates(1)
        source.searchNovels(" عبد الظل ", 2, emptyList())
        assertTrue(requests.all { it.url.host == "api.riwayatarab.com" && it.url.encodedPath == "/api/novels" })
        assertTrue(requests.all { it.url.queryParameter("limit") == "24" })
        assertEquals(listOf("1", "2", "3", "1", "2"), requests.map { it.url.queryParameter("page") })
        assertEquals(listOf("views", "views", "views", "updated_at", null), requests.map { it.url.queryParameter("sort") })
        assertEquals("عبد الظل", requests.last().url.queryParameter("search"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source.getPopularNovels(0) } }
    }

    @Test
    fun `Arabic slugs are encoded once and encoded slugs remain usable for direct details and chapters`() = runBlocking<Unit> {
        val source = Riwayatarab()
        val raw = source.novel(record("عبد-الظل"))
        val encoded = source.novel(record("%D8%B9%D8%A8%D8%AF-%D8%A7%D9%84%D8%B8%D9%84"))
        assertEquals(encoded.url, raw.url)
        assertFalse(raw.url.contains("%25"))
        val direct = source { request ->
            assertEquals("/api/novels/%D8%B9%D8%A8%D8%AF-%D8%A7%D9%84%D8%B8%D9%84", request.url.encodedPath)
            record("عبد-الظل").toString()
        }.getNovelDetails(raw)
        assertEquals(raw.url, direct.url)
        assertTrue(direct.initialized)
        source { request ->
            assertEquals("/api/novels/%D8%B9%D8%A8%D8%AF-%D8%A7%D9%84%D8%B8%D9%84/chapters", request.url.encodedPath)
            chapters(1, 1, listOf(1)).toString()
        }.getChapterList(raw, 1)
    }

    @Test
    fun `details retain metadata and omit unknown authors null covers and unrated values`() {
        val record = record("book").put("author", "غير محدد").put("cover_image", JSONObject.NULL)
            .put("status", "hiatus").put("rating", "0.00").put("category_name", "خيال")
            .put("tags", JSONArray(listOf("خيال", "سحر"))).put("description", "<p>أول</p><p><b>ثان</b></p>")
        val novel = Riwayatarab().novel(record)
        assertEquals(NovelStatus.HIATUS, novel.status)
        assertNull(novel.author)
        assertNull(novel.thumbnailUrl)
        assertNull(novel.rating)
        assertEquals(listOf("خيال", "سحر"), novel.genres)
        assertEquals("أول<br><br><b>ثان</b>", novel.description)
        record.put("author", "كاتب").put("status", "completed").put("rating", "4.50")
        assertEquals("كاتب", Riwayatarab().novel(record).author)
        assertEquals(NovelStatus.COMPLETED, Riwayatarab().novel(record).status)
        assertEquals(4.5f, Riwayatarab().novel(record).rating)
    }

    @Test
    fun `chapter pages reach the final partial page and terminate without a stale count`() = runBlocking {
        val source = source { request ->
            assertEquals("50", request.url.queryParameter("limit"))
            val page = request.url.queryParameter("page")!!.toInt()
            when (page) {
                1 -> chapters(1, 52, (1..50).toList())
                2 -> chapters(2, 52, listOf(51, 52))
                else -> chapters(page, 0, emptyList())
            }.toString()
        }
        assertEquals(2, source.getPageCount(book))
        val all = source.getChapterList(book, 1) + source.getChapterList(book, 2)
        assertEquals(52, all.size)
        assertEquals(52, all.map { it.url }.distinct().size)
        assertEquals("${book.url}/chapter/52", all.last().url)
        assertTrue(source.getChapterList(book, 3).isEmpty())
        var calls = 0
        val growing = source { chapters(1, if (calls++ == 0) 51 else 101, (1..50).toList()).toString() }
        assertEquals(2, growing.getPageCount(book))
        assertEquals(3, growing.getPageCount(book))
    }

    @Test
    fun `incomplete repeated or duplicate chapter pages fail instead of truncating the book`() {
        val source = Riwayatarab()
        assertThrows(IllegalStateException::class.java) { source.parseChapters(chapters(1, 52, listOf(1)), book.url, 1) }
        assertThrows(IllegalStateException::class.java) { source.parseChapters(chapters(1, 1, listOf(1)), book.url, 2) }
        assertThrows(IllegalStateException::class.java) { source.parseChapters(chapters(1, 2, listOf(1, 1)), book.url, 1) }
    }

    @Test
    fun `chapter routes use the numeric identity and retain fractional numbers dates and fallback titles`() {
        val data = chapters(1, 1, listOf(1))
        data.getJSONArray("chapters").getJSONObject(0).put("chapter_number", 1.5).put("slug", "unrelated-slug")
            .put("title", JSONObject.NULL).put("created_at", "2026-11-06T12:00:00Z")
        val chapter = Riwayatarab().parseChapters(data, book.url, 1).single()
        assertEquals("${book.url}/chapter/1.5", chapter.url)
        assertEquals("الفصل 1.5", chapter.name)
        assertEquals(1.5f, chapter.chapterNumber)
        assertEquals(Instant.parse("2026-11-06T12:00:00Z").toEpochMilli(), chapter.uploadDate)
    }

    @Test
    fun `br separated prose preserves Arabic lines and formatting spanning a break without navigation or scripts`() {
        // Same structure as the live /novel/shadow-slave/chapter/1 response.
        val doc = Jsoup.parse("""<nav>التالي</nav><div class="chapter-content"><div>
            السطر الأول<br><br><br><b>نص مهم<br>متصل</b><br>النهاية &amp; خاتمة
            <script>tracking()</script><style>.ad{}</style><ins>إعلان</ins>
            </div></div><footer>قائمة الفصول</footer>""")
        val pages = Riwayatarab().parsePages(doc)
        val content = pages.single().content as PageContent.Text
        assertEquals(0, pages.single().index)
        assertEquals("السطر الأول\n\nنص مهم\nمتصل\nالنهاية & خاتمة", content.text)
        assertTrue(content.html!!.contains("<b>نص مهم<br>متصل</b>"))
        assertFalse(content.html!!.contains("tracking"))
        assertFalse(content.text.contains("التالي"))
    }

    @Test
    fun `paragraph markup retains block boundaries and inline links`() {
        val doc = Jsoup.parse("""<div class="chapter-content"><p>الأول <i>مائل</i></p><p><a href="/novel/book">الثاني</a></p></div>""", book.url)
        val content = Riwayatarab().parsePages(doc).single().content as PageContent.Text
        assertEquals("الأول مائل\n\nالثاني", content.text)
        assertTrue(content.html!!.contains("<i>مائل</i>"))
        assertTrue(content.html!!.contains("href=\"https://riwayatarab.com/novel/book\""))
    }

    @Test
    fun `missing or empty chapter content is an error`() {
        for (html in listOf("<h1>Not found</h1>", "<div class=chapter-content><br><script>tracking()</script></div>")) {
            assertThrows(IllegalStateException::class.java) { Riwayatarab().parsePages(Jsoup.parse(html)) }
        }
    }

    @Test
    fun `HTTP errors are surfaced instead of becoming empty listings`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { source(503) { "maintenance" }.getPopularNovels(1) }
        }
    }
}
