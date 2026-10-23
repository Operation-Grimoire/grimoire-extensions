package io.grimoire.extension.ar.galaxynovels

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.grimoire.api.network.CloudflareBypassException
import io.grimoire.api.network.NetworkContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Some Galaxy edges serve Chromium normally but keep challenging OkHttp after clearance. */
internal class GalaxyWebView {
    // The app fetches several TOC pages concurrently. Keep only one temporary WebView alive.
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun text(url: String, allowNotFound: Boolean): String = mutex.withLock {
        withTimeoutOrNull(30_000) {
            withContext(Dispatchers.Main) {
                val context = NetworkContext.context ?: throw CloudflareBypassException(url)
                val view = WebView(context)
                val handler = Handler(Looper.getMainLooper())
                try {
                    suspendCancellableCoroutine { continuation ->
                        var status = 200
                        fun fail(error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                        fun read() {
                            if (!continuation.isActive) return
                            view.evaluateJavascript(
                                "JSON.stringify({url:location.href,body:document.contentType.includes('json')?document.body.innerText:document.documentElement.outerHTML})",
                            ) { value ->
                                if (!continuation.isActive) return@evaluateJavascript
                                runCatching { decode(value, url) }.onSuccess { body ->
                                    when {
                                        status == 404 && allowNotFound -> continuation.resume("")
                                        status in 200..299 && isContent(body) -> continuation.resume(body)
                                        status !in listOf(200, 403, 503) -> fail(IOException("GalaxyNovels: HTTP $status"))
                                        else -> handler.postDelayed({ read() }, 500)
                                    }
                                }.onFailure { fail(IOException("GalaxyNovels: invalid WebView response", it)) }
                            }
                        }
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !sameOrigin(request.url.toString(), url)

                            override fun onPageStarted(view: WebView, target: String, favicon: android.graphics.Bitmap?) {
                                status = 200
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) status = response.statusCode
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) fail(IOException("GalaxyNovels: WebView network error ${error.errorCode}"))
                            }

                            override fun onPageFinished(view: WebView, target: String) {
                                if (target != "about:blank") read()
                            }
                        }
                        view.loadUrl(url)
                    }
                } finally {
                    handler.removeCallbacksAndMessages(null)
                    view.stopLoading()
                    view.destroy()
                }
            }
        } ?: throw CloudflareBypassException(url)
    }

    internal fun decode(value: String, requestedUrl: String): String {
        val data = JSONObject(JSONArray("[$value]").getString(0))
        check(sameOrigin(data.getString("url"), requestedUrl)) { "Unexpected WebView origin" }
        return data.getString("body")
    }

    internal fun isContent(body: String): Boolean =
        runCatching { JSONObject(body) }.isSuccess || body.contains("wor-") && body.contains("<html", ignoreCase = true)

    private fun sameOrigin(value: String, requested: String): Boolean = runCatching {
        val target = value.toHttpUrl()
        val origin = requested.toHttpUrl()
        target.isHttps && target.host == origin.host && target.port == origin.port
    }.getOrDefault(false)
}
