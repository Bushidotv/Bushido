package com.example

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin: Plugin() {
    private var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        // Bulgar Canlı TV (Bushido BG)
        registerMainAPI(ExampleProvider())

        // Türk Spor & Canlı TV (Bushido TR)
        registerMainAPI(DynamicLiveProvider())

        // Film Arşivi (FullHDFilmizlesene)
        registerMainAPI(FullHDFilmProvider())

        // Şablonun beraberinde getirdiği BlankFragment ayarlar menüsünü koruyoruz
        openSettings = {
            val frag = BlankFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "Frag")
            }
        }
    }
}
