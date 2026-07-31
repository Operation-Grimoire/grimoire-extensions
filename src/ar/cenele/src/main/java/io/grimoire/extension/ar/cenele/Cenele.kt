package io.grimoire.extension.ar.cenele

import io.grimoire.api.model.lang.Language
import io.grimoire.api.model.novel.Novel
import io.grimoire.api.model.novel.NovelPage
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource
import org.jsoup.nodes.Document

@SourceInfo(name = "Cenele", lang = Language.AR, baseUrl = "https://cenele.com", versionCode = 3)
class Cenele : WPNovelsSource() {
    override val name = "Cenele"
    override val lang = Language.AR
    override val baseUrl = "https://cenele.com"
    override val novelPathSegment = "cont"

    // Cenele's customized theme puts the title in `.manga-title` rather than the
    // standard `.post-title` heading, so the base parse leaves it blank.
    override fun novelFromDocument(document: Document): Novel {
        val base = super.novelFromDocument(document)
        return if (base.title.isBlank()) {
            base.copy(title = document.selectFirst("div.manga-title")?.text()?.trim().orEmpty())
        } else {
            base
        }
    }

    // Cenele injects anti-piracy promo paragraphs (each carrying a `cenele.com`
    // link) into chapter bodies; drop them and re-index the remaining pages.
    override fun pagesFromDocument(document: Document): List<NovelPage> =
        super.pagesFromDocument(document)
            .filterNot { (it.content as? PageContent.Text)?.text?.contains("cenele.com", ignoreCase = true) == true }
            .mapIndexed { index, page -> NovelPage(index, page.content) }
}
