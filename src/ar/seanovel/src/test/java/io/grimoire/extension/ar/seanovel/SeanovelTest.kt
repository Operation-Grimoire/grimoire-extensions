package io.grimoire.extension.ar.seanovel

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeanovelTest {
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
