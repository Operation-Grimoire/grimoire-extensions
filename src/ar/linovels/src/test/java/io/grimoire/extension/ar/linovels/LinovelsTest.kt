package io.grimoire.extension.ar.linovels

import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.network.SourceBrowserReading
import io.grimoire.api.network.SourceDocumentDecision
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinovelsTest {
    private val source = Linovels()
    private val chapterUrl = "https://linovels.com/sample-chapter-1-%d9%86/"

    @Test
    fun `cards keep site order and skip unavailable series and duplicate URLs`() {
        val document = Jsoup.parse("""
            <main>
                <a class="zm-cc" href="/coming-soon/" title="Coming Soon"><span class="nk-lk"></span></a>
                <a class="zm-cc" href="/sample/" aria-label="Sample">
                    <img src="data:image/svg+xml;base64,AA" data-src="/covers/sample.jpg">
                </a>
                <a class="zm-cc" href="/another/"><span><b>جديدة</b></span><b>Another</b></a>
                <a class="zm-cc" href="/sample/" title="Duplicate"></a>
                <a class="zm-cc" href="https://example.com/" title="External"></a>
            </main>
        """.trimIndent(), source.baseUrl)

        val novels = source.cards(document, "main a.zm-cc")

        assertEquals(listOf("Sample", "Another"), novels.map { it.title })
        assertEquals("https://linovels.com/covers/sample.jpg", novels.first().thumbnailUrl)
    }

    @Test
    fun `details use the full synopsis and source metadata`() {
        val document = Jsoup.parse("""
            <a href="/sample/" title="Sample"></a>
            <h1 class="zm-nv-title">Sample Series</h1>
            <div class="zm-nv-cover"><img src="/covers/updated.jpg"></div>
            <div class="zm-meta"><span class="zm-chip completed">مكتملة</span><a class="zm-gchip">غموض</a></div>
            <div id="zm-pane-story"><div class="zm-story"><p>النص الأول</p><p><em>النص الثاني</em></p></div></div>
        """.trimIndent(), source.baseUrl)
        val novel = source.cards(document, "a[title]").single()

        val details = source.details(document, novel)

        assertEquals("Sample Series", details.title)
        assertEquals("https://linovels.com/covers/updated.jpg", details.thumbnailUrl)
        assertEquals(NovelStatus.COMPLETED, details.status)
        assertEquals(listOf("غموض"), details.genres)
        assertEquals("النص الأول<br><br><i>النص الثاني</i>", details.description)
        assertTrue(details.initialized)
    }

    @Test
    fun `chapter list preserves the full encoded slug and ignores other tabs`() {
        val document = Jsoup.parse("""
            <ul id="zm-chlist">
                <li><a href="/sample-chapter-2/"><span class="no">2</span><span class="tt">الفصل الثاني</span><i class="nk-lk"></i></a></li>
                <li><a href="$chapterUrl"><span class="no">1</span><span class="tt">الفصل الأول</span></a></li>
            </ul>
            <ul><li><a href="/other/"><span class="tt">Other</span></a></li></ul>
        """.trimIndent(), source.baseUrl)

        val chapters = source.chapters(document)

        assertEquals(listOf(1f, 2f), chapters.map { it.chapterNumber })
        assertEquals(listOf(false, true), chapters.map { it.locked })
        assertEquals(chapterUrl, chapters.first().url)
    }

    @Test
    fun `visible Arabic retains paragraph breaks and inline styling`() {
        val content = source.pages(
            "<p>النص <em>الأول</em><br>تكملة</p><p>النص الثاني<span hidden>مخفي</span></p>",
            chapterUrl,
        ).single().content as PageContent.Text

        assertEquals("النص الأول\nتكملة\n\nالنص الثاني", content.text)
        assertEquals("النص <i>الأول</i><br>تكملة<br><br>النص الثاني", content.html)
    }

    @Test
    fun `browser waits for the chapter then accepts its visible prose`() {
        assertEquals(SourceDocumentDecision.Wait, source.browserDecision(reading(403), chapterUrl))
        assertEquals(
            SourceDocumentDecision.Accept,
            source.browserDecision(reading(200, url = chapterUrl.replace("%d9%86", "%D9%86"), html = "<p>نص</p>"), chapterUrl),
        )
    }

    @Test
    fun `browser refuses locked chapters and unexpected destinations`() {
        for (reading in listOf(
            reading(403, title = "Sample — مقفلة"),
            reading(200, locked = true, html = "<p>نص</p>"),
            reading(403, title = "الوصول الآلي غير مسموح"),
            reading(200, url = "https://linovels.com/wrong-chapter/", html = "<p>نص</p>"),
            reading(200, url = "https://example.com/", html = "<p>نص</p>"),
        )) {
            assertInstanceOf(SourceDocumentDecision.Reject::class.java, source.browserDecision(reading, chapterUrl))
        }
    }

    private fun reading(
        status: Int,
        url: String = chapterUrl,
        title: String = "",
        locked: Boolean = false,
        html: String = "",
    ): SourceBrowserReading {
        val data = JSONObject().put("url", url).put("title", title).put("locked", locked).put("html", html)
        return SourceBrowserReading(chapterUrl, status, JSONObject.quote(data.toString()))
    }
}
