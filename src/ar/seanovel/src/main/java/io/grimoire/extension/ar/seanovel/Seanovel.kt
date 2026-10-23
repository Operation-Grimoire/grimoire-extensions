package io.grimoire.extension.ar.seanovel

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

@SourceInfo(name = "Seanovel", lang = Language.AR, baseUrl = "https://seanovel.org", versionCode = 3)
open class Seanovel : HttpSource(), LatestSource, SearchSource, PaginatedSource, PageListSource {
    override val name = "Seanovel"
    override val lang = Language.AR
    override val baseUrl = "https://seanovel.org"
    @Volatile private var cachedIndex: Pair<Long, List<JSONObject>>? = null
    private val pageCounts = ConcurrentHashMap<String, Int>()

    private suspend fun text(url: String): String {
        val request = Request.Builder().url(url).header("Referer", "$baseUrl/").build()
        return client.newCall(request).execute().use {
            check(it.isSuccessful) { "Seanovel: HTTP ${it.code}" }
            it.body?.string() ?: error("Seanovel: empty response")
        }
    }

    private suspend fun index(): List<JSONObject> {
        cachedIndex?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        return JSONArray(text("$baseUrl/api/search-index")).objects().also { cachedIndex = System.currentTimeMillis() to it }
    }

    internal fun novel(record: JSONObject): Novel {
        val slug = record.getString("slug")
        return Novel(
            url = "$baseUrl/novels/$slug", title = record.string("title_ar").ifBlank { record.getString("title_original") },
            language = lang,
            thumbnailUrl = "$baseUrl/api/novel/$slug/cover".toHttpUrl().newBuilder()
                .addQueryParameter("type", "webp").addQueryParameter("v", record.string("cover_version")).build().toString(),
            author = record.string("author").takeIf { it.isNotBlank() },
            genres = record.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            status = when (record.string("status")) {
                "completed" -> NovelStatus.COMPLETED
                "ongoing" -> NovelStatus.ONGOING
                else -> NovelStatus.UNKNOWN
            },
        )
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        // Match the site's Date-based updated sort, including timezone offsets.
        slice(index().sortedByDescending { updatedAt(it.string("last_updated")) }, page)
            .map(::novel)
    }

    // JavaScript Date treats zoned instants directly, naive date-times in the
    // device's timezone, and ISO date-only values at UTC midnight. All three
    // formats occur in the site's search index.
    internal fun updatedAt(value: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrDefault(0)

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        slice(index().filter { record ->
            listOf("title_ar", "title_original", "author").any { record.string(it).contains(q, ignoreCase = true) }
        }, page).map(::novel)
    }

    private fun <T> slice(items: List<T>, page: Int): List<T> {
        require(page > 0)
        return items.drop((page - 1) * 24).take(24)
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val slug = slug(novel.url)
        val record = index().firstOrNull { it.getString("slug") == slug } ?: error("Seanovel: novel not found")
        val doc = Jsoup.parse(text(novel.url), novel.url)
        val book = doc.select("script[type=application/ld+json]").flatMap { script ->
            val data = JSONObject(script.data())
            data.optJSONArray("@graph")?.objects() ?: listOf(data)
        }.firstOrNull { it.string("@type") == "Book" }
        novel(record).copy(
            description = book?.string("description")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content"),
            initialized = true,
        )
    }

    override suspend fun getPageCount(novel: Novel): Int = withContext(Dispatchers.IO) {
        pageCounts[novel.url] ?: chapterData(novel.url, 1).getInt("total").let { (it + 49) / 50 }
            .also { pageCounts[novel.url] = it }
    }

    private suspend fun chapterData(url: String, page: Int): JSONObject = JSONObject(
        text("$baseUrl/api/novel/${slug(url)}/chapters?offset=${(page - 1) * 50}&limit=50&sort=asc"),
    )

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        require(page > 0)
        pageCounts[novel.url]?.let { if (page > it) return@withContext emptyList() }
        val data = chapterData(novel.url, page)
        pageCounts[novel.url] = (data.getInt("total") + 49) / 50
        check(data.getInt("offset") == (page - 1) * 50) { "Seanovel: unexpected chapter page" }
        val chapters = parseChapters(data.getJSONArray("chapters"), novel.url)
        check(chapters.isNotEmpty() || !data.getBoolean("hasMore")) { "Seanovel: incomplete chapter list" }
        chapters
    }

    internal fun parseChapters(entries: JSONArray, url: String): List<Chapter> = entries.objects().map { ch ->
        val id = ch.get("id").toString()
        Chapter(
            url = "${url.trimEnd('/')}/chapters/$id",
            name = ch.string("title").ifBlank { "الفصل $id" },
            chapterNumber = Regex("(?:الفصل\\s*|chapter\\s*)(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .find(ch.string("title"))?.groupValues?.get(1)?.toFloatOrNull() ?: id.toFloatOrNull() ?: -1f,
            uploadDate = runCatching { Instant.parse(ch.string("date")).toEpochMilli() }.getOrDefault(0),
        )
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(text(chapter.url), chapter.url)
        val paragraphs = doc.select("article.reader-content p").filter { it.text().isNotBlank() }
        check(paragraphs.isNotEmpty()) { "Seanovel: chapter text unavailable" }
        paragraphs.mapIndexed { i, el -> NovelPage(i, PageContent.Text(el.text(), el.richHtml())) }
    }

    private fun slug(url: String): String = url.toHttpUrl().pathSegments.filter(String::isNotEmpty).last()
    // Android org.json renders JSON null as the literal "null" in optString.
    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
