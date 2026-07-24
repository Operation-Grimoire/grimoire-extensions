package io.grimoire.extension.ar.hizomanga

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource

@SourceInfo(name = "Hizo Manga", lang = Language.AR, baseUrl = "https://hizomanga.net", versionCode = 1)
class HizoManga : WPNovelsSource() {
    override val name = "Hizo Manga"
    override val lang = Language.AR
    override val baseUrl = "https://hizomanga.net"
    override val novelPathSegment = "series"
}
