package io.grimoire.extension.en.novgo

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
import io.grimoire.api.source.http.ParsedHttpSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * NovGo kept the NovelFull URL scheme (`/most-popular`, `/genre/X`, `/{slug}.html`,
 * `?page=N` chapter pages) but moved to its own markup, so it no longer fits
 * [io.grimoire.extensions.lib.theme.NovelFullThemeSource].
 */
@SourceInfo(
    name = "NovGo",
    lang = Language.EN,
    baseUrl = "https://novgo.net",
    versionCode = 13,
)
class NovGo :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    PaginatedSource,
    PageListSource {

    override val name = "NovGo"
    override val lang = Language.EN
    override val baseUrl = "https://novgo.net"

    override suspend fun getPopularNovels(page: Int): List<Novel> = list("$baseUrl/most-popular?page=$page")

    override suspend fun getLatestUpdates(page: Int): List<Novel> = list("$baseUrl/latest-release-novel?page=$page")

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedSlug()
        val completedOnly = filters.filterIsInstance<StatusFilter>().firstOrNull()?.state == 1
        val url = when {
            genre != null -> "$baseUrl/genre/$genre?page=$page"
            completedOnly -> "$baseUrl/completed-novel?page=$page"
            else -> "$baseUrl/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        }
        return list(url)
    }

    private suspend fun list(url: String): List<Novel> = withContext(Dispatchers.IO) {
        get(url).asJsoup().select("div.li:has(h3.tit a)").map { it.toListNovel() }
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val document = get(resolveUrl(novel.url)).asJsoup()
        val info = document.selectFirst("div.m-imgtxt") ?: document
        Novel(
            url = document.location(),
            title = document.selectFirst("h1.tit")?.text()?.trim().orEmpty(),
            language = lang,
            thumbnailUrl = info.selectFirst("div.pic img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            // Authors are listed in both romanised and original script.
            author = info.select("a[href^=/author/]").map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct().joinToString(", ").takeIf { it.isNotEmpty() },
            description = document.selectFirst("#novel-summary-inner")?.richDescription()?.takeIf { it.isNotBlank() },
            genres = info.select("a[href^=/genre/]").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = info.selectFirst("span[title=Status] + div.right")?.text().toNovelStatus(),
            // `data-avg` is already on a 0..5 scale.
            rating = document.selectFirst("#novel-score")?.attr("data-avg")?.toFloatOrNull()?.coerceIn(0f, 5f),
            ratingCount = document.selectFirst(".vote-summary")?.text()
                ?.let { VOTES_REGEX.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() },
            initialized = true,
        )
    }

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        val doc = get("${resolveUrl(novel.url)}?page=$page").asJsoup()
        val list = doc.selectFirst("#list-chapter") ?: return@withContext emptyList()
        // The site clamps an out-of-range ?page to the last page and re-serves it,
        // so a naive page-walk would append the final chapters twice (duplicate
        // Compose keys crash the reader). Stop once the served page differs.
        val served = list.attr("data-current-page").toIntOrNull()
        if (served != null && served != page) {
            emptyList()
        } else {
            list.select("ul#idData li a[href]").map { it.toChapter() }
        }
    }

    override suspend fun getPageCount(novel: Novel): Int? = withContext(Dispatchers.IO) {
        get(resolveUrl(novel.url)).asJsoup()
            .selectFirst("#list-chapter")?.attr("data-total-page")?.toIntOrNull()
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        // Drop empty `<p></p>` spacers; keep paragraphs with visible text or an image.
        get(resolveUrl(chapter.url)).asJsoup()
            .select("#chapter-content p:matches(\\S), #chapter-content p:has(img)")
            .mapIndexed { index, element -> element.toPage(index) }
    }

    // FilterSource — the genre list is in every page's navigation.
    @Volatile
    private var loadedGenres: List<Pair<String, String>> = emptyList()

    override val hasDynamicFilters: Boolean = true

    override fun getFilterList(): List<Filter<*>> = listOf(StatusFilter(), GenreFilter(loadedGenres))

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        loadedGenres = parseGenres(get(baseUrl).asJsoup())
        getFilterList()
    }

    private fun parseGenres(doc: Document): List<Pair<String, String>> =
        doc.select("a[href^=/genre/]").mapNotNull { link ->
            val slug = link.attr("href").removePrefix("/genre/").substringBefore('?').trim()
            val label = link.attr("title").ifBlank { link.text() }.trim()
            if (slug.isEmpty() || label.isEmpty()) null else label to slug
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }

    private fun Element.toListNovel(): Novel {
        val link = selectFirst("h3.tit a")!!
        return Novel(
            url = link.attr("href"),
            title = link.attr("title").ifBlank { link.text() }.trim(),
            language = lang,
            thumbnailUrl = selectFirst("div.pic img")?.imageUrl(),
        )
    }

    private fun Element.toChapter() = Chapter(
        url = attr("href"),
        name = (selectFirst("span.chapter-text")?.text() ?: attr("title")).trim(),
    )

    // Light-novel titles embed illustrations as <p><img>; emit them as image pages.
    private fun Element.toPage(index: Int): NovelPage {
        val imageUrl = selectFirst("img")?.imageUrl()
        if (imageUrl != null) return NovelPage(index, PageContent.Image(imageUrl))
        val text = text()
        return NovelPage(index, PageContent.Text(text, richHtml().takeIf { it != text }))
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

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Any", "Completed only"))

    private class GenreFilter(private val genres: List<Pair<String, String>>) :
        Filter.Select<String>("Genre", (listOf(ANY) + genres.map { it.first }).toTypedArray()) {
        fun selectedSlug(): String? = genres.getOrNull(state - 1)?.second
    }

    private companion object {
        const val ANY = "Any"
        val VOTES_REGEX = Regex("""\(\s*([\d,]+)\s*votes?""")
    }
}
