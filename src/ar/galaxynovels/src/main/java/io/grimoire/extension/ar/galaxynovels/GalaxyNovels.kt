package io.grimoire.extension.ar.galaxynovels

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

@SourceInfo(name = "GalaxyNovels", lang = Language.AR, baseUrl = "https://galaxynovels.com", versionCode = 2)
open class GalaxyNovels : HttpSource(), PopularSource, LatestSource, SearchSource, PaginatedSource, PageListSource {
    override val name = "GalaxyNovels"
    override val lang = Language.AR
    override val baseUrl = "https://galaxynovels.com"
    private val pageCounts = ConcurrentHashMap<String, Int>()
    @Volatile private var cachedSearch: Pair<Long, List<JSONObject>>? = null

    // HttpSource's shared client handles Cloudflare through WebViewCookieJar,
    // matching User-Agent and CloudflareInterceptor before this status check.
    private suspend fun text(url: String): String = get(url).use {
        check(it.isSuccessful) { "GalaxyNovels: HTTP ${it.code}; try opening the site in the app's WebView" }
        it.body?.string() ?: error("GalaxyNovels: empty response")
    }

    private suspend fun document(url: String): Document = Jsoup.parse(text(url), url)

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val path = if (page == 1) "/novels/" else "/novels/page/$page/"
        get("$baseUrl$path?sort=popular&period=month").use { response ->
            if (response.code == 404 && page > 1) return@withContext emptyList()
            check(response.isSuccessful) { "GalaxyNovels: HTTP ${response.code}" }
            Jsoup.parse(response.body?.string().orEmpty(), baseUrl).select("article.wor-novel-card")
                .mapNotNull(::card).distinctBy { it.url }
        }
    }

    // The homepage's latest-chapter feed is a finite selection, not /recent/
    // (which lists newly added books). Do not invent pagination for this feed.
    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        if (page != 1) return@withContext emptyList()
        document(baseUrl).select(".wor-latest-section article.wor-latest-item").mapNotNull(::card).distinctBy { it.url }
    }

    private fun card(el: Element): Novel? {
        val link = el.selectFirst("h2 a[href], h3 a[href]") ?: return null
        return Novel(
            url = link.absUrl("href"), title = link.text(), language = lang,
            thumbnailUrl = el.selectFirst("img.wor-cover-img")?.absUrl("src"),
        )
    }

    // The library page hydrates its query client-side from this public index.
    private suspend fun searchIndex(): List<JSONObject> {
        cachedSearch?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        val doc = document(baseUrl)
        val configText = doc.select("script").firstNotNullOfOrNull { script ->
            Regex("var worReaderSearchConfig = (\\{[^\\n]+});").find(script.data())?.groupValues?.get(1)
        } ?: error("GalaxyNovels: search configuration unavailable")
        val manifestUrl = searchResourceUrl(JSONObject(configText).getString("manifestUrl"))
        val indexUrl = searchResourceUrl(JSONObject(text(manifestUrl)).getString("index"))
        val items = JSONObject(text(indexUrl)).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.also { cachedSearch = System.currentTimeMillis() to it }
    }

    private fun searchResourceUrl(url: String): String = url.toHttpUrl().also {
        check(it.isHttps && it.host == baseUrl.toHttpUrl().host) { "GalaxyNovels: unexpected search resource host" }
    }.toString()

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val terms = normalized(query).split(Regex("\\s+")).filter(String::isNotEmpty)
        searchIndex().filter { entry -> terms.all { normalized(entry.optString("s")).contains(it) } }
            .drop((page - 1) * 30).take(30).map { entry ->
                Novel(url = "$baseUrl${entry.getString("u")}", title = entry.getString("t"), language = lang,
                    thumbnailUrl = baseUrl.toHttpUrl().resolve(entry.getString("c"))?.toString())
            }
    }

    internal fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace('أ', 'ا').replace('إ', 'ا')
        .replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه').replace("ـ", "")

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val doc = document(novel.url)
        parseDetails(doc, novel.url).also { pageCounts[novel.url] = chapterPageCount(doc) }
    }

    internal fun parseDetails(doc: Document, url: String): Novel = Novel(
        url = url, title = doc.selectFirst("article.wor-single-novel-page h1")?.text()
            ?: error("GalaxyNovels: novel details unavailable"),
        language = lang,
        thumbnailUrl = doc.selectFirst(".wor-single-hero__cover img")?.absUrl("src"),
        description = doc.selectFirst(".wor-single-summary__text")?.richDescription(),
        author = doc.selectFirst(".wor-single-hero__meta-text--author")?.text()?.substringAfter(':')?.trim(),
        genres = doc.select(".wor-single-genres-strip a").map { it.text() }.distinct(),
        status = when {
            doc.selectFirst(".wor-single-identity__item--status")?.text()?.contains("مكتمل") == true -> NovelStatus.COMPLETED
            doc.selectFirst(".wor-single-identity__item--status")?.text()?.contains("مستمر") == true -> NovelStatus.ONGOING
            else -> NovelStatus.UNKNOWN
        },
        initialized = true,
    )

    internal fun chapterPageCount(doc: Document): Int = doc.select("a[href*=chapter_page]")
        .mapNotNull { it.absUrl("href").toHttpUrl().queryParameter("chapter_page")?.toIntOrNull() }.maxOrNull() ?: 1

    override suspend fun getPageCount(novel: Novel): Int = withContext(Dispatchers.IO) {
        pageCounts[novel.url] ?: chapterPageCount(document(novel.url)).also { pageCounts[novel.url] = it }
    }

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        require(page > 0)
        if (page > getPageCount(novel)) return@withContext emptyList()
        val url = novel.url.toHttpUrl().newBuilder().setQueryParameter("chapter_page", "$page").build().toString()
        val doc = document(url)
        pageCounts[novel.url] = chapterPageCount(doc)
        parseChapters(doc).also { check(it.isNotEmpty()) { "GalaxyNovels: empty chapter page $page" } }
    }

    internal fun parseChapters(doc: Document): List<Chapter> = doc.select("article.wor-novel-chapter-item").map { row ->
        val link = row.selectFirst("a.wor-novel-chapter-item__num[href]") ?: error("GalaxyNovels: missing chapter URL")
        Chapter(
            url = link.absUrl("href"), name = row.selectFirst("h3")?.text()?.trim() ?: link.text(),
            chapterNumber = link.text().toFloatOrNull() ?: -1f,
            uploadDate = runCatching { OffsetDateTime.parse(row.selectFirst("time[datetime]")?.attr("datetime")).toInstant().toEpochMilli() }.getOrDefault(0),
        )
    }.distinctBy { it.url }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        parsePages(document(chapter.url))
    }

    internal fun parsePages(doc: Document): List<NovelPage> {
        val content = doc.selectFirst("article.wor-reading-page .wor-reader-text-surface")
            ?: error("GalaxyNovels: chapter text unavailable; open it in the app's WebView")
        val paragraphs = content.select("p").filter { it.text().isNotBlank() }
        check(paragraphs.isNotEmpty()) { "GalaxyNovels: empty chapter" }
        return paragraphs.mapIndexed { i, el -> NovelPage(i, PageContent.Text(el.text(), el.richHtml())) }
    }
}
