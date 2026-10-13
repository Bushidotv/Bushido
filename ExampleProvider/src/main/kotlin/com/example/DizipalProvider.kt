package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DizipalProvider : MainAPI() {
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://dizipal2138.com/"
        )
    }

    override var mainUrl = "https://dizipal2138.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "[Dizipal] Trendler",
        "${mainUrl}/kategori/aksiyon" to "[Dizipal] Aksiyon",
        "${mainUrl}/kategori/komedi" to "[Dizipal] Komedi",
        "${mainUrl}/kategori/korku" to "[Dizipal] Korku",
        "${mainUrl}/kategori/bilim-kurgu" to "[Dizipal] Bilim Kurgu",
        "${mainUrl}/kategori/macera" to "[Dizipal] Macera"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = baseHeaders).document
        val items = document.select("a[href*='/film/'], a[href*='/dizi/']")
        val list = items.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, list, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/film/") && !href.contains("/dizi/")) return null
        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim() ?: this.text().trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ajax-search?q=${query}", headers = baseHeaders).document
        return document.select("a[href*='/film/'], a[href*='/dizi/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val title = document.selectFirst("h1")?.text()?.replace(Regex("(?i)\\s*izle.*"), "")?.trim()
            ?: document.title().replace(Regex("(?i)\\s*izle.*"), "").trim()
        val poster = fixUrlNull(document.selectFirst(".trending-poster img, .player-cover-overlay")?.attr("data-src")
            ?: document.selectFirst(".player-cover-overlay")?.attr("style")?.let {
                Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1)
            })

        val isSeries = url.contains("/dizi/")
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
                this.posterUrl = poster
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = baseHeaders).document
        val cfg = document.selectFirst("#videoContainer")?.attr("data-cfg")
        if (!cfg.isNullOrBlank()) {
            val res = app.post(
                "${mainUrl}/ajax-player-config",
                data = mapOf("cfg" to cfg),
                headers = baseHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                )
            ).text
            val videoUrl = Regex(""""v":\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
            if (!videoUrl.isNullOrBlank()) {
                val cleanUrl = videoUrl.replace("\\/", "/")
                if (cleanUrl.contains(".m3u8") || cleanUrl.contains(".mp4")) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "Dizipal HLS",
                            cleanUrl,
                            type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                } else {
                    loadExtractor(cleanUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
