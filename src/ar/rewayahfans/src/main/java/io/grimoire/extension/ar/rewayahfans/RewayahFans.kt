package io.grimoire.extension.ar.rewayahfans

import io.grimoire.api.model.lang.Language
import io.grimoire.api.source.SourceInfo
import io.grimoire.extensions.lib.theme.WpComNovelPagesSource

@SourceInfo(name = "Rewayah Fans", lang = Language.AR, baseUrl = "https://rewayahfans.net", versionCode = 1)
class RewayahFans : WpComNovelPagesSource() {
    override val name = "Rewayah Fans"
    override val lang = Language.AR
    override val baseUrl = "https://rewayahfans.net"
}
