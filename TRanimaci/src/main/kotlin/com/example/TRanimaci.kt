package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class TRanimaci : MainAPI() {
    override var mainUrl              = "https://tranimaci.com"
    override var name                 = "TRanimeci"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 300L
    override var sequentialMainPageScrollDelay = 300L

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }

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
                interceptor = cloudflareKiller
            ).document
        } catch (e: Exception) {
            null
        } ?: return newHomePageResponse(request.name, emptyList())

        // 1. Try extracting animes from standard <a> links
        val home = document.select("a").mapNotNull { it.toMainPageResult() }
        val distinctHome = home.distinctBy { it.url }

        // 2. Next.js __NEXT_DATA__ JSON fallback if HTML links were empty
        if (distinctHome.isEmpty()) {
            val nextDataEl = document.selectFirst("script#__NEXT_DATA__")
            if (nextDataEl != null) {
                try {
                    val jsonStr = nextDataEl.data()
                    val json = JSONObject(jsonStr)
                    val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                    if (pageProps != null) {
                        val fallbackList = mutableListOf<SearchResponse>()
                        val keys = pageProps.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val arr = pageProps.optJSONArray(key) ?: continue
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val name = item.optString("name").ifBlank { item.optString("title") }
                                val slug = item.optString("slug").ifBlank { item.optString("id") }
                                var poster = item.optString("poster").ifBlank {
                                    item.optString("image").ifBlank { item.optString("cover") }
                                }
                                if (poster.isNotBlank() && !poster.startsWith("http")) poster = "$mainUrl$poster"

                                if (name.isNotBlank() && slug.isNotBlank()) {
                                    val animeUrl = "$mainUrl/en/anime/$slug"
                                    fallbackList.add(newAnimeSearchResponse(name, animeUrl, TvType.Anime) {
                                        if (poster.isNotBlank()) this.posterUrl = poster
                                    })
                                }
                            }
                        }
                        if (fallbackList.isNotEmpty()) {
                            return newHomePageResponse(request.name, fallbackList.distinctBy { it.url })
                        }
                    }
                } catch (e: Exception) {
                    // Ignore JSON fallback error
                }
            }
        }

        return newHomePageResponse(request.name, distinctHome, distinctHome.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = this.attr("href").trim()
        val isAnimeLink = href.contains("/anime/") || href.contains("/anime-filmleri/") ||
                          href.contains("/series/") || href.contains("/video/")
        if (!isAnimeLink) return null

        // 1. Image inside this <a>
        var imgEl = this.selectFirst("img")
        // 2. Image in parent or container
        if (imgEl == null) {
            imgEl = this.parent()?.selectFirst("img")
        }
        if (imgEl == null) {
            imgEl = this.closest("div, article, li")?.selectFirst("img")
        }

        var imgUrl = imgEl?.attr("src") ?: imgEl?.attr("data-src") ?: ""
        if (imgUrl.contains("/_next/image?url=")) {
            imgUrl = try {
                java.net.URLDecoder.decode(imgUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) {
                imgUrl
            }
        }
        if (imgUrl.isNotBlank() && !imgUrl.startsWith("http")) {
            imgUrl = "$mainUrl$imgUrl"
        }

        var title = imgEl?.attr("alt")?.trim() ?: ""
        if (title.isBlank()) {
            title = this.text().trim()
        }
        if (title.isBlank()) {
            title = this.attr("title").trim()
        }

        // Drop generic navigation words
        if (title.isBlank() || title.equals("anime", ignoreCase = true) ||
            title.equals("kategoriler", ignoreCase = true) || title.equals("giriş yap", ignoreCase = true)) {
            return null
        }

        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        return newAnimeSearchResponse(title, fullHref, TvType.Anime) {
            if (imgUrl.isNotBlank()) {
                this.posterUrl = imgUrl
            }
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
                interceptor = cloudflareKiller
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
                interceptor = cloudflareKiller
            ).document
        } catch (e: Exception) {
            return null
        }

        val title = document.selectFirst("h1, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null

        var posterUrl = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[alt*='$title'], .infox img, img")?.attr("src") ?: ""

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

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.select("p").map { it.text().trim() }.filter { it.length > 30 }.maxByOrNull { it.length } ?: ""

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

        // 1. Direct iframes or embeds
        try {
            val doc = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cloudflareKiller
            ).document

            val iframes = doc.select("iframe").mapNotNull { it.attr("src") }
            for (iframe in iframes) {
                val fullIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe
                val handled = loadExtractor(fullIframe, data, subtitleCallback, callback)
                if (handled) found = true
            }

            // Check for direct video tags
            val videoSrc = doc.selectFirst("video source")?.attr("src") ?: doc.selectFirst("video")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                val fullVideo = if (videoSrc.startsWith("//")) "https:$videoSrc" else videoSrc
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "TRanimeci HD",
                        url = fullVideo,
                        type = if (fullVideo.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("Referer" to "$mainUrl/")
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
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
