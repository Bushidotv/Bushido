package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TRanimaci : MainAPI() {
    companion object {
        private const val TAG = "TRanimaci"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://tranimaci.com"
    override var name = "TRanimeci"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "${mainUrl}/"                              to "Ana Sayfa",
        "${mainUrl}/en"                            to "Son Eklenenler",
        "${mainUrl}/anime"                         to "Animeler",
        "${mainUrl}/en/anime"                      to "Tüm Animeler",
        "${mainUrl}/anime-filmleri"                to "Anime Filmleri",
        "${mainUrl}/kategori/aksiyon-anime"        to "Aksiyon",
        "${mainUrl}/kategori/komedi-anime"         to "Komedi",
        "${mainUrl}/kategori/fantastik-anime"      to "Fantastik",
        "${mainUrl}/kategori/romantizm-anime"      to "Romantizm",
        "${mainUrl}/kategori/shounen-anime"        to "Shounen"
    )

    private suspend fun safeGet(url: String, referer: String = "$mainUrl/"): Document? {
        return try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer
                ),
                interceptor = cloudflareKiller
            ).document
        } catch (e: Exception) {
            Log.e(TAG, "Request failed for $url: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1) {
            if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
        } else {
            baseUrl
        }

        val document = safeGet(url) ?: return newHomePageResponse(request.name, emptyList())
        val home = mutableListOf<SearchResponse>()

        // 1. Next.js __NEXT_DATA__ JSON Parser
        val nextDataScript = document.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextDataScript.isNullOrBlank()) {
            try {
                val json = JSONObject(nextDataScript)
                val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                if (pageProps != null) {
                    parseNextDataItems(pageProps, home)
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error: ${e.message}")
            }
        }

        // 2. HTML Scraper (DiziPal Card & Anchor pattern)
        if (home.isEmpty()) {
            // Find all anchor links to anime or episodes
            val elements = document.select(
                "ul.content-grid > li, div.content-grid > div, div.grid > div, article, " +
                "div.anime-card, div.card, a[href*='/anime/'], a[href*='/series/'], a[href*='/video/'], a[href*='bolum']"
            )

            for (element in elements) {
                val item = element.toSearchResult()
                if (item != null) {
                    home.add(item)
                }
            }
        }

        val distinct = home.distinctBy { it.url }
        return newHomePageResponse(request.name, distinct, hasNext = distinct.size >= 8)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName().equals("a", ignoreCase = true)) {
            this
        } else {
            this.selectFirst("a[href*='/anime/'], a[href*='/series/'], a[href*='/video/'], a[href*='bolum'], a") ?: return null
        }

        val rawHref = anchor.attr("href").trim()
        if (rawHref.isBlank() || rawHref == "#" || rawHref.startsWith("javascript:")) return null
        val href = fixUrlNull(rawHref) ?: return null

        // Ignore utility / auth links
        if (href.contains("/kategori/") || href.contains("/login") || href.contains("/register") ||
            href == "$mainUrl" || href == "$mainUrl/" || href == "$mainUrl/en" || href == "$mainUrl/en/") {
            return null
        }

        val imgElement = this.selectFirst("img") ?: anchor.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("srcset")?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        )

        // Bulletproof Title extraction: DOM elements -> attributes -> URL slug fallback
        val domTitle = this.selectFirst("h1, h2, h3, h4, h5, p, span, .title, .name, .card-title, .anime-title, .film-title, .ep-title")?.text()?.trim()
        val attrTitle = anchor.attr("title").trim()
        val altTitle = imgElement?.attr("alt")?.trim()
        val rawTitle = if (!domTitle.isNullOrBlank() && domTitle.length < 80) {
            domTitle
        } else if (attrTitle.isNotBlank()) {
            attrTitle
        } else if (!altTitle.isNullOrBlank()) {
            altTitle
        } else {
            // Slug fallback (e.g. /anime/naruto-shippuuden -> Naruto shippuuden)
            href.trimEnd('/').substringAfterLast('/')
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
        }

        val cleanTitle = rawTitle.replace(Regex("""(?i)\s*(izle|full\s*hd|hd|türkçe\s*altyazı.*)$"""), "").trim()
        if (cleanTitle.isBlank()) return null

        val isEpisode = href.contains("/video/") || href.contains("/bolum/") || href.contains("-bolum")

        return if (isEpisode) {
            val seriesUrl = href
                .replace(Regex("""-\d+-bolum.*$"""), "")
                .replace(Regex("""-bolum.*$"""), "")
                .replace("/video/", "/anime/")
                .replace("/bolum/", "/anime/")

            newTvSeriesSearchResponse(cleanTitle, seriesUrl, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(cleanTitle, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/en/ara?q=$query"
        val document = safeGet(searchUrl) ?: safeGet("$mainUrl/ara?q=$query") ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        val nextDataScript = document.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextDataScript.isNullOrBlank()) {
            try {
                val json = JSONObject(nextDataScript)
                val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                if (pageProps != null) {
                    parseNextDataItems(pageProps, results)
                }
            } catch (_: Exception) {}
        }

        if (results.isEmpty()) {
            val elements = document.select("a[href*='/anime/'], a[href*='/series/'], article, div.anime-card, div.card, ul.content-grid > li")
            for (element in elements) {
                val item = element.toSearchResult()
                if (item != null) results.add(item)
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val document = safeGet(fixedUrl) ?: return null

        val title = document.selectFirst("h1.series-title, h1.anime-title, h1.entry-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: fixedUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.poster img, img.poster, div.anime-poster img")?.attr("data-src")
                ?: document.selectFirst("div.poster img, img.poster, div.anime-poster img")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
        )

        val description = document.selectFirst("div.synopsis, div.anime-details p, p.series-description, meta[property=og:description]")?.let {
            if (it.tagName().equals("meta", ignoreCase = true)) it.attr("content") else it.text()
        }?.trim()

        val year = document.selectFirst("div.info-row:contains(Yıl), div.meta:contains(Yıl), span.year")?.text()
            ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = document.select("div.genres a, div.categories a, a[href*='/kategori/']").map { it.text().trim() }

        val episodes = mutableListOf<Episode>()

        // 1. Next.js episodes
        val nextDataScript = document.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextDataScript.isNullOrBlank()) {
            try {
                val json = JSONObject(nextDataScript)
                val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                if (pageProps != null) {
                    parseNextDataEpisodes(pageProps, episodes)
                }
            } catch (_: Exception) {}
        }

        // 2. HTML episodes
        if (episodes.isEmpty()) {
            document.select("div.episodes a, ul.episodes-list a, a[href*='/video/'], a[href*='/bolum/'], a[href*='bolum'], a[href*='episode']").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epText = a.text().trim().ifBlank { a.attr("title") }
                val epNum = Regex("""(?:bölüm|bolum|episode)\s*[-.:]?\s*(\d+)|(\d+)\.\s*(?:bölüm|bolum)|bolum-(\d+)|episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find("$epHref $epText")?.let { m ->
                        m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                    }

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epText.ifBlank { "Bölüm ${epNum ?: 1}" }
                        this.episode = epNum
                    }
                )
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, fixedUrl, TvType.Anime, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, fixedUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = fixUrl(data)
        val document = safeGet(fixedData) ?: return false
        var hasFound = false

        // 1. Iframes
        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank()) src else null
        }

        for (rawSrc in iframes) {
            val src = fixUrl(rawSrc)
            if (loadExtractor(src, fixedData, subtitleCallback, callback)) {
                hasFound = true
            }
        }

        // 2. Video elements
        val videoSources = document.select("video source, video").mapNotNull {
            it.attr("src").takeIf { s -> s.isNotBlank() }
        }
        for (vSrc in videoSources) {
            val fixed = fixUrl(vSrc)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "TRanimeci Direct",
                    url = fixed,
                    type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = fixedData
                }
            )
            hasFound = true
        }

        // 3. Fallback
        if (!hasFound) {
            hasFound = loadExtractor(fixedData, "$mainUrl/", subtitleCallback, callback)
        }

        return hasFound
    }

    private fun parseNextDataItems(node: JSONObject, results: MutableList<SearchResponse>) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val item = value.optJSONObject(i) ?: continue
                    val title = item.optString("title").ifBlank {
                        item.optString("name").ifBlank { item.optString("anime_name") }
                    }
                    val slug = item.optString("slug").ifBlank {
                        item.optString("id")
                    }

                    if (title.isNotBlank() && slug.isNotBlank()) {
                        val poster = item.optString("poster").ifBlank {
                            item.optString("image").ifBlank {
                                item.optString("cover")
                            }
                        }
                        val cleanHref = fixUrlNull("$mainUrl/en/anime/$slug") ?: continue
                        results.add(
                            newAnimeSearchResponse(title, cleanHref, TvType.Anime) {
                                this.posterUrl = fixUrlNull(poster)
                            }
                        )
                    }
                }
            } else if (value is JSONObject) {
                parseNextDataItems(value, results)
            }
        }
    }

    private fun parseNextDataEpisodes(node: JSONObject, episodes: MutableList<Episode>) {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if (value is JSONArray) {
                val isEpisodeKey = key.contains("episode", ignoreCase = true) || key.contains("bolum", ignoreCase = true)
                if (isEpisodeKey) {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        val epNum: Int = if (item.has("episode_number")) {
                            item.optInt("episode_number")
                        } else if (item.has("number")) {
                            item.optInt("number")
                        } else {
                            i + 1
                        }
                        val epSlug = item.optString("slug").ifBlank { "$epNum" }
                        val epTitle = item.optString("title").ifBlank { "Bölüm $epNum" }
                        val epUrl = fixUrlNull("$mainUrl/en/video/$epSlug") ?: continue

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle
                                this.episode = epNum
                            }
                        )
                    }
                }
            } else if (value is JSONObject) {
                parseNextDataEpisodes(value, episodes)
            }
        }
    }
}
