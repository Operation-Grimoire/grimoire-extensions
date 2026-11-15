package io.grimoire.extension.ar.novltime

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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NovlTimeTest {
    private val book = Novel("https://novltime.com/ar/novel/book", "Book", Language.AR)

    private fun source(code: (Request) -> Int = { 200 }, response: (Request) -> String): NovlTime = object : NovlTime() {
        override val retryDelayMs = 0L
        override val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(code(chain.request())).message("test").protocol(Protocol.HTTP_1_1)
                .body(response(chain.request()).toResponseBody()).build()
        }.build()
    }

    private fun record(slug: String) = JSONObject().put("id", "id-$slug").put("slug", slug).put("title", "عنوان $slug")

    private fun catalog(vararg novels: JSONObject, next: String? = null) = JSONObject()
        .put("success", true).put("data", JSONArray(novels))
        .put("meta", JSONObject().put("hasMore", next != null).put("nextCursor", next))

    @Test
    fun `popular search and recent updates use their own public feeds`() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val source = source { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v1/novels" -> if (request.url.queryParameter("cursor") == null) {
                    catalog(record("one"), record("two"), next = "next")
                } else {
                    catalog(record("three"))
                }.toString()
                "/api/v1/novels/latest-updates" -> JSONArray().put(record("recent")).let {
                    JSONObject().put("success", true).put("data", it).toString()
                }
                "/api/v1/search" -> JSONObject().put("success", true).put("data", JSONObject()
                    .put("items", JSONArray().put(record("result"))).put("hasMore", false)).toString()
                else -> error("Unexpected request: ${request.url}")
            }
        }
        assertEquals(listOf("one", "two"), source.getPopularNovels(1).map { it.url.substringAfterLast('/') })
        assertEquals(listOf("three"), source.getPopularNovels(2).map { it.url.substringAfterLast('/') })
        assertTrue(source.getPopularNovels(3).isEmpty())
        assertEquals(listOf("recent"), source.getLatestUpdates(1).map { it.url.substringAfterLast('/') })
        assertTrue(source.getLatestUpdates(2).isEmpty())
        assertEquals(listOf("result"), source.searchNovels(" القس ", 1, emptyList()).map { it.url.substringAfterLast('/') })
        assertEquals("next", requests[1].url.queryParameter("cursor"))
        assertEquals("القس", requests.last().url.queryParameter("q"))
        assertEquals("ar", requests.last().url.queryParameter("languages"))
        assertEquals(listOf("1", null, "1"), listOf(requests[0], requests[1], requests[3]).map { it.url.queryParameter("page") })
        assertTrue(requests.all { it.header("User-Agent")?.contains("Windows NT 10.0") == true })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source.getPopularNovels(0) } }
    }

    @Test
    fun `a direct later catalog page reconstructs its cursor chain and then reuses it`() = runBlocking {
        val cursors = mutableListOf<String?>()
        val source = source { request ->
            cursors += request.url.queryParameter("cursor")
            when (request.url.queryParameter("cursor")) {
                null -> catalog(record("one"), next = "a")
                "a" -> catalog(record("two"), next = "b")
                else -> catalog(record("three"))
            }.toString()
        }
        assertEquals(listOf("three"), source.getPopularNovels(3).map { it.url.substringAfterLast('/') })
        assertEquals(listOf(null, "a", "b"), cursors)
        source.getPopularNovels(3)
        assertEquals(listOf(null, "a", "b", "b"), cursors)
    }

    @Test
    fun `details retain Arabic metadata full synopsis author cover and rating`() = runBlocking {
        val record = record("book").put("coverURL", "/uploads/cover.webp")
            .put("descriptionShort", "مختصر").put("descriptionLong", "الفقرة الأولى\n\nالفقرة الثانية")
            .put("genres", JSONArray(listOf("action", "unknown")))
            .put("completionStatus", "PAUSED")
            .put("stats", JSONObject().put("avgRating", 4.5).put("ratingCount", 12))
        val source = source { request ->
            assertEquals("/api/v1/novels/book/view", request.url.encodedPath)
            JSONObject().put("success", true).put("data", JSONObject().put("novel", record)
                .put("author", JSONObject().put("displayName", "طارق منصور"))).toString()
        }
        val result = source.getNovelDetails(book)
        assertEquals("طارق منصور", result.author)
        assertEquals("الفقرة الأولى\n\nالفقرة الثانية", result.description)
        assertEquals("https://novltime.com/uploads/cover.webp", result.thumbnailUrl)
        assertEquals(listOf("أكشن", "unknown"), result.genres)
        assertEquals(NovelStatus.HIATUS, result.status)
        assertEquals(4.5f, result.rating)
        assertEquals(12, result.ratingCount)
        assertTrue(result.initialized)
        assertNull(NovlTime().novel(record.put("stats", JSONObject())).rating)
    }

    @Test
    fun `chapter retrieval follows cursors and preserves numbers dates and locks`() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = source { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/v1/novels/book" -> JSONObject().put("success", true).put("data", record("book")).toString()
                "/api/v1/novels/id-book/chapters/published" -> {
                    val entries = if (request.url.queryParameter("cursor") == null) {
                        JSONArray().put(JSONObject().put("slug", "chapter-1.5").put("order", 1.5).put("title", "البداية")
                            .put("publishedAt", "2026-11-06T12:00:00Z"))
                    } else {
                        JSONArray().put(JSONObject().put("slug", "chapter-2").put("order", 2).put("isPremium", true))
                    }
                    JSONObject().put("success", true).put("data", entries).put("meta", JSONObject()
                        .put("hasMore", request.url.queryParameter("cursor") == null)
                        .put("nextCursor", if (request.url.queryParameter("cursor") == null) "next" else JSONObject.NULL)).toString()
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val first = source.getChapterList(book, null)
        assertEquals("next", first.nextCursor)
        val second = source.getChapterList(book, first.nextCursor)
        val chapters = first.chapters + second.chapters
        assertEquals(listOf(1.5f, 2f), chapters.map { it.chapterNumber })
        assertEquals("https://novltime.com/ar/novel/book/chapter/chapter-1.5", chapters.first().url)
        assertEquals(Instant.parse("2026-11-06T12:00:00Z").toEpochMilli(), chapters.first().uploadDate)
        assertTrue(chapters.last().locked)
        assertEquals(null, second.nextCursor)
        assertEquals(1, requests.count { it.url.encodedPath == "/api/v1/novels/book" })
        assertEquals(listOf(null, "next"), requests.drop(1).map { it.url.queryParameter("cursor") })
        assertEquals(listOf("1", null), requests.drop(1).map { it.url.queryParameter("page") })
        assertTrue(requests.drop(1).all { it.url.queryParameter("limit") == "200" && it.url.queryParameter("sort") == "asc" })
    }

    @Test
    fun `a missing chapter continuation fails instead of truncating the list`() {
        val source = source { request ->
            if (request.url.encodedPath.endsWith("/published")) {
                JSONObject().put("success", true).put("data", JSONArray().put(JSONObject().put("slug", "chapter-1").put("order", 1)))
                    .put("meta", JSONObject().put("hasMore", true)).toString()
            } else {
                JSONObject().put("success", true).put("data", record("book")).toString()
            }
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { source.getChapterList(book, null) } }
    }

    @Test
    fun `chapter HTML retains rich prose and images but excludes controls and scripts`() {
        val document = Jsoup.parse("""<nav>التالي</nav><div class="chapter-body">
            <p>الأول <b>مهم</b></p><p><img src="/uploads/image.webp"></p>
            <p><script>tracking()</script></p><button>إعدادات</button></div>""", book.url)
        val pages = NovlTime().parsePages(document)
        assertEquals(2, pages.size)
        val text = pages.first().content as PageContent.Text
        assertEquals("الأول مهم", text.text)
        assertEquals("الأول <b>مهم</b>", text.html)
        assertEquals("https://novltime.com/uploads/image.webp", (pages.last().content as PageContent.Image).url)
        assertEquals(listOf(0, 1), pages.map { it.index })
    }

    @Test
    fun `top-level images remain in chapter order`() {
        val document = Jsoup.parse("""<div class="chapter-body">
            <img src="/first.webp"><p>النص</p><img src="/last.webp"></div>""", book.url)
        val pages = NovlTime().parsePages(document)
        assertEquals(listOf("https://novltime.com/first.webp", "النص", "https://novltime.com/last.webp"), pages.map {
            when (val content = it.content) {
                is PageContent.Image -> content.url
                is PageContent.Text -> content.text
                else -> error("unexpected content")
            }
        })
    }

    @Test
    fun `inline images retain DOM order and formatting on both sides`() {
        val document = Jsoup.parse("""<div class="chapter-body"><p>
            قبل <b>نص قبل<img src="/inline.webp">نص بعد</b> بعد
            </p></div>""", book.url)
        val pages = NovlTime().parsePages(document)
        assertEquals(3, pages.size)
        val before = pages[0].content as PageContent.Text
        val after = pages[2].content as PageContent.Text
        assertEquals("قبل نص قبل", before.text)
        assertEquals("قبل <b>نص قبل</b>", before.html)
        assertEquals("https://novltime.com/inline.webp", (pages[1].content as PageContent.Image).url)
        assertEquals("نص بعد بعد", after.text)
        assertEquals("<b>نص بعد</b> بعد", after.html)
    }

    @Test
    fun `transient gateway failures are retried with a bounded attempt count`() = runBlocking {
        var calls = 0
        val source = source(code = { if (calls++ < 2) 504 else 200 }) { catalog(record("book")).toString() }
        assertEquals(listOf("book"), source.getPopularNovels(1).map { it.url.substringAfterLast('/') })
        assertEquals(3, calls)
        var persistentCalls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking { source(code = { persistentCalls++; 503 }) { "maintenance" }.getPopularNovels(1) }
        }
        assertEquals(5, persistentCalls)
    }

    @Test
    fun `missing chapter content and HTTP errors are surfaced`() {
        assertThrows(IllegalStateException::class.java) { NovlTime().parsePages(Jsoup.parse("<h1>Not found</h1>")) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { source(code = { 400 }) { "bad request" }.getPopularNovels(1) }
        }
    }
}
