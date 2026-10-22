package com.example

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Görünür Cloudflare doğrulama penceresi (eklenti ayarları / dişli simgesinden açılır).
 *
 * Doğrulamayı KULLANICI kendisi geçer (gerçek bir tarayıcı gibi). Başarılı olunca oluşan
 * cf_clearance çerezi ve tarayıcı kimliği (User-Agent) kaydedilir ve sonraki isteklerde kullanılır.
 * Hiçbir şey otomatik çözülmez.
 */
object TRanimaciVerifyDialog {
    private const val TAG = "TRanimaciVerify"
    private const val SITE = "https://tranimaci.com"

    @SuppressLint("SetJavaScriptEnabled")
    fun show(ctx: Context) {
        try {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundColor(Color.BLACK)

            val bar = LinearLayout(ctx)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.setPadding(dp(12), dp(8), dp(12), dp(8))

            val info = TextView(ctx)
            info.text = "Cloudflare doğrulamasını tamamla. Site açılınca kendiliğinden kapanır; kapanmazsa 'Bitti'ye bas."
            info.setTextColor(Color.WHITE)
            info.textSize = 13f
            bar.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val done = Button(ctx)
            done.text = "Bitti"
            bar.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val webView = WebView(ctx)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            val ua = webView.settings.userAgentString

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            try {
                cm.setAcceptThirdPartyCookies(webView, true)
            } catch (_: Exception) {
            }

            fun tryFinish(showMessageIfMissing: Boolean): Boolean {
                val cookies = cm.getCookie(SITE)
                if (cookies != null && cookies.contains("cf_clearance")) {
                    TRanimaciCloudflare.store(ctx.applicationContext, cookies, ua)
                    Toast.makeText(ctx, "Doğrulama tamam. Ana sayfayı yenile.", Toast.LENGTH_LONG).show()
                    try {
                        dialog.dismiss()
                    } catch (_: Exception) {
                    }
                    return true
                }
                if (showMessageIfMissing) {
                    Toast.makeText(ctx, "Henüz doğrulama alınmadı. Sayfadaki doğrulamayı tamamla.", Toast.LENGTH_LONG).show()
                }
                return false
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tryFinish(false)
                }
            }

            done.setOnClickListener { tryFinish(true) }

            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            dialog.setContentView(root)
            dialog.setOnDismissListener {
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {
                }
            }

            webView.loadUrl(SITE)
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "dialog error: ${e.message}")
            try {
                Toast.makeText(ctx, "Doğrulama penceresi açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }
}
