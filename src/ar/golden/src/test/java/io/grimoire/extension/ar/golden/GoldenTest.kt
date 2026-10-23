package io.grimoire.extension.ar.golden

import io.grimoire.api.model.novel.PageContent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GoldenTest {
    private val source = Golden()

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
