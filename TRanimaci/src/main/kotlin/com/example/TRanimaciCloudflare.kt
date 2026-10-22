package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
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

    private const val PREFS = "tranimaci_cf"
    private const val SITE = "https://tranimaci.com"

    @Volatile var clearanceCookie: String? = null
    @Volatile var userAgent: String? = null
    @Volatile private var lastFailureAt = 0L

    // Uygulama yeniden açılınca daha önce alınmış doğrulamayı geri yükle
    fun restore(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val c = sp.getString("cookie", null)
            val ua = sp.getString("ua", null)
            if (!c.isNullOrBlank() && c.contains("cf_clearance")) {
                clearanceCookie = c
                userAgent = ua ?: WebSettings.getDefaultUserAgent(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "restore error: ${e.message}")
        }
    }

    // Kullanıcı görünür pencerede doğrulamayı geçince çağrılır
    fun store(context: Context, cookie: String, ua: String) {
        clearanceCookie = cookie
        userAgent = ua
        lastFailureAt = 0L
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("cookie", cookie).putString("ua", ua).apply()
        } catch (e: Exception) {
            Log.e(TAG, "store error: ${e.message}")
        }
    }

    // Çerez geçersizse (süresi dolmuş / reddedilmiş) temizle
    fun invalidate(context: Context?) {
        clearanceCookie = null
        try {
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        } catch (_: Exception) {
        }
    }

    // WebView çerez deposunda zaten geçerli bir cf_clearance varsa (başka yoldan alınmışsa) onu kullan
    fun adoptFromWebView(context: Context?): String? {
        if (!clearanceCookie.isNullOrBlank()) return clearanceCookie
        return try {
            val c = CookieManager.getInstance().getCookie(SITE)
            if (c != null && c.contains("cf_clearance")) {
                clearanceCookie = c
                if (userAgent == null && context != null) userAgent = WebSettings.getDefaultUserAgent(context)
                c
            } else null
        } catch (_: Exception) {
            null
        }
    }

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
