package io.grimoire.extension.ar.seanovel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SeanovelTest {
    @Test
    fun `Android JSON nulls retain original title and omit absent author`() {
        val data = object : JSONObject("""{"slug":"book","title_ar":null,"title_original":"Original","author":null,"cover_version":null}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        val novel = Seanovel().novel(data)
        assertEquals("Original", novel.title)
        assertNull(novel.author)
        assertTrue(!novel.thumbnailUrl!!.contains("null"))
        val chapter = object : JSONObject("""{"id":7,"title":null}""") {
            override fun optString(key: String?): String = if (isNull(key)) "null" else super.optString(key)
        }
        assertEquals("الفصل 7", Seanovel().parseChapters(JSONArray().put(chapter), novel.url).single().name)
    }

    @Test
    fun `chapter routes retain ids while display numbers can be fractional`() {
        val chapter = Seanovel().parseChapters(JSONArray("""[
            {"id":7000,"title":"الفصل 1.5","date":"2026-10-01T12:00:00Z"}
        ]"""), "https://seanovel.org/novels/book").single()
        assertEquals("https://seanovel.org/novels/book/chapters/7000", chapter.url)
        assertEquals(1.5f, chapter.chapterNumber)
        assertTrue(chapter.uploadDate > 0)
    }
}
