package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TRanimaciWebViewHelper {
    private class HtmlBridge(private val onHtml: (String) -> Unit) {
        @JavascriptInterface
        fun processHtml(html: String) {
            onHtml(html)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchDocument(url: String, context: Context): Document? {
        var resultHtml: String? = null
        val latch = CountDownLatch(1)

        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                webView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                    }

                    addJavascriptInterface(HtmlBridge { html ->
                        if (!html.contains("Just a moment") && (html.contains("/anime/") || html.contains("__NEXT_DATA__") || html.contains("/video/") || html.contains("/series/"))) {
                            resultHtml = html
                            latch.countDown()
                        }
                    }, "HtmlBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            Handler(Looper.getMainLooper()).postDelayed({
                                view?.evaluateJavascript(
                                    "if (window.HtmlBridge) { window.HtmlBridge.processHtml(document.documentElement.outerHTML); }",
                                    null
                                )
                            }, 1500)
                        }
                    }

                    loadUrl(url)
                }
            } catch (e: Exception) {
                latch.countDown()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    webView?.destroy()
                } catch (e: Exception) {
                    // Ignore
                }
            }, 12_000)
        }

        withContext(Dispatchers.IO) {
            try {
                latch.await(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Ignore
            }
        }

        return resultHtml?.let { Jsoup.parse(it) }
    }
}
