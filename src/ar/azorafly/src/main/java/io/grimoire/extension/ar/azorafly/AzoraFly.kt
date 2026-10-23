package io.grimoire.extension.ar.azorafly

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
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
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

@SourceInfo(name = "AzoraFly", lang = Language.AR, baseUrl = "https://azorafly.com", versionCode = 2)
class AzoraFly : HttpSource(), PopularSource, LatestSource, SearchSource, ChapterListSource, PageListSource {
    override val name = "AzoraFly"
    override val lang = Language.AR
    override val baseUrl = "https://azorafly.com"
    private val apiUrl = "https://api.azorafly.com/api"
    private val records = ConcurrentHashMap<String, JSONObject>()

    private suspend fun text(url: String): String = get(url).use {
        check(it.isSuccessful) { "AzoraFly: HTTP ${it.code}" }
        it.body?.string() ?: error("AzoraFly: empty response")
    }

    override suspend fun getPopularNovels(page: Int): List<Novel> = library("", page, "trending")
    override suspend fun getLatestUpdates(page: Int): List<Novel> = library("", page, "latest")
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> = library(query, page, "latest")

    private suspend fun library(query: String, page: Int, tag: String): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val url = "$apiUrl/posts".toHttpUrl().newBuilder().addQueryParameter("page", "$page")
            .addQueryParameter("perPage", "36").addQueryParameter("isNovel", "true")
            .addQueryParameter("searchTerm", query).addQueryParameter("tag", tag).build()
        val data = JSONObject(text(url.toString()))
        if ((page - 1) * 36 >= data.getInt("novelTotalCount")) emptyList()
        else parseLibrary(data)
    }

    internal fun parseLibrary(data: JSONObject): List<Novel> = data.getJSONArray("novelPosts").objects()
        .filter { it.optBoolean("isNovel") && it.string("seriesType") == "NOVEL" }
        .map { record ->
            val url = "$baseUrl/series/${record.getString("slug")}"
            records[url] = record
            Novel(
                url = url, title = record.getString("postTitle"), language = lang,
                thumbnailUrl = record.string("featuredImageCL").ifBlank { record.string("featuredImage") }.takeIf { it.isNotBlank() },
                genres = record.optJSONArray("genres")?.objects()?.map { it.getString("name") }.orEmpty(),
                status = when (record.string("seriesStatus")) {
                    "ONGOING" -> NovelStatus.ONGOING
                    "COMPLETED" -> NovelStatus.COMPLETED
                    else -> NovelStatus.UNKNOWN
                },
            )
        }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(text(novel.url), novel.url)
        val id = postId(doc, novel.url)
        val record = records[novel.url]
        // Keep the numeric identity even when opening a URL without a listing.
        if (record == null) records[novel.url] = JSONObject().put("id", id)
        Novel(
            url = novel.url, title = doc.selectFirst("h1[itemprop=name]")?.text()
                ?: error("AzoraFly: missing novel title"),
            language = lang,
            thumbnailUrl = doc.selectFirst("img[itemprop=image]")?.absUrl("src"),
            description = doc.selectFirst("[itemprop=description]")?.richDescription(),
            genres = doc.select("[itemprop=genre]").map { it.text() }.distinct(),
            status = status(doc, record),
            rating = rating(doc),
            ratingCount = doc.selectFirst("meta[itemprop=ratingCount]")?.attr("content")?.toIntOrNull(),
            initialized = true,
        )
    }

    internal fun status(doc: Document, record: JSONObject? = null): NovelStatus {
        val pageStatus = doc.select("section[itemprop=itemReviewed] span").map { it.text().trim() }
            .firstOrNull { it in setOf("ONGOING", "COMPLETED", "HIATUS", "CANCELLED") }
        return when (pageStatus ?: record?.string("seriesStatus")) {
            "ONGOING" -> NovelStatus.ONGOING
            "COMPLETED" -> NovelStatus.COMPLETED
            "HIATUS" -> NovelStatus.HIATUS
            "CANCELLED" -> NovelStatus.CANCELLED
            else -> NovelStatus.UNKNOWN
        }
    }

    internal fun rating(doc: Document): Float? {
        val value = doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toFloatOrNull() ?: return null
        val best = doc.selectFirst("meta[itemprop=bestRating]")?.attr("content")?.toFloatOrNull() ?: 10f
        return if (best > 0) (value * 5 / best).coerceIn(0f, 5f) else null
    }

    // TanStack SSR publishes the route's post identity before its content.
    // Match the exact slug so a related manga cannot supply the wrong post ID.
    internal fun postId(doc: Document, url: String): Long {
        records[url]?.optLong("id")?.takeIf { it > 0 }?.let { return it }
        val slug = url.toHttpUrl().pathSegments.filter(String::isNotEmpty).last()
        val pattern = Regex("post:\\${'$'}R\\[\\d+]=\\{id:(\\d+),slug:\"${Regex.escape(slug)}\"")
        return pattern.find(doc.html())?.groupValues?.get(1)?.toLong()
            ?: error("AzoraFly: missing novel identity")
    }

    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val id = records[novel.url]?.getLong("id") ?: postId(Jsoup.parse(text(novel.url), novel.url), novel.url)
        val data = JSONObject(text("$apiUrl/chapters?postId=$id&skip=0&take=all&order=desc"))
        val entries = data.getJSONObject("post").getJSONArray("chapters")
        check(entries.length() == data.getInt("totalChapterCount")) { "AzoraFly: incomplete chapter list" }
        parseChapters(entries, novel.url)
    }

    internal fun parseChapters(entries: JSONArray, url: String): List<Chapter> = entries.objects().map { ch ->
        val number = ch.optDouble("number", -1.0).toFloat()
        Chapter(
            url = "${url.trimEnd('/')}/${ch.getString("slug")}",
            name = ch.string("title").ifBlank { "الفصل ${ch.get("number")}" },
            chapterNumber = number,
            uploadDate = runCatching { Instant.parse(ch.string("createdAt")).toEpochMilli() }.getOrDefault(0),
            locked = ch.optBoolean("isLocked") || !ch.optBoolean("isAccessible", true)
                || ch.optBoolean("isPermanentlyLocked") || ch.optBoolean("isShortLinkLocked"),
        )
    }.distinctBy { it.url }.sortedBy { it.chapterNumber }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        check(!chapter.locked) { "AzoraFly: chapter requires access on the website" }
        val doc = Jsoup.parse(text(chapter.url), chapter.url)
        val paragraphs = doc.select(".novel-reader-content p").filter { it.text().isNotBlank() }
        check(paragraphs.isNotEmpty()) { "AzoraFly: chapter unavailable or locked; open it on the website" }
        paragraphs.mapIndexed { i, el -> NovelPage(i, PageContent.Text(el.text(), el.richHtml())) }
    }

    // Android org.json renders JSON null as the literal "null" in optString.
    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
