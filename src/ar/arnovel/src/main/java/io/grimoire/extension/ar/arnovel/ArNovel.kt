package io.grimoire.extension.ar.arnovel

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource

@SourceInfo(name = "Ar Novel", lang = Language.AR, baseUrl = "https://ar-no.com", versionCode = 1)
class ArNovel : WPNovelsSource() {
    override val name = "Ar Novel"
    override val lang = Language.AR
    override val baseUrl = "https://ar-no.com"
    override val novelPathSegment = "novel"
}
