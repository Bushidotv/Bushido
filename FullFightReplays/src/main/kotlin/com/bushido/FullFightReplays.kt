// ! Bu araç @Bushido tarafından yazılmıştır.

package com.bushido

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FullFightReplays : MainAPI() {
    override var mainUrl = "https://fullfightreplays.com"
    override var name = "FullFightReplays"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf(
        "$mainUrl/?page" to "Latest",
        "$mainUrl/ufc/?page" to "UFC",
        "$mainUrl/boxing/?page" to "Boxing",
        "$mainUrl/bellator/?page" to "Bellator MMA",
        "$mainUrl/one-championship/?page" to "ONE Championship",
        "$mainUrl/bkfc/?page" to "BKFC",
        "$mainUrl/pfl/?page" to "PFL",
        "$mainUrl/k-1/?page" to "K-1 / Kickboxing",
        "$mainUrl/cage-warriors/?page" to "Cage Warriors",
        "$mainUrl/invicta-fc/?page" to "Invicta FC",
        "$mainUrl/ksw/?page" to "KSW",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.substringBefore("?page")
        } else {
            "${request.data}$page"
        }
        val doc = app.get(url).document
        val items = doc.select("div.short_item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = doc.select("a.swchItem-next").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("div.short_content h3 a") ?: return null
        val title = titleEl.text().trim()
        val href = fixUrl(titleEl.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("div.poster img")?.attr("src"))
        val category = this.selectFirst("div.short_cat a")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Others) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(category)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/search/?q=$encoded").document
        return doc.select("div.short_item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.full_content h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: "Unknown Fight"

        val posterUrl = fixUrlNull(doc.selectFirst("div.full_img img")?.attr("src"))
        val description = doc.selectFirst("div.full_descr")?.text()?.trim()
            ?: doc.selectFirst("div.short_descr")?.text()?.trim()

        val category = doc.selectFirst("div.full_cat a")?.text()?.trim()

        // Collect all video links from the detail page as "episodes"
        val episodes = mutableListOf<Episode>()

        // Find all su-button links (video sources)
        val videoLinks = doc.select("a.su-button-style-default, a.su-button")
        var currentSection = "Full Event"

        doc.select("div.full_text p, div.full_text div, div.entry__article p, div.entry__article div").forEach { el ->
            // Check if this is a section header
            val text = el.text().trim()
            if (text.isNotEmpty() && el.selectFirst("strong, b") != null && el.select("a.su-button, a.su-button-style-default").isEmpty()) {
                if (!text.contains("---") && !text.startsWith("Disclaimer")) {
                    currentSection = text
                }
            }

            // Extract video links from this element
            el.select("a.su-button-style-default, a.su-button").forEach { linkEl ->
                val linkText = linkEl.text().trim()
                val href = linkEl.attr("href").let { h ->
                    if (h.startsWith("//")) "https:$h" else h
                }
                if (href.isNotEmpty() && !href.startsWith("javascript")) {
                    val epName = "$currentSection - $linkText"
                    episodes.add(newEpisode(href) {
                        this.name = epName
                    })
                }
            }
        }

        // Fallback: if no structured links found, try all links
        if (episodes.isEmpty()) {
            doc.select("a[href]").forEach { linkEl ->
                val href = linkEl.attr("href").let { h ->
                    if (h.startsWith("//")) "https:$h" else h
                }
                if (isVideoHost(href)) {
                    val linkText = linkEl.text().trim().ifEmpty { "Watch" }
                    episodes.add(newEpisode(href) {
                        this.name = linkText
                    })
                }
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = listOfNotNull(category)
            }
        } else {
            val dataUrl = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, TvType.Others, dataUrl) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = listOfNotNull(category)
            }
        }
    }

    private fun isVideoHost(url: String): Boolean {
        val hosts = listOf(
            "dailymotion.com", "dai.ly",
            "ok.ru",
            "filemoon", "bysesukior.com",
            "dood", "doodstream",
            "streamtape",
            "mixdrop",
            "upstream",
            "youtube.com", "youtu.be",
            "vk.com"
        )
        return hosts.any { url.contains(it, ignoreCase = true) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (data.startsWith("//")) "https:$data" else data

        // Try to use built-in extractors
        if (url.contains("dailymotion.com") || url.contains("dai.ly")) {
            // Handle geo.dailymotion.com/player.html?video=XXX format
            val videoId = if (url.contains("player.html?video=")) {
                url.substringAfter("video=").substringBefore("&")
            } else if (url.contains("/video/")) {
                url.substringAfter("/video/").substringBefore("?")
            } else null

            if (videoId != null) {
                loadExtractor("https://www.dailymotion.com/embed/video/$videoId", mainUrl, subtitleCallback, callback)
                return true
            }
        }

        if (url.contains("ok.ru")) {
            loadExtractor(url.replace("videoembed", "video"), mainUrl, subtitleCallback, callback)
            return true
        }

        // For other hosts, try the generic extractor
        loadExtractor(url, mainUrl, subtitleCallback, callback)
        return true
    }
}
