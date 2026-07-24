package io.grimoire.extension.ar.cenele

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WPNovelsSource

@SourceInfo(name = "Cenele", lang = Language.AR, baseUrl = "https://cenele.com", versionCode = 1)
class Cenele : WPNovelsSource() {
    override val name = "Cenele"
    override val lang = Language.AR
    override val baseUrl = "https://cenele.com"
    override val novelPathSegment = "cont"
}
