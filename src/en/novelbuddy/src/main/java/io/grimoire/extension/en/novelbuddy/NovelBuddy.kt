package io.grimoire.extension.en.novelbuddy

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
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.time.Instant

@SourceInfo(name = "NovelBuddy", lang = Language.EN, baseUrl = "https://novelbuddy.me", versionCode = 14)
open class NovelBuddy :
    HttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    FilterSource,
    ChapterListSource,
    PageListSource {

    override val name = "NovelBuddy"
    override val lang = Language.EN
    override val baseUrl = "https://novelbuddy.me"

    private val apiBase = "https://api.novelbuddy.me"

    private var loadedGenres: List<Pair<String, String>> = emptyList()

    override val hasDynamicFilters: Boolean = true

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        val items = nextDataPageProps(getBody("$baseUrl/popular?page=$page")).optJSONArray("items")
            ?: return@withContext emptyList()
        (0 until items.length()).map { itemToNovel(items.getJSONObject(it)) }
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        val items = nextDataPageProps(getBody("$baseUrl/latest?page=$page")).optJSONArray("items")
            ?: return@withContext emptyList()
        (0 until items.length()).map { itemToNovel(items.getJSONObject(it)) }
    }

    // JSON API when only a query is supplied, otherwise the site's /genres/<slug>
    // listing (the search API ignores genre params).
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val genreSlug = filters.filterIsInstance<GenreFilter>().firstOrNull()
                ?.let { it.slugs.getOrNull(it.state) }
                ?.takeIf { it.isNotEmpty() }
            val body = if (genreSlug != null) {
                getBody("$baseUrl/genres/$genreSlug?page=$page")
            } else {
                getBody("$apiBase/titles/search?q=${query.trim()}&page=$page&limit=24")
            }
            // /genres/<slug> serves HTML; /titles/search returns JSON.
            val items = if (body.trimStart().startsWith("{")) {
                JSONObject(body).optJSONObject("data")?.optJSONArray("items")
            } else {
                nextDataPageProps(body).optJSONArray("items")
            } ?: return@withContext emptyList()
            (0 until items.length()).map { itemToNovel(items.getJSONObject(it)) }
        }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val url = resolveUrl(novel.url)
        val manga = loadManga(url)
        val authorsArr = manga.optJSONArray("authors")
        val genresArr = manga.optJSONArray("genres")
        val ratingRaw = manga.optDouble("rating", Double.NaN)
        val rating = if (!ratingRaw.isNaN() && ratingRaw > 0) ratingRaw.toFloat() else null
        Novel(
            url = url,
            title = manga.getString("name"),
            language = lang,
            thumbnailUrl = manga.optString("cover").takeIf { it.isNotEmpty() },
            author = if (authorsArr != null && authorsArr.length() > 0) {
                (0 until authorsArr.length()).joinToString(", ") { authorsArr.getJSONObject(it).getString("name") }
            } else {
                null
            },
            description = manga.optString("summary").let { summaryHtml ->
                if (summaryHtml.isEmpty()) null
                else Jsoup.parse(summaryHtml).body().richDescription().takeIf { it.isNotBlank() }
            },
            genres = if (genresArr != null) {
                (0 until genresArr.length()).map { genresArr.getJSONObject(it).getString("name") }
            } else {
                emptyList()
            },
            status = manga.optString("status").toNovelStatus(),
            rating = rating,
            ratingCount = manga.optInt("ratingCount", 0).takeIf { it > 0 },
            initialized = true,
        )
    }

    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val manga = loadManga(resolveUrl(novel.url))
        val mangaId = manga.getString("id")
        val cv = manga.optString("cv")
        val slug = manga.optString("slug")
        val data = JSONObject(getBody("$apiBase/titles/$mangaId/chapters?cv=$cv"))
        if (data.opt("success") == false) throw IOException("NovelBuddy: chapter list is unavailable. Please try again.")
        val chapters = data.optJSONObject("data")?.optJSONArray("chapters")
            ?: throw IOException("NovelBuddy: chapter list is unavailable. Please try again.")
        (0 until chapters.length()).map { i ->
            val ch = chapters.getJSONObject(i)
            Chapter(
                // The API now returns the full chapter path; fall back to composing it
                // from the manga + chapter slug for resilience.
                url = ch.optString("url").ifEmpty { "/$slug/${ch.getString("slug")}" },
                name = ch.optString("name").ifEmpty { "Chapter ${ch.optString("number")}" },
                chapterNumber = ch.optDouble("number", -1.0).toFloat(),
                uploadDate = parseIsoDate(ch.optString("updated_at")),
            )
        }.reversed()
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val contentHtml = nextDataPageProps(getBody(resolveUrl(chapter.url)))
            .optJSONObject("initialChapter")
            ?.optString("content")
            ?: return@withContext emptyList()
        Jsoup.parse(contentHtml).select("p").mapIndexedNotNull { index, el ->
            val text = el.text().trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            NovelPage(index, PageContent.Text(text, el.richHtml().takeIf { it != text }))
        }
    }

    override fun getFilterList(): List<Filter<*>> = listOf(GenreFilter(loadedGenres))

    override suspend fun fetchFilterOptions(): List<Filter<*>> = withContext(Dispatchers.IO) {
        val items = JSONObject(getBody("$apiBase/genres")).optJSONObject("data")?.optJSONArray("items")
        loadedGenres = if (items != null) {
            (0 until items.length()).map {
                val g = items.getJSONObject(it)
                g.getString("name") to g.getString("slug")
            }
        } else {
            emptyList()
        }
        getFilterList()
    }

    private class GenreFilter(genres: List<Pair<String, String>>) : Filter.Select<String>(
        "Genre",
        (listOf(ANY) + genres.map { it.first }).toTypedArray(),
    ) {
        val slugs: Array<String> = (listOf("") + genres.map { it.second }).toTypedArray()
    }

    private suspend fun getBody(url: String): String = get(url).use { response ->
        if (!response.isSuccessful) throw IOException("NovelBuddy: HTTP ${response.code}")
        response.body?.string() ?: throw IOException("NovelBuddy: empty response")
    }

    private fun JSONObject.hasRequiredDetails(): Boolean =
        (opt("id") as? String)?.isNotBlank() == true && (opt("name") as? String)?.isNotBlank() == true

    private suspend fun loadManga(url: String): JSONObject {
        val props = nextDataPageProps(getBody(url))
        props.optJSONObject("initialManga")?.takeIf { it.hasRequiredDetails() }?.let { return it }
        // SSR can time out while the public endpoint used by the site still works.
        val slug = url.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }
            ?: throw IOException("NovelBuddy: invalid novel URL")
        val apiUrl = apiBase.toHttpUrl().newBuilder().addPathSegments("titles/by-slug")
            .addPathSegment(slug).addQueryParameter("include", "details").build()
        val response = try {
            JSONObject(getBody(apiUrl.toString()))
        } catch (e: JSONException) {
            throw IOException("NovelBuddy: invalid details response. Please try again.", e)
        }
        val manga = response.optJSONObject("data")?.optJSONObject("title")
        if (response.optBoolean("success") && manga != null && manga.hasRequiredDetails()) return manga
        val reason = (response.opt("message") as? String)?.takeIf { it.isNotBlank() }
            ?: (props.opt("initialError") as? String)?.takeIf { it.isNotBlank() }
            ?: "Novel details are unavailable. Please try again."
        throw IOException("NovelBuddy: $reason")
    }

    private fun nextDataPageProps(html: String): JSONObject {
        val data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()?.takeIf { it.isNotBlank() }
            ?: throw IOException("NovelBuddy: page data is unavailable. Please try again.")
        return try {
            JSONObject(data).getJSONObject("props").getJSONObject("pageProps")
        } catch (e: JSONException) {
            throw IOException("NovelBuddy: invalid page data. Please try again.", e)
        }
    }

    private fun itemToNovel(item: JSONObject) = Novel(
        url = "/${item.getString("slug")}",
        title = item.getString("name"),
        language = lang,
        thumbnailUrl = item.optString("cover").takeIf { it.isNotEmpty() },
    )

    // Chapter timestamps are ISO-8601 (e.g. "2025-05-12T06:03:41.000Z"); 0 when absent/unparseable.
    private fun parseIsoDate(value: String): Long =
        if (value.isEmpty()) 0L else runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun String.toNovelStatus() = when (lowercase()) {
        "ongoing" -> NovelStatus.ONGOING
        "completed" -> NovelStatus.COMPLETED
        "hiatus" -> NovelStatus.HIATUS
        "cancelled" -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    companion object {
        private const val ANY = "Any"
    }
}
