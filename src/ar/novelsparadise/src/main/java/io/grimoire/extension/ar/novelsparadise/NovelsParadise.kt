package io.grimoire.extension.ar.novelsparadise

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.AdultContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.FilterSource
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.ParsedHttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Novels Paradise (روايات الجنة) — a custom PHP site under `/np-light/`, rendered
 * server-side and fronted by Cloudflare (solved by the API's
 * CloudflareInterceptor). Every chapter is free; membership only removes ads.
 *
 * The site rate-limits hard (429 after a handful of back-to-back requests), so
 * requests are spaced out by [PoliteInterceptor].
 */
@SourceInfo(
    name = "Novels Paradise",
    lang = Language.AR,
    baseUrl = "https://novelsparadise.site",
    versionCode = 1,
    adultContent = AdultContent.PARTIAL,
)
class NovelsParadise :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    ChapterListSource,
    PageListSource {

    override val name = "Novels Paradise"
    override val lang = Language.AR
    override val baseUrl = "https://novelsparadise.site"

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor(PoliteInterceptor())
        .build()

    private val appUrl get() = "$baseUrl/np-light"

    // --- Listings -------------------------------------------------------------

    override suspend fun getPopularNovels(page: Int): List<Novel> = list(page) { addQueryParameter("order", "popular") }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = list(page) { addQueryParameter("order", "updated") }

    // The library page takes the search query and every filter, so search and
    // browse share one endpoint.
    override val supportsSearchWithFilters: Boolean = true

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> = list(page) {
        query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
        filters.filterIsInstance<SelectFilter>().forEach { f ->
            f.slugs.getOrNull(f.state)?.takeIf { it.isNotEmpty() }?.let { addQueryParameter(f.param, it) }
        }
        filters.filterIsInstance<CheckGroupFilter>().forEach { group ->
            group.state.filter { it.state }.forEach { box -> group.valueByLabel[box.name]?.let { addQueryParameter(group.param, it) } }
        }
    }

    private suspend fun list(page: Int, params: HttpUrl.Builder.() -> Unit): List<Novel> = withContext(Dispatchers.IO) {
        val url = "$appUrl/series-list/".toHttpUrl().newBuilder().apply {
            params()
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        document(url.toString()).select("article.series-card a.series-card-main[href]").map { card ->
            Novel(
                url = card.absUrl("href"),
                title = card.selectFirst("h2")?.text()?.trim().orEmpty(),
                language = lang,
                thumbnailUrl = card.selectFirst("img")?.imageUrl(),
            )
        }.filter { it.title.isNotEmpty() }.distinctBy { it.url }
    }

    // --- Filters (read from the library page's own filter form) --------------

    @Volatile
    private var cachedFilters: List<Filter<*>> = emptyList()
    private val filterMutex = Mutex()

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = cachedFilters.ifEmpty {
        listOf(Filter.Header("افتح الفلاتر لتحميل التصنيفات والترتيب."))
    }

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        filterMutex.withLock {
            if (cachedFilters.isEmpty()) {
                val form = document("$appUrl/series-list/").selectFirst("form[action*=series-list]")
                if (form != null) cachedFilters = buildFilters(form)
            }
        }
        getFilterList()
    }

    private fun buildFilters(form: Element): List<Filter<*>> = buildList {
        fun options(name: String) = form.select("input[name=\"$name\"]")
            .map { it.attr("value").trim() to (it.parent()?.text()?.trim().orEmpty().ifEmpty { it.attr("value").trim() }) }
            .filter { it.first.isNotEmpty() }
            .distinctBy { it.first.lowercase() }
        options("order").takeIf { it.isNotEmpty() }?.let {
            add(SelectFilter("الترتيب", "order", listOf("" to "الافتراضي") + it))
        }
        options("status").filter { it.first != "all" }.takeIf { it.isNotEmpty() }?.let {
            add(SelectFilter("الحالة", "status", listOf("" to "الكل") + it))
        }
        options("languages[]").takeIf { it.isNotEmpty() }?.let { add(CheckGroupFilter("اللغة", "languages[]", it)) }
        options("types[]").takeIf { it.isNotEmpty() }?.let { add(CheckGroupFilter("النوع", "types[]", it)) }
        options("genres[]").takeIf { it.isNotEmpty() }?.let { add(CheckGroupFilter("التصنيفات", "genres[]", it)) }
    }

    private class SelectFilter(name: String, val param: String, options: List<Pair<String, String>>) :
        Filter.Select<String>(name, options.map { it.second }.toTypedArray()) {
        val slugs: List<String> = options.map { it.first }
    }

    private class CheckGroupFilter(name: String, val param: String, options: List<Pair<String, String>>) :
        Filter.Group<Filter.CheckBox>(name, options.map { Filter.CheckBox(it.second) }) {
        val valueByLabel: Map<String, String> = options.associate { it.second to it.first }
    }

    // --- Novel ----------------------------------------------------------------

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val doc = document(novel.url)
        val hero = doc.selectFirst("header.series-hero")
        val favorite = doc.selectFirst("[data-favorite-toggle]")
        fun info(label: String) = hero?.select("dl.series-info-list > div")
            ?.firstOrNull { it.selectFirst("dt")?.text()?.contains(label) == true }
            ?.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val rating = doc.selectFirst("[data-series-rating]")?.text()?.let { RATING.find(it) }
        Novel(
            url = novel.url,
            title = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null } ?: novel.title,
            language = lang,
            thumbnailUrl = hero?.selectFirst("img.series-cover-poster")?.imageUrl() ?: novel.thumbnailUrl,
            author = info("المؤلف"),
            description = (doc.selectFirst(".description .description-full") ?: doc.selectFirst(".description"))
                ?.richDescription()?.takeIf { it.isNotBlank() },
            genres = doc.select(".tag-list a.tag-link").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = favorite?.attr("data-series-status").toNovelStatus(),
            rating = rating?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 0f },
            ratingCount = rating?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 },
            initialized = true,
        )
    }

    // The novel page lists every chapter, newest first.
    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        document(novel.url).select("a.chapter-row[href]").map { row ->
            Chapter(
                url = row.absUrl("href"),
                name = row.selectFirst("span")?.text()?.trim()?.ifEmpty { null } ?: row.text().trim(),
                chapterNumber = (row.attr("data-chapter-sort").ifEmpty { row.attr("data-chapter-number") }).toFloatOrNull() ?: -1f,
                uploadDate = row.selectFirst("time")?.text()?.trim()?.let(::parseDate) ?: 0L,
            )
        }.distinctBy { it.url }.reversed()
    }

    // --- Chapter --------------------------------------------------------------

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val content = document(chapter.url).selectFirst(".chapter-content")
            ?: throw IOException("No chapter content at ${chapter.url}")
        content.select("script, style, ins, .ad-placeholder, [class*=adsbygoogle]").remove()
        content.select("p, img").mapNotNull { el ->
            if (el.tagName() == "img") {
                el.imageUrl()?.let { PageContent.Image(it) }
            } else {
                val text = el.text().trim()
                if (text.isEmpty()) null else PageContent.Text(text, el.richHtml().takeIf { it != text })
            }
        }.mapIndexed { index, page -> NovelPage(index, page) }
    }

    // --- Helpers --------------------------------------------------------------

    private suspend fun document(url: String): Document {
        val response = get(url)
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code} for $url")
        }
        return response.asJsoup()
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src").map { absUrl(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun parseDate(value: String): Long? =
        runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)?.time }.getOrNull()

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("ongoing", ignoreCase = true) || contains("مستمر") -> NovelStatus.ONGOING
        contains("completed", ignoreCase = true) || contains("مكتمل") -> NovelStatus.COMPLETED
        contains("hiatus", ignoreCase = true) || contains("متوقف") -> NovelStatus.HIATUS
        contains("dropped", ignoreCase = true) || contains("cancel", ignoreCase = true) || contains("ملغ") -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    /**
     * Spaces requests to the site at least [MIN_INTERVAL_MS] apart and, on a
     * 429, waits out `Retry-After` (capped) and retries once.
     */
    private class PoliteInterceptor : Interceptor {
        private val lock = Any()
        private var nextAt = 0L

        override fun intercept(chain: Interceptor.Chain): Response {
            if (chain.request().url.host.endsWith(SITE_HOST)) awaitTurn()
            val response = chain.proceed(chain.request())
            if (response.code != 429) return response
            val waitMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                ?.coerceIn(MIN_INTERVAL_MS, MAX_RETRY_WAIT_MS) ?: DEFAULT_RETRY_WAIT_MS
            response.close()
            Thread.sleep(waitMs)
            awaitTurn()
            return chain.proceed(chain.request())
        }

        private fun awaitTurn() {
            val wait = synchronized(lock) {
                val now = System.currentTimeMillis()
                val at = maxOf(now, nextAt)
                nextAt = at + MIN_INTERVAL_MS
                at - now
            }
            if (wait > 0) Thread.sleep(wait)
        }
    }

    private companion object {
        const val SITE_HOST = "novelsparadise.site"
        const val MIN_INTERVAL_MS = 700L
        const val DEFAULT_RETRY_WAIT_MS = 3_000L
        const val MAX_RETRY_WAIT_MS = 10_000L
        val RATING = Regex("""★\s*([\d.]+)[\s\S]*?(\d+)\s*تقييم""")
    }
}
