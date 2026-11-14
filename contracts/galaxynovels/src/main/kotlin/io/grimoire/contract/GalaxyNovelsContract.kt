package io.grimoire.contract

import io.grimoire.api.model.novel.NovelStatus
import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.network.CloudflareException
import io.grimoire.api.network.SourceBrowserReading
import io.grimoire.api.network.SourceDocumentBrowser
import io.grimoire.api.network.SourceDocumentDecision
import io.grimoire.api.source.Source
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.sourceIdFor
import io.grimoire.api.source.web.PageListSource
import io.grimoire.api.source.web.PaginatedSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** Synthetic browser observations, not an implementation of WebView or JCEF. No network is used. */
object GalaxyNovelsFixtures : SourceDocumentBrowser {
    enum class ResponseCase { CONTENT, REDIRECT, INVALID_JSON, HTTP_ERROR }

    override var ready = false
    var responseCase = ResponseCase.CONTENT
    val httpRequests = mutableListOf<String>()
    val browserRequests = mutableListOf<String>()
    val decisions = mutableListOf<String>()
    private val scripts = mutableSetOf<String>()

    fun reset() {
        ready = false
        responseCase = ResponseCase.CONTENT
        httpRequests.clear()
        browserRequests.clear()
        decisions.clear()
        scripts.clear()
    }

    val challenge = Interceptor { chain ->
        val url = chain.request().url
        check(url.isHttps && url.host == "galaxynovels.com") { "Unexpected fixture URL: $url" }
        httpRequests += url.toString()
        throw CloudflareException(url.toString())
    }

    override suspend fun read(
        url: String,
        script: String,
        timeoutMillis: Long,
        decide: (SourceBrowserReading) -> SourceDocumentDecision,
    ): SourceBrowserReading {
        check(ready && timeoutMillis > 0)
        val target = url.toHttpUrl()
        check(target.isHttps && target.host == "galaxynovels.com") { "Unexpected browser URL: $url" }
        check(script.contains("location.href") && script.contains("document.documentElement.outerHTML"))
        scripts += script
        check(scripts.size == 1) { "Source changed its browser extraction script within a run" }
        browserRequests += url

        fun reading(status: Int, body: String, origin: String = url): SourceBrowserReading =
            SourceBrowserReading(url, status, JSONObject.quote(JSONObject().put("url", origin).put("body", body).toString()))

        fun consider(value: SourceBrowserReading): SourceDocumentDecision = decide(value).also {
            decisions += when (it) {
                SourceDocumentDecision.Wait -> "Wait"
                SourceDocumentDecision.Accept -> "Accept"
                is SourceDocumentDecision.Reject -> "Reject"
            }
        }

        val invalid = when (responseCase) {
            ResponseCase.REDIRECT -> reading(200, "<html><main class='wor-reader'>redirect</main></html>", "https://other.invalid/")
            ResponseCase.INVALID_JSON -> SourceBrowserReading(url, 200, "null")
            ResponseCase.HTTP_ERROR -> reading(500, "<html><main class='wor-reader'>error</main></html>")
            ResponseCase.CONTENT -> null
        }
        if (invalid != null) {
            val rejected = consider(invalid)
            check(rejected is SourceDocumentDecision.Reject) { "Unsafe browser observation was not rejected" }
            throw rejected.error
        }

        check(consider(reading(403, "<html><title>Just a moment...</title></html>")) == SourceDocumentDecision.Wait)
        val (status, body) = response(url)
        val accepted = reading(status, body)
        check(consider(accepted) == SourceDocumentDecision.Accept) { "Fixture content was not accepted" }
        return accepted
    }

    private fun response(url: String): Pair<Int, String> {
        val target = url.toHttpUrl()
        val body = when (target.encodedPath) {
            "/novels/" -> {
                check(target.queryParameter("sort") == "popular" && target.queryParameter("period") == "month")
                "<html><article class='wor-novel-card'>$card</article></html>"
            }
            "/novels/page/2/" -> return 404 to ""
            "/" -> """<html><div class="wor-latest-section"><article class="wor-latest-item">$card</article></div>
                <script>var worReaderSearchConfig = {"manifestUrl":"https://galaxynovels.com/manifest.json"};</script></html>"""
            "/manifest.json" -> """{"index":"https://galaxynovels.com/fixture-index.json"}"""
            "/fixture-index.json" -> """{"items":[{"s":"رواية الفانوس","t":"رواية الفانوس","u":"/novel/fixture/","c":"/cover.jpg"}]}"""
            "/novel/fixture/" -> {
                val page = target.queryParameter("chapter_page")?.toInt() ?: 1
                check(page in 1..2)
                """<html><article class="wor-single-novel-page"><h1>رواية الفانوس</h1></article>
                    <div class="wor-single-hero__cover"><img src="/cover.jpg"></div>
                    <div class="wor-single-summary__text"><p>A story about <b>light</b>.</p></div>
                    <div class="wor-single-hero__meta-text--author">Author: Fixture</div>
                    <div class="wor-single-identity__item--status">مستمر</div>
                    <div class="wor-single-genres-strip"><a>Fantasy</a><a>Fantasy</a></div>
                    <article class="wor-novel-chapter-item">
                      <a class="wor-novel-chapter-item__num" href="/novel/fixture/chapter-$page/full-title/">$page</a>
                      <h3>Chapter $page</h3><time datetime="2026-10-01T12:00:00+00:00"></time>
                    </article><a href="?chapter_page=2#chapters">2</a></html>"""
            }
            "/novel/fixture/chapter-1/full-title/" -> """<html><p>UNRELATED</p><article class="wor-reading-page">
                <div class="wor-reader-text-surface"><p>رَنَّ <b>الجرسُ</b>.</p><p>تَبِعَتْ ميرا <i>النورَ</i>.</p></div>
                <p>NAVIGATION</p></article></html>"""
            else -> error("Unexpected fixture URL: $url")
        }
        return 200 to body
    }

    private const val card = "<h2><a href='/novel/fixture/'>رواية الفانوس</a></h2><img class='wor-cover-img' src='/cover.jpg'>"
}

/** Runs unchanged from the packaged APK and JAR; production source code is never copied here. */
object GalaxyNovelsContract {
    suspend fun run(source: Source): Map<String, String> {
        GalaxyNovelsFixtures.reset()
        val popularSource = source as PopularSource
        try {
            popularSource.getPopularNovels(1)
            error("A source without a ready browser must fail explicitly")
        } catch (_: CloudflareException) {
            check(GalaxyNovelsFixtures.browserRequests.isEmpty())
        }
        GalaxyNovelsFixtures.ready = true
        val popular = popularSource.getPopularNovels(1)
        check(popular.single().title == "رواية الفانوس")
        check(popularSource.getPopularNovels(2).isEmpty())
        val latestSource = source as LatestSource
        val latest = latestSource.getLatestUpdates(1)
        check(latestSource.getLatestUpdates(2).isEmpty())
        val search = (source as SearchSource).searchNovels("رِوَايَة", 1, emptyList())
        check(latest == popular && search == popular)
        val novel = source.getNovelDetails(popular.single())
        check(novel.initialized && novel.author == "Fixture" && novel.status == NovelStatus.ONGOING)
        check(novel.genres == listOf("Fantasy"))
        val paginated = source as PaginatedSource
        check(paginated.getPageCount(novel) == 2)
        val chapters = paginated.getChapterList(novel, 1) + paginated.getChapterList(novel, 2)
        check(paginated.getChapterList(novel, 3).isEmpty())
        check(chapters.size == 2 && chapters.first().url.endsWith("/chapter-1/full-title/"))
        check(chapters.all { it.uploadDate > 0 })
        val pageSource = source as PageListSource
        val pages = pageSource.getPageList(chapters.first())
        val texts = pages.map { (it.content as PageContent.Text).text }
        check(texts == listOf("رَنَّ الجرسُ.", "تَبِعَتْ ميرا النورَ."))
        check((pages.first().content as PageContent.Text).html!!.contains("<b>الجرسُ</b>"))
        check(texts.none { it.contains("UNRELATED") || it.contains("NAVIGATION") })
        check(GalaxyNovelsFixtures.httpRequests.size == 2) { "Source did not retain its browser fallback" }

        for (case in listOf(GalaxyNovelsFixtures.ResponseCase.REDIRECT, GalaxyNovelsFixtures.ResponseCase.INVALID_JSON,
            GalaxyNovelsFixtures.ResponseCase.HTTP_ERROR)) {
            GalaxyNovelsFixtures.responseCase = case
            val reads = GalaxyNovelsFixtures.browserRequests.size
            val observations = GalaxyNovelsFixtures.decisions.size
            try {
                pageSource.getPageList(chapters.first())
                error("Source accepted $case")
            } catch (_: IOException) {
                check(GalaxyNovelsFixtures.browserRequests.size == reads + 1)
                check(GalaxyNovelsFixtures.decisions.size == observations + 1 && GalaxyNovelsFixtures.decisions.last() == "Reject")
            }
        }
        GalaxyNovelsFixtures.ready = false
        val reads = GalaxyNovelsFixtures.browserRequests.size
        try {
            pageSource.getPageList(chapters.first())
            error("Source accepted an unavailable browser")
        } catch (_: CloudflareException) {
            check(GalaxyNovelsFixtures.browserRequests.size == reads)
            check(GalaxyNovelsFixtures.httpRequests.size == 2)
        }

        val content = pages.joinToString("\n") { "${it.index}:" + (it.content as PageContent.Text).let { block -> "text:${block.text}|${block.html}" } }
        val annotation = requireNotNull(source.javaClass.getAnnotation(SourceInfo::class.java))
        return linkedMapOf(
            "sourceId" to sourceIdFor(requireNotNull(source.javaClass.`package`).name).toString(),
            "sourceClass" to source.javaClass.name, "sourceVersionCode" to annotation.versionCode.toString(),
            "popular" to popular.toString(), "latest" to latest.toString(), "search" to search.toString(),
            "novel" to novel.toString(), "chapters" to chapters.toString(), "pages" to content,
            "httpRequests" to GalaxyNovelsFixtures.httpRequests.toString(),
            "browserRequests" to GalaxyNovelsFixtures.browserRequests.toString(),
            "browserDecisions" to GalaxyNovelsFixtures.decisions.toString(),
            "browserExecution" to "synthetic source protocol",
            "pagesSha256" to MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
        )
    }
}
