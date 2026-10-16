package io.grimoire.extension.ar.cenele

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Chapter
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.util.richHtml
import io.grimoire.extensions.lib.theme.WPNovelsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@SourceInfo(name = "Cenele", lang = Language.AR, baseUrl = "https://cenele.com", versionCode = 6)
class Cenele : WPNovelsSource() {
    override val name = "Cenele"
    override val lang = Language.AR
    override val baseUrl = "https://cenele.com"
    override val novelPathSegment = "cont"

    // Cenele's "novelhub" child theme replaced Madara's listing and novel-page
    // markup (`nhv-*` classes). The base class still handles search, filters and
    // chapter pages, which kept the stock structure.

    // The archive uses `article.nhv-library-card` cards and paginates by path
    // (`/cont/page/N/`); the base `?paged=N` query now 301s to an empty page.
    override suspend fun getPopularNovels(page: Int): List<Novel> = archive("views", page)

    override suspend fun getLatestUpdates(page: Int): List<Novel> = archive("latest", page)

    private suspend fun archive(orderBy: String, page: Int): List<Novel> = withContext(Dispatchers.IO) {
        val path = if (page > 1) "$novelPathSegment/page/$page/" else "$novelPathSegment/"
        get("$baseUrl/$path?m_orderby=$orderBy").asJsoup()
            .select("article.nhv-library-card").mapNotNull { it.toCardNovel() }
    }

    private fun Element.toCardNovel(): Novel? {
        val link = selectFirst(".nhv-library-card__title a") ?: return null
        return Novel(
            url = link.attr("href"),
            title = link.text().trim(),
            language = lang,
            thumbnailUrl = selectFirst(".nhv-library-card__cover img")?.imageUrl(),
        )
    }

    override fun novelFromDocument(document: Document): Novel {
        val title = document.selectFirst("h1.nhv-novel-title") ?: return legacyNovelFromDocument(document)
        // The site-wide rating widget is out of 10; the model wants 0..5.
        val rating10 = document.selectFirst(".nhv-simple-rating__avg")?.text()?.trim()?.toFloatOrNull()
        return Novel(
            url = document.location(),
            title = title.text().trim(),
            language = lang,
            thumbnailUrl = document.selectFirst(".nhv-novel-cover img")?.imageUrl(),
            author = document.selectFirst(".nhv-novel-meta a[href*=/cont-author/]")?.text()?.trim(),
            description = document.selectFirst("div.nhv-novel-synopsis")?.synopsisHtml(),
            genres = document.select(".nhv-novel-genres a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = statusOf(document.selectFirst(".nhv-novel-status")),
            rating = rating10?.let { (it / 2f).coerceIn(0f, 5f) },
            // Counts use the Arabic thousands separator ("2٬680").
            ratingCount = document.selectFirst(".nhv-simple-rating__count")?.text()
                ?.filter { it in '0'..'9' }?.toIntOrNull(),
            initialized = true,
        )
    }

    // Older Cenele pages put the title in `.manga-title` rather than the
    // standard `.post-title` heading, so the base parse leaves it blank.
    private fun legacyNovelFromDocument(document: Document): Novel {
        val base = super.novelFromDocument(document)
        return if (base.title.isBlank()) {
            base.copy(title = document.selectFirst("div.manga-title")?.text()?.trim().orEmpty())
        } else {
            base
        }
    }

    // The novel page only embeds the latest 8 chapters; the full list comes from
    // a nonce-gated admin-ajax action. `volume=-1` spans every volume in reading
    // order, and the server caps each page at 100 rows.
    override suspend fun getChapterList(novel: Novel): List<Chapter> {
        val html = withContext(Dispatchers.IO) { get(resolveUrl(novel.url)).use { it.body?.string().orEmpty() } }
        val config = NOVEL_CONFIG_REGEX.find(html)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val postId = config?.optString("postId").orEmpty()
        val nonce = config?.optString("chaptersNonce").orEmpty()
        if (postId.isEmpty() || nonce.isEmpty()) return super.getChapterList(novel)

        return withContext(Dispatchers.IO) {
            val first = chaptersPage(postId, nonce, 1)
            val perPage = first.optInt("per_page", CHAPTERS_PER_PAGE).coerceAtLeast(1)
            val pageCount = (first.optInt("total", 0) + perPage - 1) / perPage
            val gate = Semaphore(MAX_CONCURRENT_PAGES)
            val rest = (2..pageCount).map { page ->
                async { gate.withPermit { chaptersPage(postId, nonce, page) } }
            }.awaitAll()
            (listOf(first) + rest)
                .flatMap { Jsoup.parse(it.optString("html"), baseUrl).select("li.wp-manga-chapter") }
                .map { chapterFromElement(it) }
                .distinctBy { it.url }
        }
    }

    private fun chaptersPage(postId: String, nonce: String, page: Int): JSONObject {
        val body = FormBody.Builder()
            .add("action", "nhv_manga_single_chapters_page")
            .add("nonce", nonce)
            .add("manga_id", postId)
            .add("volume", "-1")
            .add("page", page.toString())
            .add("per_page", CHAPTERS_PER_PAGE.toString())
            .add("order", "asc")
            .build()
        val request = Request.Builder().url("$baseUrl/wp-admin/admin-ajax.php").post(body).build()
        val raw = client.newCall(request).execute().use { it.body?.string().orEmpty() }
        val json = runCatching { JSONObject(raw) }.getOrNull()
        // Fail loudly rather than return a truncated list the host would diff
        // against the library.
        check(json?.optBoolean("success") == true) { "Cenele chapter list page $page failed" }
        return json!!
    }

    // Cenele injects anti-piracy promo paragraphs (each carrying a `cenele.com`
    // link) into chapter bodies, plus a VIP store `<aside>` inside the reading
    // area, and pads prose with `&nbsp;` spacer paragraphs. Drop all three and
    // re-index the remaining pages.
    override fun pagesFromDocument(document: Document): List<NovelPage> {
        document.select("aside.nhv-reader-store-promo").remove()
        return super.pagesFromDocument(document)
            .filterNot { page ->
                val text = (page.content as? PageContent.Text)?.text ?: return@filterNot false
                text.isBlank() || text.contains("cenele.com", ignoreCase = true)
            }
            .mapIndexed { index, page -> NovelPage(index, page.content) }
    }

    // The synopsis is authored as `<h3>`/`<h4>` blocks with the odd `<p>`;
    // [richDescription] keeps only the `<p>`s when any exist, so render every
    // block and join them as paragraphs.
    private fun Element.synopsisHtml(): String? {
        val blocks = children().map { it.richHtml() }.filter { it.isNotBlank() }
        return (if (blocks.isEmpty()) richHtml() else blocks.joinToString("<br><br>")).takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(): String? =
        attr("data-src").ifBlank { attr("src") }.takeIf { it.isNotBlank() && !it.startsWith("data:") }

    private fun statusOf(element: Element?): NovelStatus {
        val classes = element?.className().orEmpty()
        val text = element?.text().orEmpty()
        return when {
            "is-ongoing" in classes || "مستمر" in text -> NovelStatus.ONGOING
            "is-completed" in classes || "مكتمل" in text -> NovelStatus.COMPLETED
            "متوقف" in text -> NovelStatus.HIATUS
            "ملغ" in text -> NovelStatus.CANCELLED
            else -> NovelStatus.UNKNOWN
        }
    }

    private companion object {
        val NOVEL_CONFIG_REGEX = Regex("""nhvNovelV2\s*=\s*(\{.*?\});""")
        const val CHAPTERS_PER_PAGE = 100
        const val MAX_CONCURRENT_PAGES = 4
    }
}
