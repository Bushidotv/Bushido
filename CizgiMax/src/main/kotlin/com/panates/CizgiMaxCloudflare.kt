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
import kotlin.coroutines.resume

object CizgiMaxCloudflare {
    private const val TAG = "CizgiMaxCF"
    var clearanceCookie: String? = null
    var userAgent: String? = null

    suspend fun resolveClearance(context: Context, url: String): String? {
        if (!clearanceCookie.isNullOrBlank()) {
            return clearanceCookie
        }

        return withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)

                        val existing = cookieManager.getCookie(url)
                        if (existing != null && existing.contains("cf_clearance")) {
                            clearanceCookie = existing
                            if (cont.isActive) cont.resume(existing)
                            return@post
                        }

                        val webView = WebView(context)
                        @SuppressLint("SetJavaScriptEnabled")
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.databaseEnabled = true
                        webView.settings.useWideViewPort = true
                        webView.settings.loadWithOverviewMode = true

                        userAgent = webView.settings.userAgentString

                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                val cookies = cookieManager.getCookie(finishedUrl ?: url)
                                if (cookies != null && cookies.contains("cf_clearance")) {
                                    clearanceCookie = cookies
                                    Log.d(TAG, "cf_clearance obtained: $cookies")
                                    if (cont.isActive) cont.resume(cookies)
                                    try { webView.destroy() } catch (_: Exception) {}
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, updatedUrl: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, updatedUrl, isReload)
                                val cookies = cookieManager.getCookie(updatedUrl ?: url)
                                if (cookies != null && cookies.contains("cf_clearance")) {
                                    clearanceCookie = cookies
                                    if (cont.isActive) cont.resume(cookies)
                                    try { webView.destroy() } catch (_: Exception) {}
                                }
                            }
                        }

                        webView.loadUrl(url)
                    } catch (e: Exception) {
                        Log.e(TAG, "WebView initialization error: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }
}
