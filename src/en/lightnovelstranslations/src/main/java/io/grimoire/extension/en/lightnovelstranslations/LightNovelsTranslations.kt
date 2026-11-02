package io.grimoire.extension.en.lightnovelstranslations

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
import io.grimoire.api.source.http.ParsedHttpSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@SourceInfo(
    name = "Light Novels Translations",
    lang = Language.EN,
    baseUrl = "https://lightnovelstranslations.com",
    versionCode = 13,
    novelUpdatesGroups = ["Light Novels Translations"],
)
class LightNovelsTranslations :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    PaginatedSource,
    PageListSource {

    override val name = "Light Novels Translations"
    override val lang = Language.EN
    override val baseUrl = "https://lightnovelstranslations.com"

    // The catalogue lives at /browse/ (the old /read/ 301s there): 6 cards per
    // page, `?paged=N`, with a `sortby` order and a title search
    // (`field-search`, also matching alternate titles). There is no
    // chapter-update ordering; "Newest additions" is the closest to latest.
    private fun browseUrl(page: Int, sortBy: String? = null, query: String? = null) =
        "$baseUrl/browse/".toHttpUrl().newBuilder().apply {
            sortBy?.let { addQueryParameter("sortby", it) }
            query?.let { addQueryParameter("field-search", it) }
            addQueryParameter("paged", page.toString())
        }.build().toString()

    private suspend fun browse(url: String): List<Novel> = withContext(Dispatchers.IO) {
        get(url).asJsoup().select("article.read_list-story-item").mapNotNull { it.toListNovel() }
    }

    override suspend fun getPopularNovels(page: Int): List<Novel> = browse(browseUrl(page, sortBy = "most-liked"))

    override suspend fun getLatestUpdates(page: Int): List<Novel> = browse(browseUrl(page, sortBy = "most-recent"))

    // The site-wide WordPress search (`/?s=`) now returns chapter posts rather
    // than novels, so search the catalogue instead.
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        browse(browseUrl(page, query = query.trim()))

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val url = resolveUrl(novel.url)
        val document = get(url).asJsoup()
        val author = document.select("div.novel_detail_info li")
            .firstOrNull { it.selectFirst("span")?.text()?.startsWith("Author", ignoreCase = true) == true }
            ?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
        Novel(
            url = url,
            title = document.selectFirst("div.novel_title h3")?.text()?.trim().orEmpty(),
            language = lang,
            thumbnailUrl = document.selectFirst("div.novel-image img")?.absUrl("src"),
            author = author,
            description = document.selectFirst("div.novel_text")?.richDescription()?.takeIf { it.isNotBlank() },
            genres = document.select("div.novel_tags_item span").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = document.selectFirst("div.novel_status")?.text().toNovelStatus(),
            initialized = true,
        )
    }

    // The table of contents is progressive: the novel page groups chapters
    // into `section.lnt-volume` blocks (in reader order), each heading carrying
    // its chapter count, but server-renders only the first page of the first
    // block. Every other page is fetched from the block's link with
    // `lnt_volume=<id>&lnt_page=<n>&lnt_fragment=toc`, which returns
    // `{success, data: {html, page, total}}`. There is no endpoint that
    // returns the whole list (page size is fixed server-side), so this is a
    // PaginatedSource: the (block, page) pairs are flattened into one page
    // index, and the host walks them in parallel with retry + strict
    // truncation detection.
    override suspend fun getPageCount(novel: Novel): Int = loadToc(novel).pages.size

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        val toc = tocCache[novelKey(novel)] ?: loadToc(novel)
        val target = toc.pages.getOrNull(page - 1) ?: return@withContext emptyList()
        target.inline?.let { return@withContext it }
        // A non-2xx or unparseable response maps to an empty page: the host
        // retries an in-range empty page once, then fails the fetch loudly
        // instead of truncating. Transport errors (incl. Cloudflare) propagate.
        val url = target.volumeUrl.toHttpUrl().newBuilder()
            .setQueryParameter("lnt_page", target.page.toString())
            .setQueryParameter("lnt_fragment", "toc")
            .build().toString()
        val body = get(url).use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body?.string().orEmpty()
        }
        parseTocFragment(body, target.page)?.let { chaptersIn(it) }.orEmpty()
    }

    // `inline` holds the already-parsed rows of the server-rendered page (not
    // the DOM, which would pin the whole novel page in the cache).
    private class TocPage(val volumeUrl: String, val page: Int, val inline: List<Chapter>? = null)

    private class Toc(val pages: List<TocPage>)

    // getPageCount runs once at the start of every chapter-list fetch, so it
    // always re-reads the novel page; the per-page calls that follow reuse it.
    private val tocCache = object : LinkedHashMap<String, Toc>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Toc>) = size > TOC_CACHE_SIZE
    }.let { java.util.Collections.synchronizedMap(it) }

    private fun novelKey(novel: Novel) = resolveUrl(novel.url)

    private suspend fun loadToc(novel: Novel): Toc = withContext(Dispatchers.IO) {
        val url = novelKey(novel)
        buildToc(get(url).asJsoup(), url).also { tocCache[url] = it }
    }

    // --- Pure TOC parsing, exposed for unit tests ---------------------------

    internal fun tocPageCount(document: Document): Int = buildToc(document, baseUrl).pages.size

    private fun buildToc(document: Document, novelUrl: String): Toc {
        val volumes = document.select("#lnt-chapter-list section.lnt-volume")
        // Single-list layout (no volume blocks): everything is inline.
        if (volumes.isEmpty()) return Toc(listOf(TocPage(novelUrl, 1, chaptersIn(document))))
        val pageSize = inferPageSize(document)
        val pages = mutableListOf<TocPage>()
        volumes.forEachIndexed { index, section ->
            val link = section.selectFirst("a.lnt-volume-link")?.absUrl("href")
                ?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val count = section.selectFirst("a.lnt-volume-link > span")?.text()
                ?.let { COUNT_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val panel = section.selectFirst("div.lnt-volume-panel")
            val loaded = panel?.takeIf { it.attr("data-loaded") == "1" && it.selectFirst("li.chapter-item") != null }
            val pageCount = when {
                count != null -> (count + pageSize - 1) / pageSize
                // Unknown count: fall back to the rendered nav ("Page 1 of N").
                loaded != null -> loaded.selectFirst("nav.lnt-page-nav span")?.text()
                    ?.let { PAGE_OF_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
                else -> 1
            }
            for (n in 1..pageCount) {
                // The first block's first page is already in the document.
                val inline = if (n == 1 && index == 0 && loaded != null) chaptersIn(loaded) else null
                pages += TocPage(link, n, inline)
            }
        }
        return Toc(pages)
    }

    // The rendered first page is a full page whenever it links to a next one,
    // so its row count is the server's page size. Otherwise assume the
    // current default — a single short block never needs a second page.
    private fun inferPageSize(document: Document): Int {
        val first = document.selectFirst("#lnt-chapter-list div.lnt-volume-panel[data-loaded=1] div.lnt-chapter-page")
            ?: return DEFAULT_PAGE_SIZE
        if (nextTocPageUrl(first.parent() ?: first) == null) return DEFAULT_PAGE_SIZE
        return first.select("li.chapter-item").size.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE
    }

    internal fun chaptersIn(root: Element): List<Chapter> =
        root.select("li.chapter-item").mapNotNull { it.toChapter() }

    // The nav holds "Previous"/"Next" `a.lnt-page-link`s carrying `lnt_page`;
    // pick the one pointing at the page after this one's `data-page`.
    internal fun nextTocPageUrl(root: Element): String? {
        val current = root.selectFirst("div.lnt-chapter-page[data-page]")
            ?.attr("data-page")?.toIntOrNull() ?: return null
        return root.select("nav.lnt-page-nav a.lnt-page-link")
            .map { it.absUrl("href") }
            .firstOrNull { it.toHttpUrlOrNull()?.queryParameter("lnt_page")?.toIntOrNull() == current + 1 }
    }

    /**
     * Parses a `lnt_fragment=toc` response. Returns null when it is not a
     * successful fragment for [expectedPage] — the server clamps an
     * out-of-range `lnt_page` to the last page, which would otherwise feed the
     * host duplicate rows.
     */
    internal fun parseTocFragment(json: String, expectedPage: Int): Element? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val data = obj.optJSONObject("data") ?: return null
        if (!obj.optBoolean("success")) return null
        if (data.has("page") && data.optInt("page") != expectedPage) return null
        return Jsoup.parseBodyFragment(data.optString("html"), baseUrl).body()
    }

    // Flat list of <p> in .text_story interleaved with <hr/> scene breaks;
    // illustrations are <p>-wrapped <img>.
    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        get(resolveUrl(chapter.url)).asJsoup()
            .select(pageListSelector())
            .mapIndexed { index, element -> pageFromElement(element, index) }
    }

    // Exposed for unit tests, which parse captured chapter fixtures directly.
    internal fun pageListSelector(): String = "div.text_story > p, div.text_story > hr"

    internal fun pageFromElement(element: Element, index: Int): NovelPage = element.toPage(index)

    private fun Element.toListNovel(): Novel? {
        val link = selectFirst("div.wrap_item_content h3 a") ?: return null
        return Novel(
            url = link.attr("href"),
            title = link.text().trim(),
            language = lang,
            thumbnailUrl = selectFirst("div.item_thumb img")?.imageUrl(),
        )
    }

    private fun Element.toChapter(): Chapter? {
        val link = selectFirst("a[href]") ?: return null
        return Chapter(
            url = link.absUrl("href").ifBlank { link.attr("href") },
            name = link.text().trim(),
            // VIP chapters carry a `lock` class on the <li>; free ones `unlock`.
            locked = hasClass("lock"),
        )
    }

    private fun Element.toPage(index: Int): NovelPage {
        if (tagName().equals("hr", ignoreCase = true)) return NovelPage(index, PageContent.Separator())
        val imageUrl = selectFirst("img")?.imageUrl()
        if (imageUrl != null) return NovelPage(index, PageContent.Image(imageUrl))
        return NovelPage(index, PageContent.Text(text(), richHtml()))
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotBlank() }

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) -> NovelStatus.HIATUS
        contains("Cancelled", ignoreCase = true) -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val TOC_CACHE_SIZE = 32
        val COUNT_REGEX = Regex("""(\d+)\s*chapter""", RegexOption.IGNORE_CASE)
        val PAGE_OF_REGEX = Regex("""of\s+(\d+)""", RegexOption.IGNORE_CASE)
    }
}
