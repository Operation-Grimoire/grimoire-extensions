package io.grimoire.extension.ar.seanovel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import io.grimoire.api.source.feature.PopularSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

class SeanovelTest {
    @Test
    fun `the hero carousel is not advertised as a popularity ranking`() {
        assertFalse(PopularSource::class.java.isAssignableFrom(Seanovel::class.java))
    }

    @Test
    fun `all observed index date formats retain website Date semantics`() {
        val source = Seanovel()
        val tokyo = ZoneId.of("Asia/Tokyo")
        val midnight = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli()
        assertEquals(midnight, source.updatedAt("2026-10-22T00:00:00Z", tokyo))
        assertEquals(midnight, source.updatedAt("2026-10-22T09:00:00+09:00", tokyo))
        assertEquals(midnight, source.updatedAt("2026-10-22T09:00:00", tokyo))
        assertEquals(midnight, source.updatedAt("2026-10-22", tokyo))
        assertEquals(0, source.updatedAt("", tokyo))
    }

    @Test
    fun `latest uses chronological timestamps and the full catalog in website-sized pages`() = runBlocking {
        val data = JSONArray()
        fun record(slug: String, date: String): JSONObject = JSONObject()
            .put("slug", slug).put("title_ar", slug).put("title_original", slug).put("last_updated", date)
        data.put(record("offset-date", "2026-10-22T10:00:00+02:00"))
        data.put(record("newest", "2026-10-22T08:30:00Z"))
        (1..25).forEach { data.put(record("older-$it", Instant.parse("2026-10-21T00:00:00Z").plusSeconds(it.toLong()).toString())) }
        var requests = 0
        val source = object : Seanovel() {
            override val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("/api/search-index", chain.request().url.encodedPath)
                requests++
                Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                    .body(data.toString().toResponseBody()).build()
            }.build()
        }
        val first = source.getLatestUpdates(1)
        val second = source.getLatestUpdates(2)
        assertEquals("newest", first.first().title)
        assertEquals(24, first.size)
        assertEquals(3, second.size)
        assertEquals(27, (first + second).map { it.url }.distinct().size)
        assertTrue(source.getLatestUpdates(3).isEmpty())
        assertEquals(1, requests)
    }

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
