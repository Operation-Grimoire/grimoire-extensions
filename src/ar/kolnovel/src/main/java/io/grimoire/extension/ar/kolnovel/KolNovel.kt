package io.grimoire.extension.ar.kolnovel

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
import io.grimoire.api.source.http.ParsedHttpSource
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.util.richDescription
import io.grimoire.api.util.richHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Kol Novel (ملوك الروايات), on the "lightnovel" WordPress theme. Two
 * anti-copy measures need undoing:
 * - some titles are joined by a symbol and tagged: `إرادة%أبدية%كول`;
 * - chapter bodies interleave decoy copies of their own paragraphs, hidden
 *   by randomly named classes declared in an inline stylesheet.
 */
@SourceInfo(name = "Kol Novel", lang = Language.AR, baseUrl = "https://kolnovel.com", versionCode = 1)
class KolNovel :
    ParsedHttpSource(),
    PopularSource,
    LatestSource,
    SearchSource,
    ChapterListSource,
    PageListSource {

    override val name = "Kol Novel"
    override val lang = Language.AR
    override val baseUrl = "https://kolnovel.com"

    override suspend fun getPopularNovels(page: Int): List<Novel> = series("popular", page)

    override suspend fun getLatestUpdates(page: Int): List<Novel> = series("update", page)

    private suspend fun series(order: String, page: Int): List<Novel> = withContext(Dispatchers.IO) {
        get("$baseUrl/series/?order=$order&page=$page").asJsoup().toNovels()
    }

    override suspend fun searchNovels(query: String, page: Int, filters: List<Filter<*>>): List<Novel> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
                if (page > 1) addPathSegments("page/$page/")
                addQueryParameter("s", query.trim())
            }.build()
            get(url.toString()).asJsoup().toNovels()
        }

    private fun Document.toNovels(): List<Novel> = select("article.maindet").mapNotNull { card ->
        val link = card.selectFirst("h2 a[href], .mdthumb a[href]") ?: return@mapNotNull null
        Novel(
            url = link.absUrl("href"),
            title = (link.attr("title").ifBlank { link.text() }).cleanTitle(),
            language = lang,
            thumbnailUrl = card.selectFirst(".mdthumb img")?.imageUrl(),
        )
    }.distinctBy { it.url }

    override suspend fun getNovelDetails(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val doc = get(novel.url).asJsoup()
        fun info(label: String) = doc.select(".sertoauth .serl")
            .firstOrNull { it.selectFirst(".sername")?.text()?.contains(label) == true }
            ?.selectFirst(".serval")
        Novel(
            url = doc.location(),
            title = doc.selectFirst("h1.entry-title")?.text().orEmpty().cleanTitle(),
            language = lang,
            thumbnailUrl = doc.selectFirst(".sertothumb img, .sertoinfo img.wp-post-image")?.imageUrl(),
            author = info("الكاتب")?.select("a")?.joinToString(", ") { it.text().trim() }?.takeIf { it.isNotBlank() }
                ?: info("الكاتب")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            description = doc.selectFirst("[itemprop=description]")?.richDescription()?.takeIf { it.isNotBlank() },
            genres = doc.select(".sertogenre a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = doc.selectFirst(".sertostat span")?.text().toNovelStatus(),
            initialized = true,
        )
    }

    // One `.eplister` per volume, each newest-first; the reader wants oldest first.
    override suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        get(novel.url).asJsoup().select(".eplister li > a[href]").map { link ->
            val num = link.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val title = link.selectFirst(".epl-title")?.text()?.trim().orEmpty()
            val number = CHAPTER_NUMBER.find(num)?.groupValues?.get(1)?.toFloatOrNull()
                ?: CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull()
                ?: -1f
            Chapter(
                url = link.absUrl("href"),
                name = when {
                    title.contains("الفصل") || title.isEmpty() && num.isNotEmpty() -> title.ifEmpty { num }
                    number >= 0f -> "الفصل ${number.toChapterLabel()}: $title"
                    else -> title
                },
                chapterNumber = number,
                uploadDate = 0L,
            )
        }.distinctBy { it.url }.reversed()
    }

    override suspend fun getPageList(chapter: Chapter): List<NovelPage> = withContext(Dispatchers.IO) {
        val doc = get(chapter.url).asJsoup()
        val content = doc.selectFirst("#kol_content") ?: error("No chapter content at ${chapter.url}")
        // Drop the decoys: every class the page's own CSS hides.
        hiddenClasses(doc).forEach { cls -> content.getElementsByClass(cls).remove() }
        content.select("script, style, ins, .code-block, [id^=Top_ad]").remove()
        content.select("p, img").mapNotNull { el ->
            if (el.tagName() == "img") {
                el.imageUrl()?.let { PageContent.Image(it) }
            } else {
                val text = el.ownText().trim().ifEmpty { el.text().trim() }
                if (text.isEmpty()) null else PageContent.Text(el.text().trim(), el.richHtml().takeIf { it != el.text().trim() })
            }
        }.mapIndexed { index, page -> NovelPage(index, page) }
    }

    private fun hiddenClasses(doc: Document): Set<String> {
        val css = doc.select("style").joinToString("\n") { it.data() }
        return CSS_RULE.findAll(css)
            .filter { HIDING_DECLARATION.containsMatchIn(it.groupValues[2]) }
            .flatMap { CLASS_SELECTOR.findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
            .toSet()
    }

    private fun Element.imageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "src").map { absUrl(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    /** `إرادة%أبدية%كول` → `إرادة أبدية`; plain titles pass through. */
    private fun String.cleanTitle(): String {
        val trimmed = trim()
        val sep = OBFUSCATED_TITLE.matchEntire(trimmed)?.groupValues?.get(1) ?: return trimmed
        return trimmed.removeSuffix(sep + TITLE_TAG).split(sep).joinToString(" ") { it.trim() }.trim()
    }

    private fun Float.toChapterLabel(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun String?.toNovelStatus() = when {
        this == null -> NovelStatus.UNKNOWN
        contains("Ongoing", ignoreCase = true) || contains("مستمر") -> NovelStatus.ONGOING
        contains("Completed", ignoreCase = true) || contains("مكتمل") -> NovelStatus.COMPLETED
        contains("Hiatus", ignoreCase = true) || contains("متوقف") -> NovelStatus.HIATUS
        contains("Dropped", ignoreCase = true) || contains("ملغ") -> NovelStatus.CANCELLED
        else -> NovelStatus.UNKNOWN
    }

    private companion object {
        const val TITLE_TAG = "كول"
        val OBFUSCATED_TITLE = Regex("""^.+?([^\p{L}\p{N}\s])$TITLE_TAG$""")
        val CHAPTER_NUMBER = Regex("""الفصل\s*(\d+(?:\.\d+)?)""")
        val CSS_RULE = Regex("""([^{}]+)\{([^}]*)\}""")
        val CLASS_SELECTOR = Regex("""\.([A-Za-z_][\w-]*)""")
        val HIDING_DECLARATION = Regex(
            """display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(?:\.0+)?\s*(?:;|$|!)|text-indent\s*:\s*-\d{4,}|font-size\s*:\s*0(?:px)?\s*(?:;|$)""",
        )
    }
}
