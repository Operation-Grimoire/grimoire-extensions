package io.grimoire.extension.ar.mtlarabic

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
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@SourceInfo(name = "MTLArabic", lang = Language.AR, baseUrl = "https://mtlarabic.com", versionCode = 2)
open class MTLArabic : HttpSource(), PopularSource, LatestSource, SearchSource, PaginatedSource, PageListSource {
    override val name = "MTLArabic"
    override val lang = Language.AR
    override val baseUrl = "https://mtlarabic.com"

    private val details = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val pageCounts = ConcurrentHashMap<String, Int>()
    private val completeLists = ConcurrentHashMap<String, Pair<Long, List<Chapter>>>()
    private val incompleteListAttempts = ConcurrentHashMap<String, Long>()

    private suspend fun document(url: String): Document = get(url).use {
        check(it.isSuccessful) { "MTLArabic: HTTP ${it.code}" }
        Jsoup.parse(it.body?.string().orEmpty(), url)
    }

    // The site's curated famous list is finite; its API has no popularity sort.
    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        if (page != 1) return@withContext emptyList()
        JSONArray(document(baseUrl).selectFirst("#__FAMOUS__")?.data()
            ?: error("MTLArabic: missing featured novels")).objects().map(::novel)
    }

    override suspend fun getLatestUpdates(page: Int): List<Novel> = library("", page)

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> = library(query, page)

    private suspend fun library(query: String, page: Int): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val url = "$baseUrl/novels".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "latest_update").build().toString()
        val data = embedded(document(url), "__NOVELS__")
        if (page > data.getJSONObject("pagination").getInt("totalPages")) emptyList()
        else data.getJSONArray("items").objects().map(::novel)
    }

    internal fun novel(data: JSONObject): Novel = Novel(
        url = "$baseUrl/${data.getString("slug")}",
        title = data.getString("name"), language = lang,
        thumbnailUrl = data.string("image").takeIf { it.isNotBlank() }?.let { "$baseUrl/images/novels/$it" },
        description = data.string("description").takeIf { it.isNotBlank() },
        genres = listOfNotNull(data.string("type").takeIf { it.isNotBlank() }),
        status = when {
            data.string("status").contains("مكتمل") -> NovelStatus.COMPLETED
            data.string("status").contains("مستمر") -> NovelStatus.ONGOING
            else -> NovelStatus.UNKNOWN
        },
    )

    private suspend fun detail(url: String, refresh: Boolean = false): JSONObject {
        if (!refresh) details[url]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        return embedded(document(url), "__NOVEL__").also {
            details[url] = System.currentTimeMillis() to it
            pageCounts.remove(url)
            completeLists.remove(url)
            incompleteListAttempts.remove(url)
        }
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        novel(detail(novel.url, refresh = true)).copy(initialized = true)
    }

    override suspend fun getPageCount(novel: Novel): Int = withContext(Dispatchers.IO) {
        val data = detail(novel.url)
        pageCounts[novel.url] ?: ((data.getInt("totalChapters") + 99) / 100)
            .also { pageCounts[novel.url] = it }
    }

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        require(page > 0)
        val complete = completeLists[novel.url]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.second
            ?: if (incompleteListAttempts[novel.url]?.let { System.currentTimeMillis() - it < 300_000 } == true) null
            else completeChapterList(novel.url)
        if (complete != null) return@withContext complete.drop((page - 1) * 100).take(100)
        if (page > getPageCount(novel)) return@withContext emptyList()
        val id = detail(novel.url).getLong("id")
        val url = "$baseUrl/api/novels/$id/chapters?page=$page&limit=100&sort=asc"
        val data = get(url).use {
            check(it.isSuccessful) { "MTLArabic: HTTP ${it.code} loading chapters" }
            JSONObject(it.body?.string().orEmpty())
        }
        pageCounts[novel.url] = data.getJSONObject("pagination").getInt("totalPages")
        parseChapters(data.getJSONArray("chapters"), novel.url).also {
            check(it.isNotEmpty()) { "MTLArabic: empty chapter page $page" }
        }
    }

    // Reader pages embed the entire TOC, while details only embed 100 rows.
    // One reader request avoids dozens of API calls for long novels.
    private suspend fun completeChapterList(url: String): List<Chapter>? {
        val data = detail(url)
        val initial = data.getJSONArray("chapters")
        if (initial.length() == 0) {
            check(data.getInt("totalChapters") == 0) { "MTLArabic: missing initial chapter list" }
            return emptyList()
        }
        val entries = if (initial.length() == data.getInt("totalChapters")) initial else {
            val first = parseChapters(initial, url).first()
            document(first.url).getElementById("__NOVEL__")?.data()?.let(::JSONObject)?.optJSONArray("chapters")
        }
        if (entries == null || entries.length() != data.getInt("totalChapters")) {
            incompleteListAttempts[url] = System.currentTimeMillis()
            return null
        }
        return parseChapters(entries, url).also {
            check(it.size == entries.length()) { "MTLArabic: duplicate chapter identities" }
            completeLists[url] = System.currentTimeMillis() to it
            pageCounts[url] = (it.size + 99) / 100
        }
    }

    internal fun parseChapters(entries: JSONArray, novelUrl: String): List<Chapter> = entries.objects().map { ch ->
        val special = ch.optBoolean("isSpecial")
        val route = if (special) "special-${ch.get("specialId")}" else ch.get("number").toString()
        Chapter(
            url = "${novelUrl.trimEnd('/')}/$route",
            name = ch.string("title").ifBlank { "الفصل ${ch.opt("number") ?: ""}" },
            chapterNumber = if (special) -1f else ch.optDouble("number", -1.0).toFloat(),
            uploadDate = runCatching { Instant.parse(ch.string("approvalDate")).toEpochMilli() }.getOrDefault(0),
        )
    }.distinctBy { it.url }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        parsePages(document(chapter.url))
    }

    internal fun parsePages(doc: Document): List<NovelPage> {
        val paragraphs = doc.select("#chapterText p").filter { it.text().isNotBlank() }
        check(paragraphs.isNotEmpty()) { "MTLArabic: chapter text unavailable" }
        return paragraphs.mapIndexed { i, el ->
            NovelPage(i, PageContent.Text(el.text(), el.richHtml()))
        }
    }

    private fun embedded(doc: Document, id: String): JSONObject =
        JSONObject(doc.getElementById(id)?.data() ?: error("MTLArabic: missing $id"))

    // Android org.json renders JSON null as the literal "null" in optString.
    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
