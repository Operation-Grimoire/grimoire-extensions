package io.grimoire.extension.ar.novltime

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.ChapterPage
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.CursorPaginatedSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import java.time.Instant

@SourceInfo(name = "NovlTime", lang = Language.AR, baseUrl = "https://novltime.com", versionCode = 3)
open class NovlTime : HttpSource(), PopularSource, LatestSource, SearchSource, CursorPaginatedSource, PageListSource {
    override val name = "NovlTime"
    override val lang = Language.AR
    override val baseUrl = "https://novltime.com"

    private val api = "$baseUrl/api/v1".toHttpUrl()
    private val cursorMutex = Mutex()
    private val catalogCursors = object : java.util.LinkedHashMap<String, MutableMap<Int, Cursor>>(
        MAX_CATALOG_CACHE_SIZE, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, Cursor>>): Boolean =
            size > MAX_CATALOG_CACHE_SIZE
    }
    private val novelIdMutex = Mutex()
    private val novelIds = mutableMapOf<String, String>()
    protected open val retryDelayMs: Long = RETRY_DELAY_MS

    private class Cursor(val value: String?)
    internal data class CatalogPage(val novels: List<Novel>, val hasMore: Boolean, val nextCursor: String?)

    private suspend fun getWithRetries(url: String): Response {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val response = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
            }
            if (response.code !in RETRYABLE_HTTP_CODES || attempt == MAX_ATTEMPTS - 1) return response
            response.close()
            delay(retryDelayMs * (attempt + 1))
        }
        error("NovlTime: request failed")
    }

    private suspend fun json(url: HttpUrl): JSONObject = getWithRetries(url.toString()).use { response ->
        check(response.isSuccessful) { "NovlTime: HTTP ${response.code} for $url" }
        val result = JSONObject(response.body?.string().orEmpty())
        check(result.optBoolean("success", true)) {
            "NovlTime: ${result.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "request failed" }}"
        }
        result
    }

    override suspend fun getPopularNovels(page: Int): List<Novel> = catalog("popular", null, page)

    // This is the site's finite recent-release feed, not the newest-additions archive.
    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        if (page > 1) return@withContext emptyList()
        val url = api.newBuilder().addPathSegment("novels").addPathSegment("latest-updates")
            .addQueryParameter("limit", CATALOG_PAGE_SIZE.toString()).build()
        json(url).getJSONArray("data").objects().map(::novel).distinctBy { it.url }
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        catalog("popular", query.trim().takeIf { it.isNotEmpty() }, page)

    private suspend fun catalog(sort: String, query: String?, page: Int): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val key = "$sort\u0000${query.orEmpty()}"
        val known = cursorMutex.withLock { catalogCursors[key]?.toMap().orEmpty() }
        val firstPage = known.keys.filter { it <= page }.maxOrNull() ?: 1
        var cursor = known[firstPage]?.value
        if (firstPage > 1 && cursor == null) return@withContext emptyList()
        var result: CatalogPage? = null
        for (currentPage in firstPage..page) {
            if (cursor == null && currentPage > 1) return@withContext emptyList()
            val pageResult = fetchCatalog(key, sort, query, currentPage, cursor)
            result = pageResult
            if (currentPage < page && !pageResult.hasMore) return@withContext emptyList()
            cursor = pageResult.nextCursor
        }
        result!!.novels
    }

    internal open suspend fun fetchCatalog(
        key: String,
        sort: String,
        query: String?,
        page: Int,
        cursor: String?,
    ): CatalogPage {
        val url = api.newBuilder().apply {
            if (query == null) {
                addPathSegment("novels")
                addQueryParameter("language", "ar")
                addQueryParameter("limit", CATALOG_PAGE_SIZE.toString())
            } else {
                addPathSegment("search")
                addQueryParameter("q", query)
                addQueryParameter("languages", "ar")
                addQueryParameter("perPage", CATALOG_PAGE_SIZE.toString())
            }
            addQueryParameter("sort", sort)
            if (cursor == null) addQueryParameter("page", "1") else addQueryParameter("cursor", cursor)
        }.build()
        val response = json(url)
        val data = response.get("data")
        val entries = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        val pagination = response.optJSONObject("meta") ?: data as? JSONObject
        val hasMore = pagination?.optBoolean("hasMore") == true
        val nextCursor = pagination?.string("nextCursor")?.takeIf { it.isNotEmpty() }
        check(!hasMore || nextCursor != null) { "NovlTime: catalog page $page has no continuation" }
        cursorMutex.withLock {
            val pages = catalogCursors.getOrPut(key) { mutableMapOf(1 to Cursor(null)) }
            pages.keys.removeAll { it > page + 1 }
            pages[page + 1] = Cursor(nextCursor)
        }
        return CatalogPage(entries.objects().map(::novel), hasMore, nextCursor)
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val data = json(novelApi(novelSlug(novel.url)).addPathSegment("view").build()).getJSONObject("data")
        val record = data.getJSONObject("novel")
        cacheNovelId(record)
        novel(record).copy(author = authorName(data.opt("author")) ?: authorName(record.opt("author")), initialized = true)
    }

    internal fun novel(record: JSONObject): Novel {
        val slug = record.string("slug").ifBlank { record.string("id") }
        require(slug.isNotEmpty()) { "NovlTime: novel has no slug" }
        val stats = record.optJSONObject("stats")
        val rating = stats?.optDouble("avgRating", Double.NaN)?.takeIf { it.isFinite() && it > 0 }?.toFloat()
        return Novel(
            url = baseUrl.toHttpUrl().newBuilder().addPathSegment("ar").addPathSegment("novel").addPathSegment(slug).build().toString(),
            title = record.string("title"),
            language = lang,
            thumbnailUrl = absoluteUrl(record.string("coverURL").ifBlank { record.string("coverUrl") }),
            author = authorName(record.opt("author")),
            description = record.string("descriptionLong").ifBlank {
                record.string("description").ifBlank { record.string("descriptionShort") }
            }.takeIf { it.isNotEmpty() },
            genres = record.optJSONArray("genres")?.strings()?.map { GENRES[it] ?: it }?.distinct().orEmpty(),
            status = when (record.string("completionStatus").ifBlank { record.string("status") }) {
                "PUBLISHED", "ONGOING" -> NovelStatus.ONGOING
                "COMPLETED" -> NovelStatus.COMPLETED
                "PAUSED", "HIATUS" -> NovelStatus.HIATUS
                "CANCELLED", "DROPPED" -> NovelStatus.CANCELLED
                else -> NovelStatus.UNKNOWN
            },
            rating = rating?.coerceAtMost(5f),
            ratingCount = stats?.optInt("ratingCount", -1)?.takeIf { it >= 0 },
        )
    }

    override suspend fun getChapterList(novel: Novel, cursor: String?): ChapterPage = withContext(Dispatchers.IO) {
        val slug = novelSlug(novel.url)
        val id = novelId(slug)
        val url = api.newBuilder().addPathSegment("novels").addPathSegment(id)
            .addPathSegment("chapters").addPathSegment("published")
            .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
            .addQueryParameter("sort", "asc").apply {
                if (cursor == null) addQueryParameter("page", "1") else addQueryParameter("cursor", cursor)
            }.build()
        val response = json(url)
        val chapters = response.getJSONArray("data").objects().map { chapter(it, slug) }
        check(chapters.map { it.url }.distinct().size == chapters.size) { "NovlTime: duplicate chapter URLs" }
        val meta = response.optJSONObject("meta")
        val hasMore = meta?.optBoolean("hasMore") == true
        val nextCursor = meta?.string("nextCursor")?.takeIf { it.isNotEmpty() }
        check(!hasMore || nextCursor != null) { "NovlTime: chapter page has no continuation" }
        ChapterPage(chapters.sortedBy { it.chapterNumber }, if (hasMore) nextCursor else null)
    }

    internal fun chapter(record: JSONObject, novelSlug: String): Chapter {
        val slug = record.string("slug")
        require(slug.isNotEmpty()) { "NovlTime: chapter has no slug" }
        val number = record.optDouble("order", Double.NaN).takeIf { it.isFinite() }?.toFloat() ?: -1f
        return Chapter(
            url = "$baseUrl/ar/novel/".toHttpUrl().newBuilder().addPathSegment(novelSlug)
                .addPathSegment("chapter").addPathSegment(slug).build().toString(),
            name = record.string("title").ifBlank { "الفصل ${record.opt("order")}" },
            chapterNumber = number,
            uploadDate = runCatching { Instant.parse(record.string("publishedAt")).toEpochMilli() }.getOrDefault(0),
            locked = record.optBoolean("isPremium") || record.optBoolean("isHidden") || record.optDouble("price", 0.0) > 0,
        )
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val html = getWithRetries(resolveUrl(chapter.url)).use { response ->
            check(response.isSuccessful) { "NovlTime: HTTP ${response.code} for ${chapter.url}" }
            response.body?.string().orEmpty()
        }
        parsePages(Jsoup.parse(html, chapter.url))
    }

    internal fun parsePages(document: Document): List<NovelPage> {
        val content = document.selectFirst("div.chapter-body") ?: error("NovlTime: chapter text unavailable")
        content.select("script, style, button, nav").remove()
        val pages = content.children().flatMap { element ->
            if (element.tagName() == "img") {
                element.absUrl("src").takeIf { it.isNotBlank() }
                    ?.let { listOf(NovelPage(0, PageContent.Image(it))) }
                    .orEmpty()
            } else {
                element.toPages()
            }
        }
        check(pages.isNotEmpty()) { "NovlTime: chapter text unavailable" }
        return pages.mapIndexed { index, page -> NovelPage(index, page.content) }
    }

    private fun Element.toPages(): List<NovelPage> {
        val pages = mutableListOf<NovelPage>()
        var segment = Element("p", baseUri())
        val open = mutableListOf<Pair<Element, Element>>()

        fun shallow(element: Element) = Element(element.tag(), baseUri(), element.attributes().clone())
        fun current(): Element = open.lastOrNull()?.second ?: segment

        fun flush() {
            val text = segment.text().trim()
            if (text.isNotEmpty()) {
                val html = segment.richHtml()
                pages += NovelPage(pages.size, PageContent.Text(text, html.takeIf { it != text }))
            }
            segment = Element("p", baseUri())
            open.replaceAll { (original, _) -> original to shallow(original) }
            var parent = segment
            open.forEach { (_, clone) ->
                parent.appendChild(clone)
                parent = clone
            }
        }

        fun walk(node: Node) {
            when {
                node is Element && node.tagName() == "img" -> {
                    flush()
                    node.absUrl("src").takeIf { it.isNotBlank() }?.let {
                        pages += NovelPage(pages.size, PageContent.Image(it))
                    }
                }
                node is Element -> {
                    val clone = shallow(node)
                    current().appendChild(clone)
                    open += node to clone
                    node.childNodes().forEach(::walk)
                    open.removeAt(open.lastIndex)
                }
                else -> current().appendChild(node.clone())
            }
        }

        childNodes().forEach(::walk)
        flush()
        return pages
    }

    private fun novelApi(slug: String): HttpUrl.Builder = api.newBuilder().addPathSegment("novels").addPathSegment(slug)

    private suspend fun novelId(slug: String): String {
        novelIdMutex.withLock { novelIds[slug]?.let { return it } }
        val record = json(novelApi(slug).build()).getJSONObject("data")
        val id = record.string("id")
        check(id.isNotEmpty()) { "NovlTime: novel has no id" }
        novelIdMutex.withLock { novelIds[slug] = id }
        return id
    }

    private suspend fun cacheNovelId(record: JSONObject) {
        val slug = record.string("slug")
        val id = record.string("id")
        if (slug.isNotEmpty() && id.isNotEmpty()) novelIdMutex.withLock { novelIds[slug] = id }
    }

    private fun novelSlug(url: String): String {
        val segments = url.toHttpUrl().pathSegments.filter { it.isNotEmpty() }
        val index = segments.indexOf("novel")
        require(index >= 0 && index + 1 < segments.size) { "NovlTime: invalid novel URL $url" }
        return segments[index + 1]
    }

    private fun absoluteUrl(url: String): String? =
        url.takeIf { it.isNotBlank() }?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }

    private fun authorName(value: Any?): String? = when (value) {
        is JSONObject -> value.string("displayName").ifBlank { value.string("username") }.takeIf { it.isNotEmpty() }
        is String -> value.trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key).trim()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }

    private companion object {
        const val CATALOG_PAGE_SIZE = 20
        const val MAX_CATALOG_CACHE_SIZE = 32
        const val CHAPTER_PAGE_SIZE = 200
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 500L
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val RETRYABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
        val GENRES = mapOf(
            "fantasy" to "فانتازيا",
            "litrpg" to "ليتآربيجي",
            "xianxia" to "شيانشيا",
            "wuxia" to "ووشيا",
            "romance" to "رومانسية",
            "action" to "أكشن",
            "adventure" to "مغامرة",
            "scifi" to "خيال علمي",
            "horror" to "رعب",
            "mystery" to "غموض",
            "system" to "نظام",
            "sliceoflife" to "شريحة من الحياة",
            "historical" to "تاريخي",
            "comedy" to "كوميديا",
            "drama" to "دراما",
            "urban" to "حضري",
            "thriller" to "إثارة",
        )
    }
}
