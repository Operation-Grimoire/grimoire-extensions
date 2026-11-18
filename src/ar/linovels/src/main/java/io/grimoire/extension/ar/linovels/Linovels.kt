package io.grimoire.extension.ar.linovels

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.network.SourceBrowser
import io.grimoire.api.network.SourceBrowserReading
import io.grimoire.api.network.SourceDocumentDecision
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException

@SourceInfo(name = "Linovels", lang = Language.AR, baseUrl = "https://linovels.com", versionCode = 1)
open class Linovels : HttpSource(), PopularSource, LatestSource, SearchSource, ChapterListSource, PageListSource {
    override val name = "Linovels"
    override val lang = Language.AR
    override val baseUrl = "https://linovels.com"

    private suspend fun document(url: String, allowNotFound: Boolean = false): Document? =
        withContext(Dispatchers.IO) {
            val target = requireNotNull(sourceUrl(url)) { "Linovels: invalid source URL" }
            get(target).use { response ->
                if (allowNotFound && response.code == 404) return@withContext null
                if (!response.isSuccessful) throw IOException("Linovels: HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Linovels: empty response")
                Jsoup.parse(body, response.request.url.toString())
            }
        }

    override suspend fun getPopularNovels(page: Int): List<Novel> =
        if (page == 1) cards(requireNotNull(document(baseUrl)), "#PopularPosts2 a.zm-hc[href]") else emptyList()

    override suspend fun getLatestUpdates(page: Int): List<Novel> =
        if (page == 1) cards(requireNotNull(document(baseUrl)), LATEST_CARDS) else emptyList()

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> {
        require(page > 0)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isBlank()) addPathSegments("search/label/Series/")
            else {
                addQueryParameter("search-type", "series")
                addQueryParameter("s", query.trim())
            }
            if (page > 1) addPathSegments("page/$page/")
        }.build()
        val result = document(url.toString(), allowNotFound = page > 1) ?: return emptyList()
        return cards(result, "main a.zm-cc[href]")
    }

    internal fun cards(document: Document, selector: String): List<Novel> =
        document.select(selector).mapNotNull { card ->
            // Coming-soon series are locked by the site and have no readable chapters.
            if (card.selectFirst(".nk-lk") != null) return@mapNotNull null
            val url = sourceUrl(card.absUrl("href")) ?: return@mapNotNull null
            val title = card.attr("title").ifBlank { card.attr("aria-label") }
                .ifBlank { card.children().firstOrNull { it.tagName() == "b" }?.text().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            Novel(url = url, title = title, language = lang, thumbnailUrl = cover(card.selectFirst("img")))
        }.distinctBy { it.url }

    override suspend fun getNovelDetails(novel: Novel): Novel =
        details(requireNotNull(document(novel.url)), novel)

    internal fun details(document: Document, novel: Novel): Novel {
        val title = document.selectFirst(".zm-nv-title")?.text()
            ?: throw IOException("Linovels: novel details unavailable")
        return novel.copy(
            title = title,
            thumbnailUrl = cover(document.selectFirst(".zm-nv-cover img")) ?: novel.thumbnailUrl,
            description = document.selectFirst("#zm-pane-story .zm-story")?.richDescription(),
            genres = document.select(".zm-meta .zm-gchip").eachText(),
            status = when {
                document.selectFirst(".zm-meta .zm-chip.completed") != null -> NovelStatus.COMPLETED
                document.selectFirst(".zm-meta .zm-chip.ongoing") != null -> NovelStatus.ONGOING
                else -> NovelStatus.UNKNOWN
            },
            initialized = true,
        )
    }

    override suspend fun getChapterList(novel: Novel): List<Chapter> =
        chapters(requireNotNull(document(novel.url)))

    internal fun chapters(document: Document): List<Chapter> {
        val list = document.selectFirst("#zm-chlist") ?: throw IOException("Linovels: chapter list unavailable")
        return list.select("li > a[href]").mapNotNull { link ->
            val url = sourceUrl(link.absUrl("href")) ?: return@mapNotNull null
            val title = link.selectFirst(".tt")?.text() ?: return@mapNotNull null
            Chapter(
                url = url,
                name = title,
                chapterNumber = link.selectFirst(".no")?.text()?.toFloatOrNull() ?: -1f,
                locked = link.selectFirst(".nk-lk") != null,
            )
        }.distinctBy { it.url }.asReversed()
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        if (chapter.locked) throw IOException("Linovels: this chapter is locked")
        val url = requireNotNull(sourceUrl(chapter.url)) { "Linovels: invalid chapter URL" }
        if (!SourceBrowser.ready) throw IOException("Linovels: chapter reading requires the Grimoire browser")
        val reading = SourceBrowser.read(url, READER_SCRIPT) { browserDecision(it, url) }
        pages(browserPayload(reading).getString("html"), url)
    }

    internal fun browserDecision(reading: SourceBrowserReading, requestedUrl: String): SourceDocumentDecision {
        val data = browserPayload(reading)
        val actual = sourceUrl(data.getString("url"))?.toHttpUrl()
        if (actual?.pathSegments != requestedUrl.toHttpUrl().pathSegments) {
            return SourceDocumentDecision.Reject(IOException("Linovels: unexpected chapter URL"))
        }

        val title = data.getString("title")
        return when {
            title.endsWith("— مقفلة") || data.getBoolean("locked") ->
                SourceDocumentDecision.Reject(IOException("Linovels: this chapter is locked"))
            title == "الوصول الآلي غير مسموح" ->
                SourceDocumentDecision.Reject(IOException("Linovels: the site denied browser access"))
            reading.status in 200..299 && data.getString("html").isNotBlank() -> SourceDocumentDecision.Accept
            reading.status in 200..299 || reading.status in listOf(403, 503) -> SourceDocumentDecision.Wait
            else -> SourceDocumentDecision.Reject(IOException("Linovels: HTTP ${reading.status}"))
        }
    }

    internal fun pages(html: String, url: String): List<NovelPage> {
        val body = Jsoup.parseBodyFragment(html, url).body()
        body.select("script, style, noscript, [hidden], [aria-hidden=true]").remove()
        val rich = body.richDescription()
        val plain = Jsoup.parseBodyFragment(rich).body()
        plain.select("br").forEach { it.replaceWith(TextNode("\n")) }
        val text = plain.wholeText().trim()
        if (text.isBlank()) throw IOException("Linovels: chapter text unavailable")
        return listOf(NovelPage(0, PageContent.Text(text, rich)))
    }

    private fun browserPayload(reading: SourceBrowserReading): JSONObject =
        JSONObject(JSONArray("[${reading.javascript}]").getString(0))

    private fun cover(image: Element?): String? =
        image?.absUrl("data-src")?.ifBlank { image.absUrl("src") }
            ?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()

    private fun sourceUrl(url: String): String? =
        url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "linovels.com" && it.port == 443 }?.toString()

    private companion object {
        const val LATEST_CARDS = "section.zm-nrow:has(a[href*='/search/label/Series']) .zm-ngrid > a.zm-hc[href]"
        val READER_SCRIPT = """
            (() => {
                const reader = document.querySelector('#nk-novel-reader');
                const locked = Array.from(document.querySelectorAll('main input[type="password"]'))
                    .some(input => !input.closest('.hidden, [hidden]') && input.getClientRects().length > 0);
                const visible = reader && reader.getClientRects().length > 0
                    && getComputedStyle(reader).visibility === 'visible';
                return JSON.stringify({
                    url: location.href,
                    title: document.title,
                    locked,
                    html: !locked && visible && reader.innerText.trim() ? reader.innerHTML : ''
                });
            })()
        """.trimIndent()
    }
}
