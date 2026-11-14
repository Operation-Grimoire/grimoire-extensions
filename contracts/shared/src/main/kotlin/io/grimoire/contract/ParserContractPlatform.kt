package io.grimoire.contract

import io.grimoire.api.network.SourceDocumentBrowser
import io.grimoire.api.network.SourceHttpPlatform
import okhttp3.Interceptor

/** One process-wide test host for both contracts. Every HTTP request is intercepted offline. */
class ParserContractPlatform : SourceHttpPlatform, SourceDocumentBrowser by GalaxyNovelsFixtures {
    private val royalRoad = RoyalRoadContractPlatform()
    override val cookieJar = royalRoad.cookieJar
    override val userAgent = royalRoad.userAgent
    override val interceptors = listOf(Interceptor { chain ->
        when (chain.request().url.host) {
            "www.royalroad.com" -> royalRoad.interceptors.single().intercept(chain)
            "galaxynovels.com" -> GalaxyNovelsFixtures.challenge.intercept(chain)
            else -> error("Unexpected fixture host: ${chain.request().url}")
        }
    })
}
