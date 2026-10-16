package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BushidoMMAPlugin : Plugin() {
    override fun load(context: Context) {
        // Full Fight Replays VOD (UFC, Boxing, K-1, MMA)
        registerMainAPI(BushidoMMAProvider())
    }
}
