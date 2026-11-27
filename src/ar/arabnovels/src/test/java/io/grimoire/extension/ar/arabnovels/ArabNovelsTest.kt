package io.grimoire.extension.ar.arabnovels

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class ArabNovelsTest {
    private val source = ArabNovels()
    private val novelUrl = "https://arabnovels.com/novels/the-strange"

    @Test
    fun `catalog cards unwrap optimized covers and skip foreign or duplicate links`() {
        val document = Jsoup.parse("""
            <section aria-label="نتائج البحث">
                <article><a href="/novels/the-strange">
                    <img src="/_next/image?url=%2Fmedia%2Fcovers%2Fabc.webp&amp;w=3840&amp;q=75">
                    <h3>الغريب</h3><p>Author</p>
                </a></article>
                <article><a href="/novels/lord-of-truth"><h3>لورد الحقيقة</h3></a></article>
                <article><a href="/novels/the-strange"><h3>Duplicate</h3></a></article>
                <article><a href="/u/someone"><h3>Profile</h3></a></article>
                <article><a href="https://example.com/novels/x"><h3>External</h3></a></article>
            </section>
        """.trimIndent(), source.baseUrl)

        val novels = source.cards(document)

        assertEquals(listOf("الغريب", "لورد الحقيقة"), novels.map { it.title })
        assertEquals("https://arabnovels.com/media/covers/abc.webp", novels.first().thumbnailUrl)
    }

    @Test
    fun `details come from the Book JSON-LD and the chapter status line`() {
        val book = JSONObject()
            .put("@type", "Book")
            .put("name", "الغريب")
            .put("author", JSONObject().put("@type", "Person").put("name", "Diablo"))
            .put("description", "أين أنا؟\n\nاستيقظت")
            .put("genre", JSONArray().put("فانتازيا").put(""))
            .put("image", "https://arabnovels.com/media/covers/abc.webp")
        val document = Jsoup.parse("""
            <script type="application/ld+json">$book</script>
            <article><h1>Fallback</h1>
                <section aria-labelledby="chapters-heading"><p>الحالة: مكتملة</p></section>
            </article>
        """.trimIndent(), source.baseUrl)

        val details = source.details(document, Novel(novelUrl, "", Language.AR))

        assertEquals("الغريب", details.title)
        assertEquals("Diablo", details.author)
        assertEquals("أين أنا؟\n\nاستيقظت", details.description)
        assertEquals(listOf("فانتازيا"), details.genres)
        assertEquals("https://arabnovels.com/media/covers/abc.webp", details.thumbnailUrl)
        assertEquals(NovelStatus.COMPLETED, details.status)
        assertTrue(details.initialized)
    }

    @Test
    fun `chapter list reads every chapter from the RSC payload`() {
        val rows = JSONArray()
            .put(row(2, "ارس", "FREE"))
            .put(row(1, "اين انا", "FREE"))
            .put(row(1.5, "", "FREE"))
            .put(row(3, "مقفل", "COINS"))
        // The payload is split across several pushes, mid-array.
        val flight = "48:[\"\$\",\"\$L48\",null,{\"slug\":\"the-strange\",\"chapters\":$rows}]\n"
        val split = flight.length / 2
        val document = Jsoup.parse("""
            <script>self.__next_f.push([0])</script>
            <script>self.__next_f.push(${JSONArray().put(1).put(flight.substring(0, split))})</script>
            <script>self.__next_f.push(${JSONArray().put(1).put(flight.substring(split))})</script>
            <ol><li><a href="/read/the-strange/2">2</a></li></ol>
        """.trimIndent(), source.baseUrl)

        val chapters = source.chapters(document, novelUrl)

        assertEquals(listOf(1f, 1.5f, 2f, 3f), chapters.map { it.chapterNumber })
        assertEquals(
            listOf(
                "https://arabnovels.com/read/the-strange/1",
                "https://arabnovels.com/read/the-strange/1.5",
                "https://arabnovels.com/read/the-strange/2",
                "https://arabnovels.com/read/the-strange/3",
            ),
            chapters.map { it.url },
        )
        assertEquals(listOf("الفصل 1: اين انا", "الفصل 1.5"), chapters.take(2).map { it.name })
        assertEquals(listOf(false, false, false, true), chapters.map { it.locked })
        assertEquals(1795635992634L, chapters.first().uploadDate)
    }

    @Test
    fun `chapter list fails rather than returning the truncated HTML list`() {
        val document = Jsoup.parse("""<ol><li><a href="/read/the-strange/2">2</a></li></ol>""", source.baseUrl)
        assertThrows<IOException> { source.chapters(document, novelUrl) }
    }

    @Test
    fun `chapter body keeps paragraphs and drops hidden content`() {
        val document = Jsoup.parse("""
            <main><h1>اين انا</h1>
                <div id="chapter-body"><p>«أين <em>أنا</em>؟»</p><p></p><p>النص الثاني<span hidden>مخفي</span></p></div>
                <article aria-label="تعليق">تعليق</article>
            </main>
        """.trimIndent(), source.baseUrl)

        val content = source.pages(document).single().content as PageContent.Text

        assertEquals("«أين أنا؟»\n\nالنص الثاني", content.text)
        assertEquals("«أين <i>أنا</i>؟»<br><br>النص الثاني", content.html)
    }

    @Test
    fun `missing chapter body is an error`() {
        assertThrows<IOException> { source.pages(Jsoup.parse("<main></main>")) }
    }

    private fun row(number: Number, title: String, access: String) = JSONObject()
        .put("id", "c$number")
        .put("number", number)
        .put("title", title)
        .put("access", access)
        .put("publishedAt", "2026-11-25T19:46:32.634Z")
}
