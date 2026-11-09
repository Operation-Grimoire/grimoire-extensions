package io.grimoire.contract

import io.grimoire.api.model.novel.PageContent
import io.grimoire.api.network.SourceHttpPlatform
import io.grimoire.api.source.Source
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.feature.LatestSource
import io.grimoire.api.source.feature.PopularSource
import io.grimoire.api.source.feature.SearchSource
import io.grimoire.api.source.sourceIdFor
import io.grimoire.api.source.web.ChapterListSource
import io.grimoire.api.source.web.PageListSource
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest

/** Test-only host. Unmatched requests fail before any network access. */
class RoyalRoadContractPlatform : SourceHttpPlatform {
    override val cookieJar = CookieJar.NO_COOKIES
    override val userAgent = "GrimoireArtifactContract/1"
    override val interceptors = listOf(Interceptor { chain ->
        val url = chain.request().url
        check(url.host == "www.royalroad.com") { "Unexpected fixture host: $url" }
        val html = when (url.encodedPath) {
            "/fictions/best-rated", "/fictions/latest-updates", "/fictions/search" -> {
                if (url.encodedPath == "/fictions/search") check(url.queryParameter("title") == "Lantern")
                """<div class="fiction-list-item"><h2 class="fiction-title"><a href="/fiction/900000001">The Lantern Keeper</a></h2></div>"""
            }
            "/fiction/900000001" -> """
                <div class="fic-header"><h1>The Lantern Keeper</h1><h4><a>Grimoire fixture</a></h4></div>
                <div class="fiction-info"><span class="label">ONGOING</span></div>
                <div class="description"><p>A story about <b>light</b>.</p></div>
                <script>window.chapters = [
                  {"url":"/fiction/900000001/chapter/1","title":"At the harbor","order":0,"isUnlocked":true},
                  {"url":"/fiction/900000001/chapter/2","title":"A sealed letter","order":1,"isUnlocked":false}
                ];</script>
            """.trimIndent()
            "/fiction/900000001/chapter/1" -> """
                <style>.hidden-trap {display: none;}</style><div class="chapter-content">
                <p>The <b>harbor</b> bell rang.</p><p class="hidden-trap">HONEYPOT</p>
                <hr><p>Mira followed the <i>light</i>.</p></div>
            """.trimIndent()
            else -> error("Unexpected fixture URL: $url")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("Fixture").body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
    })
}

/** Exercises the real extension loaded from its JAR using the fixture responses above. */
object RoyalRoadContract {
    suspend fun run(source: Source): Map<String, String> {
        val popular = (source as PopularSource).getPopularNovels(1)
        check(popular.single().title == "The Lantern Keeper")
        val latest = (source as LatestSource).getLatestUpdates(1)
        val search = (source as SearchSource).searchNovels("Lantern", 1, emptyList())
        check(latest == popular && search == popular)
        val novel = source.getNovelDetails(popular.single())
        check(novel.initialized && novel.author == "Grimoire fixture")
        val chapters = (source as ChapterListSource).getChapterList(novel)
        check(chapters.size == 2 && !chapters.first().locked && chapters.last().locked)
        val pages = (source as PageListSource).getPageList(chapters.first())
        val texts = pages.mapNotNull { (it.content as? PageContent.Text)?.text }
        check(texts == listOf("The harbor bell rang.", "Mira followed the light."))
        check(pages.any { it.content is PageContent.Separator })
        check(pages.none { (it.content as? PageContent.Text)?.text?.contains("HONEYPOT") == true })
        val content = pages.joinToString("\n") { page ->
            "${page.index}:" + when (val block = page.content) {
                is PageContent.Text -> "text:${block.text}|${block.html}"
                is PageContent.Image -> "image:${block.url}"
                is PageContent.Separator -> "separator"
            }
        }
        val annotation = requireNotNull(source.javaClass.getAnnotation(SourceInfo::class.java))
        return linkedMapOf(
            "sourceId" to sourceIdFor(requireNotNull(source.javaClass.`package`).name).toString(),
            "sourceClass" to source.javaClass.name,
            "sourceVersionCode" to annotation.versionCode.toString(),
            "popular" to popular.toString(), "latest" to latest.toString(), "search" to search.toString(),
            "novel" to novel.toString(), "chapters" to chapters.toString(), "pages" to content,
            "pagesSha256" to MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }
}
