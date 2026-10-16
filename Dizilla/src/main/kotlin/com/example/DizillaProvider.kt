package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler" to "[Dizilla] Altyazili Bolumler",
        "${mainUrl}/dublaj-bolumler" to "[Dizilla] Dublaj Bolumler",
        "${mainUrl}/dizi-turu/aksiyon" to "[Dizilla] Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "[Dizilla] Bilim Kurgu",
        "${mainUrl}/dizi-turu/komedi" to "[Dizilla] Komedi",
        "${mainUrl}/dizi-turu/aile" to "[Dizilla] Aile",
        "${mainUrl}/dizi-turu/romantik" to "[Dizilla] Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = baseHeaders).document
        val home = if (request.data.contains("dizi-turu")) {
            document.select("div.grid-cols-3 a").mapNotNull { it.diziler() }
        } else {
            document.select("div.grid a").mapNotNull { it.sonBolumler() }
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name = this.selectFirst("h2")?.text()?.trim() ?: return null
        val epName = this.selectFirst("div.opacity-80")?.text()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: ""
        val title = if (epName.isNotBlank()) "$name - $epName" else name

        val epDoc = app.get(this.attr("href"), headers = baseHeaders).document
        val href = fixUrlNull(epDoc.selectFirst("a.relative")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(epDoc.selectFirst("img.imgt")?.attr("onerror")?.substringAfter("= '")?.substringBefore("';"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama?q=${query}", headers = baseHeaders).document
        return document.select("div.grid-cols-3 a, a[href*='/dizi/']").mapNotNull { it.diziler() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document

        val title = document.selectFirst("div.page-top h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.page-top img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.page-top img")?.attr("data-src"))
        val year = document.selectXpath("//span[text()='Yayın tarihi']//following-sibling::span").text().trim().split(" ").lastOrNull()?.toIntOrNull()
        val description = document.selectFirst("div.mv-det-p")?.text()?.trim()
            ?: document.selectFirst("div.w-full div.text-base")?.text()?.trim()
        val tags = document.select("[href*='dizi-turu']").map { it.text().trim() }
        val rating = document.selectFirst("a[href*='imdb.com'] span")?.text()?.trim()?.toFloatOrNull()
        val duration = Regex("(\\d+)").find(document.select("div.gap-3 span.text-sm").text())?.value?.toIntOrNull()
        val actors = document.select("[href*='oyuncu']").map { Actor(it.text().trim()) }

        val episodeList = mutableListOf<Episode>()
        document.selectXpath("//div[contains(@class, 'gap-2')]/a[contains(@href, '-sezon')]").forEach { seasonLink ->
            val epDoc = app.get(fixUrlNull(seasonLink.attr("href")) ?: return@forEach, headers = baseHeaders).document

            epDoc.select("div.episodes div.cursor-pointer").forEach ep@{ episodeElement ->
                val epName = episodeElement.select("a").last()?.text()?.trim() ?: return@ep
                val epHref = fixUrlNull(episodeElement.selectFirst("a.opacity-60")?.attr("href")) ?: return@ep
                val epDescription = episodeElement.selectFirst("span.t-content")?.text()?.trim()
                val epPoster = epDoc.selectFirst("img.object-cover")?.attr("src")
                val epEpisode = episodeElement.selectFirst("a.opacity-60")?.text()?.toIntOrNull()

                val parentDiv = episodeElement.parent()
                val seasonClass = parentDiv?.className()?.split(" ")?.find { it.startsWith("szn") }
                val epSeason = seasonClass?.substringAfter("szn")?.toIntOrNull() ?: 1

                episodeList.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                    this.description = epDescription
                    this.posterUrl = epPoster
                })
            }

            epDoc.select("div.dub-episodes div.cursor-pointer").forEach epDub@{ dubEpisodeElement ->
                val epName = dubEpisodeElement.select("a").last()?.text()?.trim() ?: return@epDub
                val epHref = fixUrlNull(dubEpisodeElement.selectFirst("a.opacity-60")?.attr("href")) ?: return@epDub
                val epDescription = dubEpisodeElement.selectFirst("span.t-content")?.text()?.trim()
                val epPoster = epDoc.selectFirst("img.object-cover")?.attr("src")
                val epEpisode = dubEpisodeElement.selectFirst("a.opacity-60")?.text()?.toIntOrNull()

                val parentDiv = dubEpisodeElement.parent()
                val seasonClass = parentDiv?.className()?.split(" ")?.find { it.startsWith("szn") }
                val epSeason = seasonClass?.substringAfter("szn")?.toIntOrNull() ?: 1

                episodeList.add(newEpisode(epHref) {
                    this.name = "$epName Dublaj"
                    this.season = epSeason
                    this.episode = epEpisode
                    this.description = epDescription
                    this.posterUrl = epPoster
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
            this.duration = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZL", "data >> $data")
        val document = app.get(data, headers = baseHeaders).document
        val iframes = mutableSetOf<String>()

        val alternatifler = document.select("a[href*='player']")
        if (alternatifler.isEmpty()) {
            val iframe = fixUrlNull(document.selectFirst("div#playerLsDizilla iframe")?.attr("src")) ?: return false
            Log.d("DZL", "iframe >> $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        } else {
            alternatifler.forEach {
                val playerDoc = app.get(fixUrlNull(it.attr("href")) ?: return@forEach, headers = baseHeaders).document
                val iframe = fixUrlNull(playerDoc.selectFirst("div#playerLsDizilla iframe")?.attr("src")) ?: return false

                if (iframe in iframes) return@forEach
                iframes.add(iframe)

                Log.d("DZL", "iframe >> $iframe")
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
