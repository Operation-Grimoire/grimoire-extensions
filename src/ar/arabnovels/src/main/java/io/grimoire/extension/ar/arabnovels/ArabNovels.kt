package io.grimoire.extension.ar.arabnovels

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.FilterSource
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.time.Instant

/**
 * Arab Novels is a Next.js site that server-renders every page, so plain HTTP
 * is enough. The novel page only renders the newest chapters as links; the
 * complete list travels in the React Server Components payload
 * (`self.__next_f.push`), which [chapters] reads instead.
 */
@SourceInfo(name = "Arab Novels", lang = Language.AR, baseUrl = "https://arabnovels.com", versionCode = 1)
class ArabNovels :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    ChapterListSource,
    PageListSource {

    override val name = "Arab Novels"
    override val lang = Language.AR
    override val baseUrl = "https://arabnovels.com"

    private suspend fun document(url: HttpUrl): Document = withContext(Dispatchers.IO) {
        val target = requireNotNull(sourceUrl(url.toString())) { "Arab Novels: invalid source URL" }
        get(target).use { response ->
            if (!response.isSuccessful) throw IOException("Arab Novels: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Arab Novels: empty response")
            Jsoup.parse(body, response.request.url.toString())
        }
    }

    // --- Catalog ----------------------------------------------------------------

    override suspend fun getPopularNovels(page: Int): List<Novel> = catalog(page, sort = "popular")

    override suspend fun getLatestUpdates(page: Int): List<Novel> = catalog(page, sort = "updated")

    override val supportsSearchWithFilters: Boolean = true

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> {
        val select = filters.filterIsInstance<ParamFilter>().associate { it.param to it.value }
        return catalog(page, sort = select["sort"], status = select["status"], query = query.trim().ifEmpty { null })
    }

    private suspend fun catalog(page: Int, sort: String?, status: String? = null, query: String? = null): List<Novel> {
        require(page > 0)
        // Pages past the end render an empty grid, which ends paging.
        val url = "$baseUrl/novels".toHttpUrl().newBuilder().apply {
            query?.let { addQueryParameter("q", it) }
            sort?.let { addQueryParameter("sort", it) }
            status?.let { addQueryParameter("status", it) }
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return cards(document(url))
    }

    internal fun cards(document: Document): List<Novel> =
        document.select("article > a[href]").mapNotNull { card ->
            val url = sourceUrl(card.absUrl("href"))?.takeIf { novelSlug(it) != null } ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            Novel(url = url, title = title, language = lang, thumbnailUrl = cover(card.selectFirst("img")?.absUrl("src")))
        }.distinctBy { it.url }

    // --- Filters ----------------------------------------------------------------

    override fun getFilterList(): List<Filter<*>> = listOf(
        ParamFilter(
            "الترتيب",
            "sort",
            listOf("updated" to "آخر تحديث", "popular" to "الأكثر قراءة", "newest" to "الأحدث إضافة", "title" to "الاسم (أ-ي)"),
        ),
        ParamFilter(
            "الحالة",
            "status",
            listOf("" to "الكل", "ONGOING" to "مستمرة", "COMPLETED" to "مكتملة", "HIATUS" to "متوقفة"),
        ),
    )

    private class ParamFilter(name: String, val param: String, private val options: List<Pair<String, String>>) :
        Filter.Select<String>(name, options.map { it.second }.toTypedArray()) {
        val value: String? get() = options.getOrNull(state)?.first?.ifEmpty { null }
    }

    // --- Novel ------------------------------------------------------------------

    override suspend fun getNovelDetails(novel: Novel): Novel = details(document(novelUrl(novel.url)), novel)

    internal fun details(document: Document, novel: Novel): Novel {
        val book = document.select("script[type=application/ld+json]")
            .mapNotNull { runCatching { JSONObject(it.data()) }.getOrNull() }
            .firstOrNull { it.optString("@type") == "Book" }
        val title = book?.optString("name")?.trim()?.ifEmpty { null }
            ?: document.selectFirst("article h1")?.text()?.trim()?.ifEmpty { null }
            ?: throw IOException("Arab Novels: novel details unavailable")
        val genres = book?.optJSONArray("genre")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).trim().ifEmpty { null } }
        }.orEmpty()
        val status = document.select("section[aria-labelledby=chapters-heading] p")
            .firstOrNull { it.text().startsWith("الحالة") }?.text()
        return novel.copy(
            title = title,
            author = book?.optJSONObject("author")?.optString("name")?.trim()?.ifEmpty { null } ?: novel.author,
            description = book?.optString("description")?.trim()?.ifEmpty { null } ?: novel.description,
            thumbnailUrl = cover(book?.optString("image")) ?: novel.thumbnailUrl,
            genres = genres,
            status = when {
                status == null -> NovelStatus.UNKNOWN
                "مستمرة" in status -> NovelStatus.ONGOING
                "مكتملة" in status -> NovelStatus.COMPLETED
                "متوقفة" in status -> NovelStatus.HIATUS
                else -> NovelStatus.UNKNOWN
            },
            initialized = true,
        )
    }

    // --- Chapters ---------------------------------------------------------------

    override suspend fun getChapterList(novel: Novel): List<Chapter> {
        val url = novelUrl(novel.url)
        return chapters(document(url), url.toString())
    }

    internal fun chapters(document: Document, novelUrl: String): List<Chapter> {
        val slug = requireNotNull(novelSlug(novelUrl)) { "Arab Novels: invalid novel URL" }
        val flight = flightData(document)
        val marker = "\"slug\":${JSONObject.quote(slug)},\"chapters\":"
        val start = flight.indexOf(marker).takeIf { it >= 0 }
            ?: throw IOException("Arab Novels: chapter list unavailable")
        val rows = JSONTokener(flight.substring(start + marker.length)).nextValue() as? JSONArray
            ?: throw IOException("Arab Novels: chapter list unavailable")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val number = row.optDouble("number").takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val label = formatNumber(number)
            val title = row.optString("title").trim()
            Chapter(
                url = "$baseUrl/read/$slug/$label",
                name = if (title.isEmpty()) "الفصل $label" else "الفصل $label: $title",
                uploadDate = runCatching { Instant.parse(row.optString("publishedAt")).toEpochMilli() }.getOrDefault(0L),
                chapterNumber = number.toFloat(),
                locked = row.optString("access", "FREE") != "FREE",
            )
        }.distinctBy { it.url }.sortedBy { it.chapterNumber }
    }

    /** Concatenates the RSC payload chunks streamed through `self.__next_f.push([1, "..."])`. */
    private fun flightData(document: Document): String = buildString {
        for (script in document.select("script:not([src])")) {
            val data = script.data().trim()
            if (!data.startsWith(FLIGHT_PREFIX) || !data.endsWith(")")) continue
            val chunk = runCatching { JSONArray(data.substring(FLIGHT_PREFIX.length, data.length - 1)) }.getOrNull()
            if (chunk?.optInt(0) == 1) append(chunk.optString(1))
        }
    }

    // --- Reader -----------------------------------------------------------------

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> {
        if (chapter.locked) throw IOException("Arab Novels: this chapter is locked")
        val url = requireNotNull(sourceUrl(chapter.url)?.toHttpUrl()) { "Arab Novels: invalid chapter URL" }
        return pages(document(url))
    }

    internal fun pages(document: Document): List<NovelPage> {
        val body = document.selectFirst("#chapter-body")?.clone()
            ?: throw IOException("Arab Novels: chapter text unavailable")
        body.select("script, style, noscript, [hidden], [aria-hidden=true]").remove()
        val rich = body.richDescription()
        val plain = Jsoup.parseBodyFragment(rich).body()
        plain.select("br").forEach { it.replaceWith(TextNode("\n")) }
        val text = plain.wholeText().trim()
        if (text.isBlank()) throw IOException("Arab Novels: chapter text unavailable")
        return listOf(NovelPage(0, PageContent.Text(text, rich)))
    }

    // --- URLs -------------------------------------------------------------------

    /** Covers are served through `/_next/image?url=/media/covers/...`; unwrap to the original file. */
    private fun cover(src: String?): String? {
        val url = src?.toHttpUrlOrNull() ?: return null
        val original = if (url.encodedPath == "/_next/image") url.queryParameter("url")?.let { url.resolve(it) } else url
        return original?.takeIf { it.isHttps }?.toString()
    }

    private fun novelUrl(url: String): HttpUrl {
        val slug = requireNotNull(sourceUrl(url)?.let(::novelSlug)) { "Arab Novels: invalid novel URL" }
        return "$baseUrl/novels/$slug".toHttpUrl()
    }

    private fun novelSlug(url: String): String? =
        url.toHttpUrlOrNull()?.pathSegments?.takeIf { it.size == 2 && it[0] == "novels" && it[1].isNotEmpty() }?.get(1)

    private fun sourceUrl(url: String): String? =
        url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "arabnovels.com" && it.port == 443 }?.toString()

    private fun formatNumber(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toBigDecimal().stripTrailingZeros().toPlainString()

    private companion object {
        const val FLIGHT_PREFIX = "self.__next_f.push("
    }
}
