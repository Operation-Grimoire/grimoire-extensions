package io.grimoire.extension.ar.galaxynovels

import io.grimoire.api.network.SourceBrowser
import io.grimoire.api.network.SourceBrowserReading
import io.grimoire.api.network.SourceDocumentDecision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Some Galaxy edges serve Chromium normally but keep challenging OkHttp after clearance. */
internal class GalaxyWebView {
    // The app fetches several TOC pages concurrently. Keep only one browser read in flight.
    private val mutex = Mutex()

    suspend fun text(url: String, allowNotFound: Boolean): String = mutex.withLock {
        val reading = SourceBrowser.read(url, SCRIPT) { snapshot -> decide(snapshot, url, allowNotFound) }
        if (reading.status == 404 && allowNotFound) "" else decode(reading.javascript, url)
    }

    private fun decide(reading: SourceBrowserReading, url: String, allowNotFound: Boolean): SourceDocumentDecision {
        val body = runCatching { decode(reading.javascript, url) }.getOrElse {
            return SourceDocumentDecision.Reject(IOException("GalaxyNovels: invalid browser response", it))
        }
        return when {
            reading.status == 404 && allowNotFound -> SourceDocumentDecision.Accept
            reading.status in 200..299 && isContent(body) -> SourceDocumentDecision.Accept
            reading.status !in listOf(200, 403, 503) ->
                SourceDocumentDecision.Reject(IOException("GalaxyNovels: HTTP ${reading.status}"))
            else -> SourceDocumentDecision.Wait
        }
    }

    internal fun decode(value: String, requestedUrl: String): String {
        val data = JSONObject(JSONArray("[$value]").getString(0))
        check(sameOrigin(data.getString("url"), requestedUrl)) { "Unexpected browser origin" }
        return data.getString("body")
    }

    internal fun isContent(body: String): Boolean =
        runCatching { JSONObject(body) }.isSuccess || body.contains("wor-") && body.contains("<html", ignoreCase = true)

    private fun sameOrigin(value: String, requested: String): Boolean = runCatching {
        val target = value.toHttpUrl()
        val origin = requested.toHttpUrl()
        target.isHttps && target.host == origin.host && target.port == origin.port
    }.getOrDefault(false)

    private companion object {
        const val SCRIPT =
            "JSON.stringify({url:location.href,body:document.contentType.includes('json')?document.body.innerText:document.documentElement.outerHTML})"
    }
}
