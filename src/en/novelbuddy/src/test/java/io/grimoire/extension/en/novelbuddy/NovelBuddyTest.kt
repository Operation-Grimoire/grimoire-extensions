package io.grimoire.extension.en.novelbuddy

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class NovelBuddyTest {
    private val novel = Novel("/fixture-book", "Browse title", Language.EN)
    private val title = """{"id":"fixture-id","slug":"fixture-book","name":"Fixture Book","cv":123,"authors":[{"name":"Writer"}],"genres":[{"name":"Fantasy"}],"summary":"<p>A <b>story</b>.</p>","status":"Completed","rating":4.5}"""
    private val failedPage = page("null", "\"The request took too long to load. Please try again.\"")
    private fun page(manga: String, error: String = "null") = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialManga":$manga,"initialError":$error}}}</script>"""
    private fun source(requests: MutableList<String>, respond: (String) -> Pair<Int, String>) = object : NovelBuddy() {
        override val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath + (chain.request().url.encodedQuery?.let { "?$it" } ?: "")
            requests += chain.request().url.host + path
            val (status, body) = respond(path)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build()
    }

    @Test fun `valid SSR details do not request the fallback`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = source(requests) { path -> assertEquals("/fixture-book", path); 200 to page(title) }
        val actual = source.getNovelDetails(novel)
        assertEquals("Fixture Book", actual.title); assertEquals("Writer", actual.author)
        assertEquals(NovelStatus.COMPLETED, actual.status); assertTrue(actual.initialized)
        assertEquals(listOf("novelbuddy.me/fixture-book"), requests)
    }

    @Test fun `null SSR details recover through the site details API`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = source(requests) { path -> when(path) {
            "/fixture-book" -> 200 to failedPage
            "/titles/by-slug/fixture-book?include=details" -> 200 to """{"success":true,"data":{"title":$title}}"""
            else -> error("Unexpected request $path")
        } }
        val actual = source.getNovelDetails(novel)
        assertEquals("Fixture Book", actual.title); assertEquals("https://novelbuddy.me/fixture-book", actual.url)
        assertEquals(listOf("Fantasy"), actual.genres); assertEquals(4.5f, actual.rating)
        assertTrue(actual.description!!.contains("story")); assertTrue(actual.initialized)
        assertEquals(listOf("novelbuddy.me/fixture-book", "api.novelbuddy.me/titles/by-slug/fixture-book?include=details"), requests)
    }

    @Test fun `partial SSR details recover instead of returning unusable metadata`() = runBlocking {
        for (initial in listOf("""{"id":"partial"}""", """{"id":"","name":"Partial"}""")) {
            val requests = mutableListOf<String>()
            val source = source(requests) { path -> if (path=="/fixture-book") 200 to page(initial) else 200 to """{"success":true,"data":{"title":$title}}""" }
            assertEquals("Fixture Book", source.getNovelDetails(novel).title)
            assertEquals(2, requests.size)
        }
    }

    @Test fun `chapter loading uses recovered identity and preserves API paths and order`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = source(requests) { path -> when(path) {
            "/fixture-book" -> 200 to failedPage
            "/titles/by-slug/fixture-book?include=details" -> 200 to """{"success":true,"data":{"title":$title}}"""
            "/titles/fixture-id/chapters?cv=123" -> 200 to """{"data":{"chapters":[{"name":"Second","url":"/fixture-book/chapter-2-full-title","number":2,"updated_at":"2026-01-02T00:00:00Z"},{"name":"First","url":"/fixture-book/chapter-1-full-title","number":1,"updated_at":"2026-01-01T00:00:00Z"}]}}"""
            else -> error("Unexpected request $path")
        } }
        val chapters = source.getChapterList(novel)
        assertEquals(listOf("First", "Second"), chapters.map { it.name })
        assertEquals("/fixture-book/chapter-1-full-title", chapters[0].url)
        assertEquals(1f, chapters[0].chapterNumber); assertEquals(1767225600000L, chapters[0].uploadDate)
        assertEquals(3, requests.size)
    }

    @Test fun `unavailable fallback fails both entry points without inventing data or retrying`() {
        for (response in listOf("""{"success":false,"message":"Temporarily unavailable","data":null}""", """{"success":true,"data":{"title":null}}""", """{"success":true,"data":{"title":{"id":"partial"}}}""")) {
            for (chapters in listOf(false, true)) {
                val requests = mutableListOf<String>()
                val source = source(requests) { path -> if (path=="/fixture-book") 200 to failedPage else 200 to response }
                val error = assertThrows(IOException::class.java) { runBlocking { if (chapters) source.getChapterList(novel) else source.getNovelDetails(novel) } }
                assertTrue(error.message!!.contains("NovelBuddy")); assertEquals(2, requests.size)
            }
        }
    }

    @Test fun `HTTP errors cannot be accepted as successful title payloads`() {
        val requests = mutableListOf<String>()
        val source = source(requests) { path -> if (path=="/fixture-book") 200 to failedPage else 503 to """{"success":true,"data":{"title":$title}}""" }
        val error = assertThrows(IOException::class.java) { runBlocking { source.getNovelDetails(novel) } }
        assertTrue(error.message!!.contains("503")); assertEquals(2, requests.size)
    }

    @Test fun `failed chapter payloads cannot masquerade as an empty chapter list`() {
        val requests = mutableListOf<String>()
        val source = source(requests) { path -> if (path=="/fixture-book") 200 to page(title) else 200 to """{"success":false,"data":null}""" }
        val error = assertThrows(IOException::class.java) { runBlocking { source.getChapterList(novel) } }
        assertTrue(error.message!!.contains("NovelBuddy")); assertEquals(2, requests.size)
    }

    @Test fun `malformed fallback JSON reports the source failure with its cause`() {
        val requests = mutableListOf<String>()
        val source = source(requests) { path -> if (path=="/fixture-book") 200 to failedPage else 200 to "<html>Unavailable</html>" }
        val error = assertThrows(IOException::class.java) { runBlocking { source.getNovelDetails(novel) } }
        assertTrue(error.message!!.contains("NovelBuddy")); assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is JSONException })
        assertEquals(2, requests.size)
    }

    @Test fun `missing Next data fails explicitly rather than parsing unrelated HTML`() {
        val requests = mutableListOf<String>()
        val source = source(requests) { 200 to "<html><title>Upstream unavailable</title></html>" }
        val error = assertThrows(IOException::class.java) { runBlocking { source.getNovelDetails(novel) } }
        assertTrue(error.message!!.contains("NovelBuddy")); assertEquals(1, requests.size)
    }

    @Test fun `malformed SSR JSON reports source context and retains the parser cause`() {
        val requests = mutableListOf<String>()
        val source = source(requests) { 200 to """<script id="__NEXT_DATA__" type="application/json">{invalid</script>""" }
        val error = assertThrows(IOException::class.java) { runBlocking { source.getNovelDetails(novel) } }
        assertTrue(error.message!!.contains("NovelBuddy"))
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is JSONException })
        assertEquals(1, requests.size)
    }
}
