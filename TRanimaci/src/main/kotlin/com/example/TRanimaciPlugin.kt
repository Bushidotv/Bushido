package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TRanimaciPlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context.applicationContext ?: context
        pluginContext?.let { TRanimaciCloudflare.restore(it) }
        registerMainAPI(TRanimaci())

        // Ayarlar > Uzantılar > TRanimeci dişlisi: görünür Cloudflare doğrulama penceresi
        openSettings = { ctx -> TRanimaciVerifyDialog.show(ctx) }
    }
}
