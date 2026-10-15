package io.grimoire.extension.ar.rewayatfans

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WpComNovelPagesSource

@SourceInfo(name = "Rewayat Fans", lang = Language.AR, baseUrl = "https://rewayatfans.com", versionCode = 1)
class RewayatFans : WpComNovelPagesSource() {
    override val name = "Rewayat Fans"
    override val lang = Language.AR
    override val baseUrl = "https://rewayatfans.com"
}
