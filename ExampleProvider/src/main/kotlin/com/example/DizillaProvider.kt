package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DizillaProvider : MainAPI() {
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://dizilla.now/"
        )
    }

    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-diziler" to "[Dizilla] Tum Diziler",
        "${mainUrl}/trend-diziler" to "[Dizilla] Trend Diziler",
        "${mainUrl}/tur/aksiyon" to "[Dizilla] Aksiyon",
        "${mainUrl}/tur/bilim-kurgu" to "[Dizilla] Bilim Kurgu",
        "${mainUrl}/tur/komedi" to "[Dizilla] Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = baseHeaders).document
        val items = document.select("a[href*='/dizi/']")
        val list = items.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, list, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim() ?: this.text().trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama?q=${query}", headers = baseHeaders).document
        return document.select("a[href*='/dizi/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: document.title().trim()
        val poster = fixUrlNull(document.selectFirst(".poster img, .cover img")?.attr("src"))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = baseHeaders).document
        val iframes = document.select("iframe").mapNotNull { it.attr("src") }
        for (src in iframes) {
            loadExtractor(fixUrl(src), subtitleCallback, callback)
        }
        return true
    }
}
