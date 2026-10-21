package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TRanimaci : MainAPI() {
    override var mainUrl              = "https://tranimaci.com"
    override var name                 = "TrAnimeci"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 500L
    override var sequentialMainPageScrollDelay = 500L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment") || response.code == 403) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/en/yeni"                  to "Yeni Bölümler",
        "${mainUrl}/anime"                    to "Tüm Animeler",
        "${mainUrl}/populer"                  to "Popüler",
        "${mainUrl}/kategori/aksiyon-anime"   to "Aksiyon",
        "${mainUrl}/kategori/macera-anime"    to "Macera",
        "${mainUrl}/kategori/komedi-anime"    to "Komedi",
        "${mainUrl}/kategori/harem-anime"     to "Harem",
        "${mainUrl}/kategori/fantastik-anime" to "Fantastik",
        "${mainUrl}/kategori/romantizm-anime" to "Romantizm",
        "${mainUrl}/kategori/dram-anime"      to "Dram"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("SAYFA", "$page")
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("a").mapNotNull { it.toMainPageResult() }

        val distinctHome = home.distinctBy { it.url }
        return newHomePageResponse(request.name, distinctHome, distinctHome.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = this.attr("href")
        if (!href.startsWith("/anime/") && !href.startsWith("$mainUrl/anime/")) return null
        val imgEl = this.selectFirst("img") ?: return null

        var imgUrl = imgEl.attr("src")
        if (imgUrl.contains("/_next/image?url=")) {
            imgUrl = java.net.URLDecoder.decode(imgUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
        }
        if (!imgUrl.startsWith("http")) {
            imgUrl = "$mainUrl$imgUrl"
        }

        val title = imgEl.attr("alt")
        if (title.isBlank()) return null

        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        return newAnimeSearchResponse(title, fullHref, TvType.Anime) {
            this.posterUrl = imgUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url, interceptor = interceptor).document

        return document.select("a").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        var posterUrl = document.selectFirst("img[alt*='$title']")?.attr("src") ?: ""
        if (posterUrl.contains("/_next/image?url=")) {
            posterUrl = java.net.URLDecoder.decode(posterUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
        }
        if (posterUrl.isNotBlank() && !posterUrl.startsWith("http")) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val plot = document.select("p").map { it.text().trim() }
            .filter { it.length > 50 }
            .maxByOrNull { it.length } ?: ""

        val episodes = mutableListOf<Episode>()
        val episodeLinks = document.select("a[href*='/video/']")
        val distinctLinks = episodeLinks.distinctBy { it.attr("href") }

        for (ep in distinctLinks) {
            val epHref = ep.attr("href")
            val fullHref = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"

            val numFromUrl = Regex("""-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val numFromText = Regex("""Bölüm\s*(\d+)""").find(ep.text())?.groupValues?.get(1)?.toIntOrNull()

            val epNum = numFromText ?: numFromUrl

            episodes.add(newEpisode(fullHref) {
                this.name = ep.text().trim()
                this.episode = epNum
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1. Check if the page has direct iframes or player embeds
        try {
            val doc = app.get(data, interceptor = interceptor).document
            val iframes = doc.select("iframe").mapNotNull { it.attr("src") }
            for (iframe in iframes) {
                val fullIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe
                val handled = loadExtractor(fullIframe, data, subtitleCallback, callback)
                if (handled) found = true
            }
        } catch (e: Exception) {
            // Ignore error and proceed to WebView extractor
        }

        // 2. Use WebView extractor for Next.js rendered streams
        val context = TRanimaciPlugin.pluginContext
        if (context != null) {
            try {
                TRanimaciWebViewExtractor(context).getUrl(data, "$mainUrl/", subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                // Ignore
            }
        }

        return found
    }
}
