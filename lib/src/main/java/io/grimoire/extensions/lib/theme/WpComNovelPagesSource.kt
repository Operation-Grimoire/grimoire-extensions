package io.grimoire.extensions.lib.theme

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * Base for WordPress.com-hosted translation sites that publish every novel and
 * chapter as a hand-built Gutenberg *page* (no novel/chapter post types):
 *
 * - a novel-list page ([novelListSlug]) of cover `figure.wp-block-image` links;
 * - one page per novel: cover, a `ul.wp-block-list` of "label : value" lines,
 *   a story section, then the chapters as links whose text is the number;
 * - one page per chapter: a breadcrumb, the prose, prev/next buttons.
 *
 * Everything is read through the public WordPress.com REST API rather than the
 * site itself: it serves the same rendered content and isn't subject to the
 * site's per-IP rate limiting.
 */
abstract class WpComNovelPagesSource :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    ChapterListSource,
    PageListSource {

    /** Slug of the page listing every novel, percent-encoded as in its URL. */
    protected open val novelListSlug: String = "%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa"

    private val siteHost: String get() = baseUrl.toHttpUrl().host

    private fun pagesApi(): HttpUrl.Builder =
        "https://public-api.wordpress.com/wp/v2/sites/$siteHost/pages".toHttpUrl().newBuilder()

    private suspend fun getJson(url: HttpUrl): String = get(url.toString()).use { response ->
        check(response.isSuccessful) { "HTTP ${response.code} for $url" }
        response.body?.string().orEmpty()
    }

    /**
     * Fetch one page by the slug in its site URL; null when it doesn't exist.
     * [isNovel] enables the fallback for novels the list links under a stale
     * slug (renamed pages): the site's own redirect for those lands on an
     * arbitrary chapter, so locate the real novel page by its title instead
     * ([novelTitle], or the words of the stale slug).
     */
    private suspend fun pageBySlug(
        url: String,
        fields: String,
        isNovel: Boolean = false,
        novelTitle: String? = null,
    ): JSONObject? {
        querySlug(movedNovels[url] ?: url, fields)?.let { return it }
        if (!isNovel) return null
        val title = novelTitle?.takeIf { it.isNotBlank() }
            ?: url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() }
                ?.replace(TRAILING_SLUG_NUMBER, "")?.replace('-', ' ')
            ?: return null
        val moved = findMovedNovel(title) ?: return null
        movedNovels[url] = moved.getString("link")
        return moved
    }

    private suspend fun querySlug(url: String, fields: String): JSONObject? {
        val slug = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: return null
        val api = pagesApi()
            // pathSegments are decoded; WordPress matches the encoded slug either way.
            .addQueryParameter("slug", slug)
            .addQueryParameter("_fields", fields)
            .build()
        // An empty result means no such page; HTTP errors propagate so a rate
        // limit or outage isn't mistaken for a missing page.
        return JSONArray(getJson(api)).optJSONObject(0)
    }

    private val movedNovels = ConcurrentHashMap<String, String>()

    /**
     * Search for [title]: a hit titled like the novel that lists chapters *is*
     * the novel page; otherwise a chapter hit ("<title> <n>") names the current
     * novel page in its breadcrumb.
     */
    private suspend fun findMovedNovel(title: String): JSONObject? {
        // A near-empty key would prefix-match every hit and resolve to any novel.
        val key = title.titleKey().takeIf { it.length >= MIN_TITLE_KEY } ?: return null
        val api = pagesApi()
            .addQueryParameter("search", title)
            .addQueryParameter("per_page", "20")
            .addQueryParameter("_fields", "link,title,content")
            .build()
        val hits = JSONArray(getJson(api))
        val viaBreadcrumb = LinkedHashSet<String>()
        for (i in 0 until hits.length()) {
            val hit = hits.getJSONObject(i)
            if (!Jsoup.parse(hit.getJSONObject("title").getString("rendered")).text().titleKey().startsWith(key)) continue
            val body = contentBody(hit, baseUrl)
            if (chapterLinks(body).isNotEmpty()) return hit
            breadcrumbNovelLink(body)?.let(viaBreadcrumb::add)
        }
        return viaBreadcrumb.take(3).firstNotNullOfOrNull { link ->
            querySlug(link, "link,title,content")?.takeIf { chapterLinks(contentBody(it, baseUrl)).isNotEmpty() }
        }
    }

    private fun breadcrumbNovelLink(body: Element): String? =
        body.select("p").firstOrNull { p -> p.select("a[href]").any { it.isSiteRoot() } }
            ?.select("a[href]")?.firstOrNull { !it.isSiteRoot() && it.absUrl("href").toHttpUrlOrNull()?.host == siteHost }
            ?.absUrl("href")

    private fun Element.isSiteRoot(): Boolean = absUrl("href").trimEnd('/').substringAfter("://") == siteHost

    private fun contentBody(page: JSONObject, base: String): Element =
        Jsoup.parseBodyFragment(page.getJSONObject("content").getString("rendered"), base).body()

    // --- Listings -------------------------------------------------------------

    private class Catalogue(
        val fetchedAt: Long,
        val novels: List<Novel>,
        val pageTitles: Map<String, String>,
        val titlesComplete: Boolean,
    )

    @Volatile
    private var catalogue: Catalogue? = null

    private suspend fun novelList(): List<Novel> = catalogue().novels

    private suspend fun catalogue(): Catalogue {
        catalogue?.let {
            // Missing Arabic titles only degrade search, so a partial catalogue is
            // still served, but retried soon rather than kept for the full TTL.
            val ttl = if (it.titlesComplete) LIST_TTL_MS else PARTIAL_LIST_TTL_MS
            if (System.currentTimeMillis() - it.fetchedAt < ttl) return it
        }
        val list = fetchNovelList()
        val (titles, complete) = fetchPageTitles(list)
        return Catalogue(System.currentTimeMillis(), list, titles, complete).also { catalogue = it }
    }

    private suspend fun fetchNovelList(): List<Novel> {
        val page = pageBySlug("$baseUrl/$novelListSlug/", "content") ?: return emptyList()
        val body = contentBody(page, baseUrl)
        // Each cover is a `figure.wp-block-image`; the link sits on the image, the
        // caption, or both, and the title is the caption or a text link under it.
        val list = body.select("figure.wp-block-image:has(img)").mapNotNull { figure ->
            val url = figure.select("a[href]").map { it.absUrl("href") }
                .firstOrNull { it.toHttpUrlOrNull()?.host == siteHost } ?: return@mapNotNull null
            val img = figure.selectFirst("img")!!
            val title = figure.selectFirst("figcaption")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: body.select("a[href]").firstOrNull { it.absUrl("href") == url && it.text().isNotBlank() }?.text()?.trim()
                ?: img.attr("alt").trim()
            Novel(url = url, title = title, language = lang, thumbnailUrl = img.coverUrl())
        }.filter { it.title.isNotEmpty() }.distinctBy { it.url }
        return list
    }

    /**
     * The list shows English titles while each novel page is titled in Arabic;
     * fetch the page titles in batches so search can match either. Batches stay
     * small because Arabic slugs percent-encode to ~9 bytes per letter and the
     * query string grows with every slug. Returns the titles and whether every
     * batch succeeded.
     */
    private suspend fun fetchPageTitles(list: List<Novel>): Pair<Map<String, String>, Boolean> {
        var complete = true
        val titles = list.chunked(TITLE_BATCH).flatMap { chunk ->
            val api = pagesApi().apply {
                chunk.forEach { novel ->
                    novel.url.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }?.let { addQueryParameter("slug[]", it) }
                }
            }.addQueryParameter("per_page", "100").addQueryParameter("_fields", "link,title").build()
            val pages = runCatching { JSONArray(getJson(api)) }.getOrNull()
                ?: run { complete = false; return@flatMap emptyList() }
            (0 until pages.length()).map { i ->
                val page = pages.getJSONObject(i)
                page.getString("link").pathKey() to Jsoup.parse(page.getJSONObject("title").getString("rendered")).text()
            }
        }.toMap()
        return titles to complete
    }

    private fun String.pathKey(): String =
        toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() }?.joinToString("/")?.lowercase() ?: lowercase()

    // The list page is the whole catalogue in the site's own order.
    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        if (page > 1) emptyList() else novelList()
    }

    // Recently published chapter pages, mapped back to their novels. Chapter
    // pages are titled "<English novel title> <n>", the same title the list uses.
    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        val byTitle = novelList().associateBy { it.title.titleKey() }
        val api = pagesApi()
            .addQueryParameter("orderby", "date")
            .addQueryParameter("order", "desc")
            .addQueryParameter("per_page", "100")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("_fields", "title")
            .build()
        // Past the last page the API answers 400 rest_post_invalid_page_number.
        val raw = runCatching { getJson(api) }.getOrElse { if (page > 1) return@withContext emptyList() else throw it }
        val pages = JSONArray(raw)
        (0 until pages.length()).mapNotNull { i ->
            val title = pages.getJSONObject(i).getJSONObject("title").getString("rendered")
            byTitle[Jsoup.parse(title).text().replace(TRAILING_NUMBER, "").titleKey()]
        }.distinctBy { it.url }
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList()
            val catalogue = catalogue()
            val q = query.trim().titleKey()
            if (q.isEmpty()) return@withContext catalogue.novels
            catalogue.novels.filter { novel ->
                q in novel.title.titleKey() || q in catalogue.pageTitles[novel.url.pathKey()].orEmpty().titleKey()
            }
        }

    // --- Novel ----------------------------------------------------------------

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val page = pageBySlug(novel.url, "title,link,content", isNovel = true, novelTitle = novel.title) ?: return@withContext novel
        val body = contentBody(page, novel.url)
        val meta = body.select("ul.wp-block-list li").associate { li ->
            li.text().substringBefore(':').trim() to li.text().substringAfter(':', "").trim()
        }
        fun field(vararg labels: String) = labels.firstNotNullOfOrNull { label ->
            meta.entries.firstOrNull { it.key.contains(label) }?.value
        }?.takeIf { it.isNotEmpty() && it != PLACEHOLDER }
        val rating = field("التقييم")?.let { RATING_REGEX.find(it) }
        novel.copy(
            url = page.getString("link"),
            language = lang,
            title = Jsoup.parse(page.getJSONObject("title").getString("rendered")).text().ifBlank { novel.title },
            thumbnailUrl = body.selectFirst("figure.wp-block-image img")?.coverUrl() ?: novel.thumbnailUrl,
            author = field("المؤلف"),
            description = sectionHtml(body, STORY_LABEL, CHAPTERS_LABEL),
            genres = field("التصنيفات")?.split(GENRE_SEPARATORS)?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList(),
            status = field("الحالة").toNovelStatus(),
            rating = rating?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 5f),
            ratingCount = rating?.groupValues?.get(2)?.toIntOrNull(),
            initialized = true,
        )
    }

    /** Paragraphs between the heading starting with [from] and the one starting with [until]. */
    private fun sectionHtml(body: Element, from: String, until: String): String? {
        var inside = false
        val parts = mutableListOf<String>()
        for (el in body.children()) {
            val text = el.text().trim()
            when {
                text.startsWith(from) -> inside = true
                text.startsWith(until) -> if (inside) break
                inside && el.tagName() == "p" -> el.richHtml().takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        return parts.joinToString("<br><br>").takeIf { it.isNotBlank() }
    }

    // Chapters are links whose text is just the number ("01", "250").
    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val page = pageBySlug(novel.url, "content", isNovel = true, novelTitle = novel.title) ?: return@withContext emptyList()
        chapterLinks(contentBody(page, novel.url))
    }

    private fun chapterLinks(body: Element): List<Chapter> =
        body.select("a[href]").mapNotNull { link ->
            val number = link.text().trim().toAsciiDigits().takeIf { NUMBER_REGEX.matches(it) }?.toFloatOrNull()
                ?: return@mapNotNull null
            Chapter(url = link.absUrl("href"), name = "الفصل ${link.text().trim()}", chapterNumber = number)
        }.distinctBy { it.url }.sortedBy { it.chapterNumber }

    // --- Chapter --------------------------------------------------------------

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val page = pageBySlug(chapter.url, "content")
            ?: throw IllegalStateException("Chapter page not found: ${chapter.url}")
        val body = contentBody(page, chapter.url)
        body.select(".wp-block-buttons, .wp-block-spacer, button, script, style").remove()
        // Breadcrumb: "الرئيسية / <novel>" (a link to the site root) and "/ الفصل N".
        body.select("p").filter { p -> p.select("a[href]").any { it.isSiteRoot() } || p.text().trim().startsWith("/") }
            .forEach { it.remove() }
        body.select("p, figure img").mapNotNull { el ->
            if (el.tagName() == "img") {
                el.coverUrl()?.let { PageContent.Image(it) }
            } else {
                val text = el.text()
                if (text.isBlank()) null else PageContent.Text(text, el.richHtml().takeIf { it != text })
            }
        }.mapIndexed { index, content -> NovelPage(index, content) }
    }

    // --- Helpers --------------------------------------------------------------

    private fun Element.coverUrl(): String? =
        sequenceOf("data-orig-file", "src").map { attr(it).trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }

    private fun String.titleKey(): String = lowercase().filter { it.isLetterOrDigit() }

    private fun String.toAsciiDigits(): String =
        map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("مستمر") -> NovelStatus.ONGOING
        contains("مكتمل") -> NovelStatus.COMPLETED
        contains("متوقف") -> NovelStatus.HIATUS
        contains("ملغ") -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    private companion object {
        const val LIST_TTL_MS = 10 * 60 * 1000L
        const val PARTIAL_LIST_TTL_MS = 60 * 1000L
        const val TITLE_BATCH = 40
        const val MIN_TITLE_KEY = 6
        const val PLACEHOLDER = "تحديث" // "to be updated" — an unset field
        const val STORY_LABEL = "القصة"
        const val CHAPTERS_LABEL = "الفصول"
        val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
        val TRAILING_SLUG_NUMBER = Regex("""-\d+$""")
        val TRAILING_NUMBER = Regex("""\s*\d+(?:\.\d+)?\s*$""")
        val RATING_REGEX = Regex("""([\d.]+)\s*/\s*5\s*من\s*(\d+)""")
        val GENRE_SEPARATORS = Regex("""[،؛,;]""")
    }
}
