package io.grimoire.extension.ar.hizomanga

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource
import org.jsoup.nodes.Document

// Despite the name, Hizo Manga publishes Arabic text novels. Its Madara
// archive lives at /series/; novels themselves are under /content/{id}/.
@SourceInfo(name = "Hizo Manga", lang = Language.AR, baseUrl = "https://hizomanga.net", versionCode = 2)
class HizoManga : WPNovelsSource() {
    override val name = "Hizo Manga"
    override val lang = Language.AR
    override val baseUrl = "https://hizomanga.net"
    override val novelPathSegment = "series"

    // The child theme moves title, author and status out of Madara's
    // `.post-title` / `.author-content` / `.post-status` blocks into
    // `.manga-title`, `.manga-author` and `.manga-status`.
    override fun novelFromDocument(document: Document): Novel {
        val base = super.novelFromDocument(document)
        val status = document.selectFirst(".manga-status")?.text().orEmpty()
        return base.copy(
            title = base.title.ifBlank { document.selectFirst(".manga-title h2")?.text()?.trim().orEmpty() },
            author = base.author?.takeIf { it.isNotBlank() }
                ?: document.select(".manga-author a").joinToString(", ") { it.text().trim() }.takeIf { it.isNotBlank() },
            status = if (base.status != NovelStatus.UNKNOWN) {
                base.status
            } else {
                when {
                    "مستمر" in status -> NovelStatus.ONGOING
                    "مكتمل" in status -> NovelStatus.COMPLETED
                    "متوقف" in status -> NovelStatus.HIATUS
                    "ملغ" in status -> NovelStatus.CANCELLED
                    else -> NovelStatus.UNKNOWN
                }
            },
        )
    }

    // Chapters pad prose with `&nbsp;` spacer paragraphs; drop them and re-index.
    override fun pagesFromDocument(document: Document): List<NovelPage> =
        super.pagesFromDocument(document)
            .filterNot { (it.content as? PageContent.Text)?.text?.isBlank() == true }
            .mapIndexed { index, page -> NovelPage(index, page.content) }
}
