package io.grimoire.extension.ar.mtlarabic

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MTLArabicTest {
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
