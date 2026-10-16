package io.grimoire.extension.ar.rewayatclub

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
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Rewayat Club (نادي الروايات) is a Nuxt app over a public JSON API at
 * api.rewayat.club. Novel and chapter URLs mirror the site's own routes
 * (`/novel/{slug}`, `/novel/{slug}/{number}`) so they open in a browser.
 */
@SourceInfo(name = "Rewayat Club", lang = Language.AR, baseUrl = "https://rewayat.club", versionCode = 2)
class RewayatClub :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    ChapterListSource,
    PageListSource {

    override val name = "Rewayat Club"
    override val lang = Language.AR
    override val baseUrl = "https://rewayat.club"

    private fun api(path: String): HttpUrl.Builder = "$API_URL/$path".toHttpUrl().newBuilder()

    private suspend fun getJson(url: HttpUrl): String = get(url.toString()).use { response ->
        check(response.isSuccessful) { "HTTP ${response.code} for $url" }
        response.body?.string().orEmpty()
    }

    // --- Listings -------------------------------------------------------------

    // The library's only server-side ordering is by chapter count, which is
    // also what the site's own library shows by default.
    override suspend fun getPopularNovels(page: Int): List<Novel> = library(page, query = null, type = null)

    // The weekly feed of new chapters, newest first; one entry per chapter.
    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        val url = api("chapters/weekly/list/").addQueryParameter("page", page.toString()).build()
        // Past the last page the API answers 404 rather than an empty page.
        val raw = runCatching { getJson(url) }.getOrElse { if (page > 1) return@withContext emptyList() else throw it }
        JSONObject(raw).optJSONArray("results").objects()
            .mapNotNull { it.optJSONObject("novel")?.toListNovel() }
            .distinctBy { it.url }
    }

    // The library API takes `search` and `type` together.
    override val supportsSearchWithFilters: Boolean = true

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> {
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.state?.takeIf { it > 0 }
        return library(page, query.trim().takeIf { it.isNotEmpty() }, type)
    }

    private suspend fun library(page: Int, query: String?, type: Int?): List<Novel> = withContext(Dispatchers.IO) {
        val url = api("novels/").apply {
            query?.let { addQueryParameter("search", it) }
            type?.let { addQueryParameter("type", it.toString()) }
            addQueryParameter("page", page.toString())
        }.build()
        // Past the last page the API answers 404 rather than an empty page.
        val raw = runCatching { getJson(url) }.getOrElse { if (page > 1) return@withContext emptyList() else throw it }
        JSONObject(raw).optJSONArray("results").objects().map { it.toListNovel() }
    }

    override fun getFilterList(): List<Filter<*>> = listOf(TypeFilter())

    // Values are the site's own library `type` parameter: 1 translated,
    // 2 original (Arabic-authored), 3 complete.
    private class TypeFilter : Filter.Select<String>("النوع", arrayOf("الكل", "مترجمة", "مؤلفة", "مكتملة"))

    // --- Novel ----------------------------------------------------------------

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val json = JSONObject(getJson(api("novels/${novel.slug()}/").build()))
        Novel(
            url = novelUrl(json.getString("slug")),
            title = json.title(),
            language = lang,
            thumbnailUrl = json.optString("poster_url").takeIf { it.isNotBlank() },
            author = json.optJSONArray("contributors").objects()
                .mapNotNull { it.optJSONObject("profile")?.optString("display_name")?.takeIf(String::isNotBlank) ?: it.optString("username").takeIf(String::isNotBlank) }
                .distinct().joinToString(", ").takeIf { it.isNotEmpty() },
            description = json.optString("about").trim().takeIf { it.isNotEmpty() }
                ?.let { Jsoup.parse(it).body().richHtml().ifBlank { it } },
            genres = json.optJSONArray("genre").objects().map { it.optString("arabic") }.filter { it.isNotBlank() },
            status = json.optString("get_novel_status").toNovelStatus(),
            initialized = true,
        )
    }

    // One call returns every chapter (number + title). Numbers are sparse:
    // announcements and art posts use large numbers like 130666 and sort last.
    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val slug = novel.slug()
        JSONArray(getJson(api("chapters/$slug/all/").build())).objects()
            .map { ch ->
                val number = ch.getInt("number")
                Chapter(
                    url = "$baseUrl/novel/$slug/$number",
                    name = ch.optString("title").trim().ifEmpty { "الفصل $number" },
                    chapterNumber = number.toFloat(),
                )
            }
            .distinctBy { it.url }
            .sortedBy { it.chapterNumber }
    }

    // --- Chapter --------------------------------------------------------------

    // A chapter can have several uploads (one per translator); the site shows the
    // first unless the URL names an uploader via ?username=.
    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val url = chapter.url.toHttpUrl()
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        require(segments.size >= 3 && segments[0] == "novel") { "Unexpected chapter URL: ${chapter.url}" }
        val uploads = JSONArray(getJson(api("chapters/${segments[1]}/${segments[2]}/new/").build())).objects()
        val upload = url.queryParameter("username")
            ?.let { name -> uploads.firstOrNull { it.optJSONObject("uploader")?.optString("username") == name } }
            ?: uploads.firstOrNull()
            ?: error("No content for ${chapter.url}")
        Jsoup.parseBodyFragment(upload.optString("content"), baseUrl).body()
            .select("p, img")
            .mapNotNull { el ->
                if (el.tagName() == "img") {
                    el.absUrl("src").takeIf { it.isNotBlank() }?.let { PageContent.Image(it) }
                } else {
                    val text = el.text().trim()
                    if (text.isEmpty()) null else PageContent.Text(text, el.richHtml().takeIf { it != text })
                }
            }
            .mapIndexed { index, content -> NovelPage(index, content) }
    }

    // --- Helpers --------------------------------------------------------------

    private fun novelUrl(slug: String) = "$baseUrl/novel/$slug"

    private fun Novel.slug(): String =
        url.toHttpUrl().pathSegments.filter { it.isNotEmpty() }.let { if (it.firstOrNull() == "novel") it.getOrNull(1) else it.lastOrNull() }
            ?: error("No slug in $url")

    private fun JSONObject.title(): String =
        optString("arabic").trim().ifEmpty { optString("english").trim() }

    private fun JSONObject.toListNovel() = Novel(
        url = novelUrl(getString("slug")),
        title = title(),
        language = lang,
        thumbnailUrl = optString("poster_url").ifBlank { optString("poster") }.takeIf { it.isNotBlank() },
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("مستمر") -> NovelStatus.ONGOING
        contains("مكتمل") -> NovelStatus.COMPLETED
        contains("متوقف") -> NovelStatus.HIATUS
        contains("ملغ") -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    private companion object {
        const val API_URL = "https://api.rewayat.club/api"
    }
}
