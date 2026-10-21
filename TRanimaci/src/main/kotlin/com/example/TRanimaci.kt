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
    override var name                 = "TRanimeci"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 500L
    override var sequentialMainPageScrollDelay = 500L

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment") || response.code == 403 || response.code == 503) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/en"                            to "Son Eklenenler",
        "${mainUrl}/en/anime"                      to "Tüm Animeler",
        "${mainUrl}/en/anime-filmleri"             to "Anime Filmleri",
        "${mainUrl}/en/kategori/aksiyon-anime"     to "Aksiyon",
        "${mainUrl}/en/kategori/macera-anime"      to "Macera",
        "${mainUrl}/en/kategori/komedi-anime"      to "Komedi",
        "${mainUrl}/en/kategori/fantastik-anime"   to "Fantastik",
        "${mainUrl}/en/kategori/romantizm-anime"   to "Romantizm",
        "${mainUrl}/en/kategori/dram-anime"        to "Dram",
        "${mainUrl}/en/kategori/shounen-anime"     to "Shounen",
        "${mainUrl}/en/kategori/seinen-anime"      to "Seinen",
        "${mainUrl}/en/kategori/bilim-kurgu-anime" to "Bilim Kurgu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1) {
            if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
        } else {
            baseUrl
        }

        val document = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            ).document
        } catch (e: Exception) {
            null
        } ?: return newHomePageResponse(request.name, emptyList())

        val home = document.select("a").mapNotNull { it.toMainPageResult() }
        val distinctHome = home.distinctBy { it.url }

        return newHomePageResponse(request.name, distinctHome, distinctHome.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = this.attr("href").trim()
        val isAnimeLink = href.contains("/anime/") || href.contains("/anime-filmleri/") || href.contains("/series/")
        if (!isAnimeLink) return null

        val imgEl = this.selectFirst("img") ?: return null

        var imgUrl = imgEl.attr("src")
        if (imgUrl.contains("/_next/image?url=")) {
            imgUrl = try {
                java.net.URLDecoder.decode(imgUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) {
                imgUrl
            }
        }
        if (!imgUrl.startsWith("http")) {
            imgUrl = "$mainUrl$imgUrl"
        }

        val title = imgEl.attr("alt").ifBlank {
            this.text().trim()
        }
        if (title.isBlank()) return null

        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        return newAnimeSearchResponse(title, fullHref, TvType.Anime) {
            this.posterUrl = imgUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/arama?q=$query"
        val document = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            ).document
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        return document.select("a").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            ).document
        } catch (e: Exception) {
            return null
        }

        val title = document.selectFirst("h1, .entry-title")?.text()?.trim() ?: return null

        var posterUrl = document.selectFirst("img[alt*='$title'], .infox img, img")?.attr("src") ?: ""
        if (posterUrl.contains("/_next/image?url=")) {
            posterUrl = try {
                java.net.URLDecoder.decode(posterUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) {
                posterUrl
            }
        }
        if (posterUrl.isNotBlank() && !posterUrl.startsWith("http")) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val plot = document.select("p").map { it.text().trim() }
            .filter { it.length > 30 }
            .maxByOrNull { it.length } ?: ""

        val episodes = mutableListOf<Episode>()
        val episodeLinks = document.select("a[href*='/video/'], a[href*='-bolum'], a[href*='/bolum/'], a.epl-title")
        val distinctLinks = episodeLinks.distinctBy { it.attr("href") }

        for (ep in distinctLinks) {
            val epHref = ep.attr("href").trim()
            if (epHref.isBlank()) continue
            val fullHref = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"

            val numFromUrl = Regex("""-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val numFromText = Regex("""Bölüm\s*(\d+)""").find(ep.text())?.groupValues?.get(1)?.toIntOrNull()

            val epNum = numFromText ?: numFromUrl

            episodes.add(newEpisode(fullHref) {
                this.name = ep.text().trim().ifBlank { "Bölüm ${epNum ?: (episodes.size + 1)}" }
                this.episode = epNum
            })
        }

        // Single anime movie fallback
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = 1
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
            val doc = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            ).document

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
