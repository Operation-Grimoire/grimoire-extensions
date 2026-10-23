package io.grimoire.extension.ar.azorafly

import io.grimoire.api.model.novel.NovelStatus
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AzoraFlyTest {
    private val source = AzoraFly()

    @Test
    fun `cold details use current page status rather than catalog cache`() {
        val doc = Jsoup.parse("""<section itemprop="itemReviewed"><span>COMPLETED</span></section>""")
        assertEquals(NovelStatus.COMPLETED, source.status(doc))
        assertEquals(NovelStatus.COMPLETED, source.status(doc, JSONObject().put("seriesStatus", "ONGOING")))
    }

    @Test
    fun `Android JSON nulls preserve cover and chapter title fallbacks`() {
        val record = object : JSONObject("""{"id":1,"slug":"book","postTitle":"Book","isNovel":true,"seriesType":"NOVEL","featuredImageCL":null,"featuredImage":"https://azorafly.com/cover.png"}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        val novel = source.parseLibrary(JSONObject().put("novelPosts", JSONArray().put(record))).single()
        assertEquals("https://azorafly.com/cover.png", novel.thumbnailUrl)
        val row = object : JSONObject("""{"slug":"chapter-1","number":1,"title":null}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        assertEquals("الفصل 1", source.parseChapters(JSONArray().put(row), novel.url).single().name)
    }

    @Test
    fun `library reads novelPosts and rejects mixed media`() {
        val novels = source.parseLibrary(JSONObject("""{
            "posts":[{"id":90,"slug":"manga","postTitle":"Wrong"}],
            "novelPosts":[
              {"id":1,"slug":"book","postTitle":"Book","isNovel":true,"seriesType":"NOVEL"},
              {"id":2,"slug":"comic","postTitle":"Comic","isNovel":false,"seriesType":"MANHWA"}
            ]
        }"""))
        assertEquals(listOf("https://azorafly.com/series/book"), novels.map { it.url })
    }

    @Test
    fun `chapter flags preserve restrictions and decimal numbers`() {
        val chapters = source.parseChapters(JSONArray("""[
            {"slug":"chapter-2","number":2,"isAccessible":false},
            {"slug":"chapter-1","number":1,"isLocked":false,"isAccessible":true},
            {"slug":"chapter-1-5","number":1.5,"isPermanentlyLocked":true},
            {"slug":"chapter-3","number":3,"isShortLinkLocked":true}
        ]"""), "https://azorafly.com/series/book")
        assertEquals(listOf(1f, 1.5f, 2f, 3f), chapters.map { it.chapterNumber })
        assertFalse(chapters[0].locked)
        assertTrue(chapters.drop(1).all { it.locked })
    }

    @Test
    fun `detail identity matches route post rather than recommendations`() {
        val doc = Jsoup.parse("""<script>post:${'$'}R[89]={id:2283,slug:"book",postTitle:"Title"};post:${'$'}R[90]={id:9,slug:"other"}</script>""")
        assertEquals(2283L, source.postId(doc, "https://azorafly.com/series/book"))
    }

    @Test
    fun `ten point rating maps to the reader five point scale`() {
        val doc = Jsoup.parse("""<meta itemprop="ratingValue" content="9.5"><meta itemprop="bestRating" content="10">""")
        assertEquals(4.75f, source.rating(doc))
    }
}
