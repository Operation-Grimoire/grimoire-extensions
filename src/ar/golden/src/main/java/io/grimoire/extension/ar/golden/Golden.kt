package io.grimoire.extension.ar.golden

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
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.util.ArrayDeque

/** Golden is currently branded Dilar. Catalog search may be in read-only maintenance. */
@SourceInfo(name = "Golden", lang = Language.AR, baseUrl = "https://golden.rest", versionCode = 3)
open class Golden : HttpSource(), LatestSource, SearchSource, ChapterListSource, PageListSource {
    override val name = "Golden"
    override val lang = Language.AR
    override val baseUrl = "https://golden.rest"
    private val latestMutex = Mutex()
    private var latestCache: LatestCache? = null

    private class LatestCache {
        val createdAt = System.currentTimeMillis()
        val pages = mutableListOf<List<Novel>>()
        val pending = ArrayDeque<Novel>()
        val seen = mutableSetOf<String>()
        var nextReleasePage = 1
        var lastReleasePage = Int.MAX_VALUE
    }

    private suspend fun text(url: String): String = get(url).use {
        check(it.isSuccessful) { "Golden: HTTP ${it.code}" }
        it.body?.string() ?: error("Golden: empty response")
    }

    private fun props(doc: Document): JSONObject = JSONObject(
        doc.selectFirst("script.js-react-on-rails-component[data-component-name=HomeApp]")?.data()
            ?: error("Golden: page data unavailable"),
    )

    // Public release pages contain manga, repeated chapters and hidden entries.
    // Pack distinct novels in release order; a short/empty filtered backend page
    // is not the end of the feed. Its UI uses 50 raw releases per backend page.
    override suspend fun getLatestUpdates(page: Int): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        latestMutex.withLock {
            val cache = latestCache?.takeUnless { page == 1 && System.currentTimeMillis() - it.createdAt > 300_000 }
                ?: LatestCache().also { latestCache = it }
            while (cache.pages.size < page) {
                if (cache.pages.lastOrNull()?.isEmpty() == true) return@withLock emptyList()
                var fetched = 0
                while (cache.pending.size < 12 && cache.nextReleasePage <= cache.lastReleasePage && fetched < 20) {
                    val data = JSONObject(text("$baseUrl/api/releases?page=${cache.nextReleasePage}"))
                    val total = data.getInt("totalReleases").also { check(it >= 0) }
                    val novels = novelsInReleases(data.getJSONArray("releases"))
                    cache.lastReleasePage = (total + 49) / 50
                    cache.nextReleasePage++
                    fetched++
                    novels.forEach { novel -> if (cache.seen.add(id(novel.url))) cache.pending.addLast(novel) }
                }
                // Bound a sparse archive scan without falsely declaring EOF.
                // A retry resumes at the next unfetched release page.
                check(cache.pending.isNotEmpty() || cache.nextReleasePage > cache.lastReleasePage) {
                    "Golden: no new novels in 20 release pages; retry to continue"
                }
                val result = cache.pending.take(12)
                repeat(result.size) { cache.pending.removeFirst() }
                cache.pages.add(result)
            }
            cache.pages[page - 1]
        }
    }

    internal fun novelsInReleases(entries: JSONArray): List<Novel> = entries.objects()
        .mapNotNull { it.optJSONObject("manga") }.filter { it.optBoolean("is_novel") }
        .map(::novel).distinctBy { it.url }

    internal fun novel(data: JSONObject): Novel {
        check(data.getBoolean("is_novel")) { "Golden: this title is not a prose novel" }
        val id = data.getLong("id")
        return Novel(
            url = "$baseUrl/mangas/$id/${slug(data.getString("title"))}",
            title = data.string("arabic_title").takeUnless { it.isBlank() || it == "null" } ?: data.getString("title"),
            language = lang,
            thumbnailUrl = data.string("cover").takeIf { it.isNotBlank() && it != "null" }
                ?.let { "$baseUrl/uploads/manga/cover/$id/large_$it" },
            description = data.string("summary").takeIf { it.isNotBlank() }
                ?.let { Jsoup.parseBodyFragment(it).body().richDescription() },
            author = data.optJSONArray("authors")?.objects()?.map { it.getString("name") }?.distinct()?.joinToString(", ")?.takeIf { it.isNotBlank() },
            genres = data.optJSONArray("categories")?.objects()?.map { it.getString("name") }?.distinct().orEmpty(),
            status = when (data.optInt("translation_status", -1)) {
                0 -> NovelStatus.COMPLETED
                1 -> NovelStatus.ONGOING
                else -> NovelStatus.UNKNOWN
            },
            rating = data.string("rating").toFloatOrNull(),
        )
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> = withContext(Dispatchers.IO) {
        require(page > 0)
        val doc = Jsoup.parse(text("$baseUrl/mangas"), baseUrl)
        val body = JSONObject().put("title", query).put("novel", true).put("oneshot", false).put("page", page)
        for (key in listOf("manga_types", "story_status", "translation_status", "categories")) {
            body.put(key, JSONObject().put("include", JSONArray()).put("exclude", JSONArray()))
        }
        body.put("chapters", JSONObject().put("min", "").put("max", ""))
        body.put("dates", JSONObject().put("start", JSONObject.NULL).put("end", JSONObject.NULL))
        val request = Request.Builder().url("$baseUrl/api/mangas/search").header("Referer", "$baseUrl/mangas")
            .apply { doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.let { header("X-CSRF-Token", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.code != 503) { "Golden: site search is temporarily unavailable (read-only maintenance)" }
            check(response.isSuccessful) { "Golden: search HTTP ${response.code}" }
            val data = JSONObject(response.body?.string().orEmpty())
            check(data.optBoolean("success", true)) { "Golden: site search failed" }
            data.getJSONArray("mangas").objects().filter { it.optBoolean("is_novel") }.map(::novel)
        }
    }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val data = JSONObject(text("$baseUrl/api/mangas/${id(novel.url)}")).getJSONObject("mangaData")
        novel(data).copy(initialized = true)
    }

    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        val data = JSONObject(text("$baseUrl/api/mangas/${id(novel.url)}/releases"))
        parseChapters(data.getJSONArray("releases"), novel.url)
    }

    internal fun parseChapters(entries: JSONArray, novelUrl: String): List<Chapter> = entries.objects().map { ch ->
        val number = ch.get("chapter").toString()
        val teams = ch.getJSONArray("teams").objects().map { it.getString("name") }
        // The frontend uses its permissive slugify(name, true) for team paths.
        val teamPath = teams.joinToString("&joint&") { it.lowercase().replace(Regex("\\s+"), "-").replace(Regex("-+"), "-").trim('-').replace('/', '-') }
        val url = "${novelUrl.trimEnd('/')}/$number".toHttpUrl().newBuilder().apply {
            if (teamPath.isNotBlank()) addPathSegment(teamPath)
        }.build().toString()
        Chapter(
            url = url, name = ch.string("title").ifBlank { "الفصل $number" },
            chapterNumber = ch.optDouble("chapter", -1.0).toFloat(),
            translator = teams.joinToString(" & "), uploadDate = ch.optLong("time_stamp") * 1000,
            locked = ch.optInt("link_control") != 0 || ch.optBoolean("has_rev_link"),
        )
    }.distinctBy { it.url }.sortedBy { it.chapterNumber }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        check(!chapter.locked) { "Golden: chapter requires access on the website" }
        parsePages(props(Jsoup.parse(text(chapter.url), chapter.url)))
    }

    internal fun parsePages(data: JSONObject): List<NovelPage> {
        val reader = data.getJSONObject("readerDataAction").getJSONObject("readerData")
        check(reader.getJSONObject("manga").optBoolean("is_novel")) { "Golden: chapter is not prose" }
        val release = reader.getJSONObject("release")
        check(release.optBoolean("showable") && !release.optBoolean("deleted") && release.optInt("link_control") == 0
            && !release.optBoolean("has_rev_link")) { "Golden: chapter unavailable or restricted" }
        // For novels this field is already plaintext, even when encoded=true.
        val paragraphs = release.getString("content").split(Regex("\\r?\\n+")).map(String::trim).filter(String::isNotEmpty)
        check(paragraphs.isNotEmpty()) { "Golden: empty chapter text" }
        return paragraphs.mapIndexed { i, paragraph -> NovelPage(i, PageContent.Text(paragraph)) }
    }

    private fun id(url: String): String = url.toHttpUrl().pathSegments[1].also { require(it.toLongOrNull() != null) }
    private fun slug(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("\\s+"), "-").replace('&'.toString(), "-and-")
        .replace(Regex("[^a-z0-9_-]"), "").replace(Regex("-+"), "-").trim('-').ifBlank { "data" }
    // Android org.json renders JSON null as the literal "null" in optString.
    private fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
