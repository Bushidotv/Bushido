package com.bushido

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FullFightReplaysPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FullFightReplays())
    }
}
