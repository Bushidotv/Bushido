package com.example

import java.util.Locale
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BelgeselX : MainAPI() {
    override var mainUrl              = "https://belgeselx.com"
    override var name                 = "BelgeselX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Documentary, TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/konu/turk-tarihi-belgeselleri" to "Türk Tarihi",
        "${mainUrl}/konu/tarih-belgeselleri"       to "Tarih",
        "${mainUrl}/konu/seyehat-belgeselleri"     to "Seyahat",
        "${mainUrl}/konu/seri-belgeseller"        to "Seri",
        "${mainUrl}/konu/savas-belgeselleri"       to "Savaş",
        "${mainUrl}/konu/sanat-belgeselleri"       to "Sanat",
        "${mainUrl}/konu/psikoloji-belgeselleri"   to "Psikoloji",
        "${mainUrl}/konu/polisiye-belgeselleri"   to "Polisiye",
        "${mainUrl}/konu/otomobil-belgeselleri"    to "Otomobil",
        "${mainUrl}/konu/nazi-belgeselleri"        to "Nazi",
        "${mainUrl}/konu/muhendislik-belgeselleri" to "Mühendislik",
        "${mainUrl}/konu/kultur-din-belgeselleri"  to "Kültür Din",
        "${mainUrl}/konu/kozmik-belgeseller"       to "Kozmik",
        "${mainUrl}/konu/hayvan-belgeselleri"      to "Hayvan",
        "${mainUrl}/konu/eski-tarih-belgeselleri"  to "Eski Tarih",
        "${mainUrl}/konu/egitim-belgeselleri"      to "Eğitim",
        "${mainUrl}/konu/dunya-belgeselleri"       to "Dünya",
        "${mainUrl}/konu/doga-belgeselleri"        to "Doğa",
        "${mainUrl}/konu/bilim-belgeselleri"       to "Bilim"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.px-card, div.px-card, div.gen-movie-contain").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".px-card-title, h3 a, h3")?.text()?.trim()?.toTitleCase() ?: return null
        val href = fixUrlNull(this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.px-card-img, img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm"

        return try {
            val tokenResponse = app.get("https://cse.google.com/cse.js?cx=${cx}")
            val cseLibVersion = Regex("""cselibVersion": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
            val cseToken      = Regex("""cse_token": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)

            val response = app.get("https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${query}&safe=off&cse_tok=${cseToken}&oq=${query}&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F")

            val titles     = Regex(""""titleNoFormatting": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
            val urls       = Regex(""""url": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
            val posterUrls = Regex(""""ogImage": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()

            val searchResponses = mutableListOf<TvSeriesSearchResponse>()

            for (i in titles.indices) {
                val title     = titles[i].split("İzle")[0].trim().toTitleCase()
                val url       = urls.getOrNull(i) ?: continue
                val posterUrl = posterUrls.getOrNull(i) ?: continue

                if (!url.contains("belgeseldizi") && !url.contains("belgesel")) continue
                searchResponses.add(newTvSeriesSearchResponse(title, url, TvType.Documentary) {
                    this.posterUrl = posterUrl
                })
            }
            searchResponses
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = (document.selectFirst("h1.px-hero-title")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("h2.gen-title")?.text())?.trim()?.toTitleCase() ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("div.px-hero-poster img, div.gen-tv-show-top img")?.attr("src"))

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.gen-single-tv-show-info p, .px-card-desc")?.text()?.trim()

        val tags = document.select("div.gen-socail-share a[href*='belgeselkanali'], a.px-hero-channel span").map {
            it.text().trim().toTitleCase()
        }

        var counter = 1
        val epCards = document.select("a.px-ep-card, div.px-ep-card")
        val episodes = if (epCards.isNotEmpty()) {
            epCards.mapNotNull {
                val epHref = fixUrlNull(it.attr("href").ifEmpty { it.selectFirst("a")?.attr("href") }) ?: return@mapNotNull null
                val epName = it.selectFirst(".px-ep-title")?.text()?.trim() ?: "Bölüm $counter"
                val metaText = it.selectFirst(".px-ep-s")?.text()?.trim() ?: ""
                val epSeason = Regex("""S(\d+)""").find(metaText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""B(\d+)""").find(metaText)?.groupValues?.get(1)?.toIntOrNull() ?: counter++

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }
        } else {
            val genCards = document.select("div.gen-movie-contain")
            if (genCards.isNotEmpty()) {
                genCards.mapNotNull {
                    val epName = it.selectFirst("div.gen-movie-info h3 a")?.text()?.trim() ?: return@mapNotNull null
                    val epHref = fixUrlNull(it.selectFirst("div.gen-movie-info h3 a")?.attr("href")) ?: return@mapNotNull null
                    val seasonName = it.selectFirst("div.gen-single-meta-holder ul li")?.text()?.trim() ?: ""
                    var epEpisode = Regex("""Bölüm (\d+)""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val epSeason = Regex("""Sezon (\d+)""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    if (epEpisode == 0) epEpisode = counter++

                    newEpisode(epHref) {
                        this.name    = epName
                        this.season  = epSeason
                        this.episode = epEpisode
                    }
                }
            } else {
                listOf(newEpisode(url) {
                    this.name    = title
                    this.season  = 1
                    this.episode = 1
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("BLX", "data » $data")
        val source = app.get(data)
        val text = source.text

        val srcMap = mapOf("0" to "new5", "2" to "new1", "5" to "new4", "3" to "new2", "4" to "new3")

        // 1. Check diziGetir call in JavaScript
        val diziMatches = Regex("""diziGetir\('(\d+)','(\d+)','(\d+)','(\d+)'""").findAll(text)
        var foundAny = false

        for (match in diziMatches) {
            val id = match.groupValues[1]
            val icList = listOf(match.groupValues[2], match.groupValues[3], match.groupValues[4])

            for ((index, ic) in icList.withIndex()) {
                val phpName = srcMap[ic] ?: continue
                val sira = index + 1
                val videoDataUrl = "https://belgeselx.com/video/data/$phpName.php?id=$id&sira=$sira"

                try {
                    val resp = app.get(videoDataUrl, referer = data)
                    val rText = resp.text

                    // Check JWPlayer file:"..." sources
                    Regex("""file:\s*"([^"]+)",\s*label:\s*"([^"]+)"""").findAll(rText).forEach {
                        val videoUrl = it.groupValues[1].trim()
                        if (videoUrl.isNotEmpty()) {
                            var quality = it.groupValues[2].trim()
                            if (quality == "FULL") quality = "1080p"

                            callback.invoke(
                                newExtractorLink(
                                    source  = this.name,
                                    name    = "${this.name} $quality",
                                    url     = videoUrl,
                                    type    = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = getQualityFromName(quality)
                                }
                            )
                            foundAny = true
                        }
                    }

                    // Check iframes inside
                    resp.document.select("iframe").forEach { iframeEl ->
                        val iSrc = fixUrlNull(iframeEl.attr("src").ifEmpty { iframeEl.attr("data-src") })
                        if (!iSrc.isNullOrEmpty()) {
                            loadExtractor(iSrc, "${mainUrl}/", subtitleCallback, callback)
                            foundAny = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BLX", "Error loading video data $videoDataUrl", e)
                }
            }
            if (foundAny) break
        }

        // 2. Fallback: check all iframes directly on page
        source.document.select("iframe").forEach { iframeEl ->
            val iSrc = fixUrlNull(iframeEl.attr("src").ifEmpty { iframeEl.attr("data-src") })
            if (!iSrc.isNullOrEmpty()) {
                loadExtractor(iSrc, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
