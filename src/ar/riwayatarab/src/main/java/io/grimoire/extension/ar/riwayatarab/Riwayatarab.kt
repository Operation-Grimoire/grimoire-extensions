package io.grimoire.extension.ar.riwayatarab

import io.grimoire.api.model.filter.Filter
import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.AdultContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import java.math.BigDecimal
import java.time.Instant

@SourceInfo(
    name = "Riwayatarab", lang = Language.AR, baseUrl = "https://riwayatarab.com",
    versionCode = 1, adultContent = AdultContent.PARTIAL,
)
open class Riwayatarab : HttpSource(), PopularSource, LatestSource, SearchSource, PaginatedSource, PageListSource {
    override val name = "Riwayatarab"
    override val lang = Language.AR
    override val baseUrl = "https://riwayatarab.com"
    private val apiUrl = "https://api.riwayatarab.com/api/novels".toHttpUrl()

    private suspend fun text(url: String): String = get(url).use {
        check(it.isSuccessful) { "Riwayatarab: HTTP ${it.code}" }
        it.body?.string() ?: error("Riwayatarab: empty response")
    }

    override suspend fun getPopularNovels(page: Int): List<Novel> = library(page, "views")
    override suspend fun getLatestUpdates(page: Int): List<Novel> = library(page, "updated_at")
    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        library(page, "", query.trim())

    private suspend fun library(page: Int, sort: String, query: String = ""): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val url = apiUrl.newBuilder().addQueryParameter("page", "$page").addQueryParameter("limit", "24")
            .apply {
                if (sort.isNotEmpty()) addQueryParameter("sort", sort)
                if (query.isNotEmpty()) addQueryParameter("search", query)
            }.build()
        val data = JSONObject(text(url.toString()))
        val entries = data.getJSONArray("novels")
        (0 until entries.length()).map { novel(entries.getJSONObject(it)) }.distinctBy { it.url }
    }

    internal fun novel(record: JSONObject): Novel = Novel(
        // Slugs include both raw Arabic and already percent-encoded Arabic.
        url = baseUrl.toHttpUrl().newBuilder().addPathSegment("novel")
            .addEncodedPathSegment(record.getString("slug")).build().toString(),
        title = record.getString("title"), language = lang,
        thumbnailUrl = record.string("cover_image").takeIf { it.isNotBlank() },
        author = record.string("author").takeIf { it.isNotBlank() && it != "غير محدد" },
        description = record.string("description").takeIf { it.isNotBlank() }
            ?.let { Jsoup.parseBodyFragment(it).body().richDescription() },
        genres = buildList {
            record.string("category_name").takeIf { it.isNotBlank() }?.let(::add)
            record.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).map { tags.getString(it) }.filter { it.isNotBlank() }.forEach(::add)
            }
        }.distinct(),
        status = when (record.string("status")) {
            "ongoing" -> NovelStatus.ONGOING
            "completed" -> NovelStatus.COMPLETED
            "hiatus" -> NovelStatus.HIATUS
            "cancelled", "dropped" -> NovelStatus.CANCELLED
            else -> NovelStatus.UNKNOWN
        },
        rating = record.optDouble("rating", 0.0).takeIf { it.isFinite() && it > 0 }?.toFloat()?.coerceAtMost(5f),
    )

    private fun novelApi(url: String): HttpUrl {
        val segments = url.toHttpUrl().encodedPathSegments
        require(segments.size >= 2 && segments[0] == "novel" && segments[1].isNotBlank()) {
            "Riwayatarab: invalid novel URL"
        }
        return apiUrl.newBuilder().addEncodedPathSegment(segments[1]).build()
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        novel(JSONObject(text(novelApi(novel.url).toString()))).copy(initialized = true)
    }

    private suspend fun chapterData(novel: Novel, page: Int): JSONObject {
        require(page > 0)
        val url = novelApi(novel.url).newBuilder().addPathSegment("chapters")
            .addQueryParameter("page", "$page").addQueryParameter("limit", "50").build()
        return JSONObject(text(url.toString()))
    }

    // Refresh the count on each load so newly published chapters remain visible.
    override suspend fun getPageCount(novel: Novel): Int = withContext(Dispatchers.IO) {
        chapterData(novel, 1).getJSONObject("pagination").getInt("totalPages")
    }

    override suspend fun getChapterList(novel: Novel, page: Int): List<Chapter> = withContext(Dispatchers.IO) {
        parseChapters(chapterData(novel, page), novel.url, page)
    }

    internal fun parseChapters(data: JSONObject, novelUrl: String, page: Int): List<Chapter> {
        require(page > 0)
        val pagination = data.getJSONObject("pagination")
        check(pagination.getInt("currentPage") == page) { "Riwayatarab: unexpected chapter page" }
        val entries = data.getJSONArray("chapters")
        val expected = (pagination.getInt("totalCount").toLong() - (page - 1L) * 50).coerceIn(0, 50).toInt()
        check(entries.length() == expected) { "Riwayatarab: incomplete chapter list" }
        return (0 until entries.length()).map { index ->
            val chapter = entries.getJSONObject(index)
            val number = BigDecimal(chapter.get("chapter_number").toString()).stripTrailingZeros().toPlainString()
            Chapter(
                url = novelUrl.toHttpUrl().newBuilder().addPathSegment("chapter").addPathSegment(number).build().toString(),
                name = chapter.string("title").ifBlank { "الفصل $number" },
                chapterNumber = number.toFloat(),
                uploadDate = runCatching { Instant.parse(chapter.string("created_at")).toEpochMilli() }.getOrDefault(0),
            )
        }.also { chapters ->
            check(chapters.map { it.url }.distinct().size == chapters.size) { "Riwayatarab: duplicate chapter URLs" }
        }.sortedBy { it.chapterNumber }
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        parsePages(Jsoup.parse(text(chapter.url), chapter.url))
    }

    internal fun parsePages(doc: Document): List<NovelPage> {
        val content = doc.selectFirst(".chapter-content")?.clone() ?: error("Riwayatarab: chapter text unavailable")
        content.select("script, style, ins, .adsbygoogle").remove()
        // Most chapters contain a div with br-separated prose, not p elements.
        // Keep one rich block so formatting spanning a break remains intact.
        content.select("p, div, section, blockquote, h1, h2, h3, li").forEach {
            it.before("<br>")
            it.after("<br>")
        }
        val html = content.richHtml().replace(Regex("(?:<br>\\s*){3,}"), "<br><br>")
            .replace(Regex("^(?:\\s*<br>)+|(?:<br>\\s*)+$"), "").trim()
        val plain = Jsoup.parseBodyFragment(html)
        plain.select("br").forEach { it.replaceWith(TextNode("\n")) }
        val text = plain.body().wholeText().trim()
        check(text.isNotBlank()) { "Riwayatarab: chapter text unavailable" }
        return listOf(NovelPage(0, PageContent.Text(text, html)))
    }

    // Android's optString renders JSON null as the literal string "null".
    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)
}
