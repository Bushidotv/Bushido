package com.example

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

/**
 * Cloudflare çerez (cf_clearance) çözücü.
 *
 * Önemli davranışlar:
 *  - Yalnızca site gerçekten engel sayfası döndürdüğünde çağrılır (bkz. TRanimaci.safeGet).
 *  - Başarısız olursa COOLDOWN_MS boyunca tekrar denenmez; böylece her istekte 20 sn beklenmez.
 *  - WebView başarı, zaman aşımı ve iptal durumlarının hepsinde yok edilir (bellek sızıntısı yok).
 */
object TRanimaciCloudflare {
    private const val TAG = "TRanimaciCF"
    private const val TIMEOUT_MS = 20_000L
    private const val COOLDOWN_MS = 120_000L

    @Volatile var clearanceCookie: String? = null
    @Volatile var userAgent: String? = null
    @Volatile private var lastFailureAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveClearance(context: Context, url: String): String? {
        if (!clearanceCookie.isNullOrBlank()) return clearanceCookie

        // Yakın zamanda denendi ve olmadıysa tekrar bekletme
        if (System.currentTimeMillis() - lastFailureAt < COOLDOWN_MS) return null

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null

                fun destroyWebView() {
                    handler.post {
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (_: Exception) {
                        }
                        webView = null
                    }
                }

                fun finish(cookie: String?) {
                    if (cont.isActive) cont.resume(cookie)
                    destroyWebView()
                }

                // Zaman aşımı / iptal olduğunda da WebView kapatılsın
                cont.invokeOnCancellation { destroyWebView() }

                handler.post {
                    try {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)

                        val existing = cookieManager.getCookie(url)
                        if (existing != null && existing.contains("cf_clearance")) {
                            clearanceCookie = existing
                            finish(existing)
                            return@post
                        }

                        val wv = WebView(context)
                        webView = wv
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.databaseEnabled = true
                        wv.settings.useWideViewPort = true
                        wv.settings.loadWithOverviewMode = true
                        val ua = wv.settings.userAgentString

                        fun check(checkedUrl: String?) {
                            val cookies = cookieManager.getCookie(checkedUrl ?: url)
                            if (cookies != null && cookies.contains("cf_clearance")) {
                                // cf_clearance, onu üreten tarayıcının User-Agent'ına bağlıdır
                                userAgent = ua
                                clearanceCookie = cookies
                                Log.d(TAG, "cf_clearance obtained")
                                finish(cookies)
                            }
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                check(finishedUrl)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, updatedUrl: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, updatedUrl, isReload)
                                check(updatedUrl)
                            }
                        }

                        wv.loadUrl(url)
                    } catch (e: Exception) {
                        Log.e(TAG, "WebView initialization error: ${e.message}")
                        finish(null)
                    }
                }
            }
        }

        if (result.isNullOrBlank()) {
            lastFailureAt = System.currentTimeMillis()
            return null
        }
        return result
    }
}
