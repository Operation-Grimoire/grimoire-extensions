package io.grimoire.extension.ar.galaxynovels

import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.network.CloudflareInterceptor
import io.grimoire.api.network.UserAgentInterceptor
import io.grimoire.api.network.WebViewCookieJar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GalaxyNovelsTest {
    private val source = GalaxyNovels()

    @Test
    fun `source retains host WebView cookies User-Agent and Cloudflare handling`() {
        assertTrue(source.client.cookieJar is WebViewCookieJar)
        assertTrue(source.client.interceptors.any { it is UserAgentInterceptor })
        assertTrue(source.client.interceptors.any { it is CloudflareInterceptor })
    }

    @Test
    fun `external search manifest is rejected before any external request`() {
        val requested = mutableListOf<String>()
        val guarded = object : GalaxyNovels() {
            override val client = OkHttpClient.Builder().addInterceptor { chain ->
                requested += chain.request().url.host
                Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                    .body("""<script>var worReaderSearchConfig = {"manifestUrl":"https://other.invalid/manifest.json"};</script>""".toResponseBody())
                    .build()
            }.build()
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { guarded.searchNovels("query", 1, emptyList()) }
        }
        assertEquals(listOf("galaxynovels.com"), requested)
    }

    @Test
    fun `chapter pagination preserves full title slug and finds last page`() {
        val doc = Jsoup.parse("""
            <article class="wor-novel-chapter-item">
              <a class="wor-novel-chapter-item__num" href="/novel/book/chapter-1/title/">1</a>
              <h3>First</h3><time datetime="2026-10-01T12:00:00+00:00"></time>
            </article>
            <a href="?chapter_page=2#chapters">2</a><a href="?chapter_page=11#chapters">11</a>
        """, "https://galaxynovels.com/novel/book/")
        assertEquals(11, source.chapterPageCount(doc))
        assertEquals("https://galaxynovels.com/novel/book/chapter-1/title/", source.parseChapters(doc).single().url)
        assertTrue(source.parseChapters(doc).single().uploadDate > 0)
    }

    @Test
    fun `reader excludes controls and other page content`() {
        val pages = source.parsePages(Jsoup.parse("""
            <p>Unrelated</p><article class="wor-reading-page">
              <div class="wor-reader-text-surface"><p>Prose <b>bold</b></p></div>
              <p>Navigation</p>
            </article>
        """))
        assertEquals("Prose bold", (pages.single().content as PageContent.Text).text)
        assertTrue((pages.single().content as PageContent.Text).html!!.contains("<b>bold</b>"))
    }

    @Test
    fun `search normalizes Arabic spelling and diacritics`() {
        assertEquals(source.normalized("روايه"), source.normalized("رِوَايَة"))
    }
}
