package com.panates

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume

/**
 * Fetches page HTML by loading the URL inside a real Android WebView.
 * Cloudflare Turnstile challenges are solved automatically by the
 * browser engine; once the real page appears we pull the DOM via
 * JavaScript and hand it back as a Jsoup Document.
 *
 * This is intentionally a suspend function – it posts work to the
 * main-thread Handler but never blocks it.
 */
object CizgiMaxWebView {
    private const val TAG = "CizgiMaxWV"

    /** Maximum time we wait for a single page load (ms). */
    private const val TIMEOUT_MS = 25_000L

    /**
     * After each onPageFinished we wait this long for JS to settle
     * (Cloudflare injects scripts that redirect after solving).
     */
    private const val SETTLE_DELAY_MS = 2_500L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchDocument(context: Context, url: String): Document? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)

                        val webView = WebView(context)
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.databaseEnabled = true
                        webView.settings.useWideViewPort = true
                        webView.settings.loadWithOverviewMode = true

                        var settled = false

                        fun tryExtract(view: WebView?) {
                            if (settled) return
                            view?.evaluateJavascript(
                                "(function(){ return document.documentElement.outerHTML; })();"
                            ) { rawJs ->
                                if (settled) return@evaluateJavascript
                                val html = rawJs
                                    ?.trim()
                                    ?.removeSurrounding("\"")
                                    ?.replace("\\u003C", "<")
                                    ?.replace("\\u003E", ">")
                                    ?.replace("\\u0026", "&")
                                    ?.replace("\\\"", "\"")
                                    ?.replace("\\n", "\n")
                                    ?.replace("\\t", "\t")
                                    ?.replace("\\/", "/")
                                    ?: ""

                                if (html.length > 500 && !html.contains("Just a moment")) {
                                    settled = true
                                    val doc = Jsoup.parse(html)
                                    doc.setBaseUri(url)
                                    Log.d(TAG, "Got real page for $url (${html.length} chars)")
                                    if (cont.isActive) cont.resume(doc)
                                    try { webView.destroy() } catch (_: Exception) {}
                                }
                            }
                        }

                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                // Give Cloudflare scripts time to complete redirect
                                view?.postDelayed({ tryExtract(view) }, SETTLE_DELAY_MS)
                            }
                        }

                        cont.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post {
                                try { webView.destroy() } catch (_: Exception) {}
                            }
                        }

                        webView.loadUrl(url)
                    } catch (e: Exception) {
                        Log.e(TAG, "WebView error for $url: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }
}
