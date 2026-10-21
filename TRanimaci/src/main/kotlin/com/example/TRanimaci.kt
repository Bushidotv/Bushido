package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TRanimaci : MainAPI() {
    companion object {
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
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
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
        "${mainUrl}/en/kategori/shounen-anime"     to "Shounen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1) {
            if (baseUrl.contains("?")) "$baseUrl&page=$page" else "$baseUrl?page=$page"
        } else {
            baseUrl
        }

        val res = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            )
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val document = res.document
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
            } catch (_: Exception) {}
        }

        // 2. DiziPal Card Scraper Architecture
        if (home.isEmpty()) {
            val selectors = listOf(
                "ul.content-grid > li",
                "div.content-grid > div",
                "div.episodes-list-grid > a",
                "div.grid > div",
                "article",
                "div.anime-card",
                "div.card",
                "a.dp-poster",
                "a.dp-episode",
                "a[href*='/anime/']",
                "a[href*='/series/']",
                "a[href*='/video/']",
                "a[href*='bolum']"
            )

            for (selector in selectors) {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    elements.mapNotNull { it.toSearchResult() }.forEach { home.add(it) }
                    if (home.isNotEmpty()) break
                }
            }
        }

        val distinct = home.distinctBy { it.url }
        return newHomePageResponse(request.name, distinct, hasNext = distinct.size >= 10)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName().equals("a", ignoreCase = true)) {
            this
        } else {
            this.selectFirst("a[href*='/anime/'], a[href*='/series/'], a[href*='/video/'], a[href*='bolum'], a") ?: return null
        }

        val rawHref = anchor.attr("href").ifBlank { return null }
        val href = fixUrlNull(rawHref) ?: return null

        // Ignore utility links
        if (href.contains("/kategori/") || href.contains("/login") || href.contains("/register") || href == "$mainUrl/" || href == "$mainUrl/en") {
            return null
        }

        val imgElement = this.selectFirst("img") ?: anchor.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.ifBlank { null }
                ?: imgElement?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgElement?.attr("srcset")?.split(" ")?.firstOrNull()?.ifBlank { null }
                ?: imgElement?.attr("src")?.ifBlank { null }
        )

        val rawTitle = this.selectFirst("h1, h2, h3, h4, .title, .name, .card-title, .anime-title, .film-title, .ep-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: imgElement?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

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
        val res = try {
            app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            )
        } catch (_: Exception) {
            return emptyList()
        }

        val document = res.document
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
            document.select("a[href*='/anime/'], a[href*='/series/'], article, div.anime-card, div.card, ul.content-grid > li")
                .mapNotNull { it.toSearchResult() }
                .forEach { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val res = try {
            app.get(
                fixedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            )
        } catch (_: Exception) {
            return null
        }

        val document = res.document

        val title = document.selectFirst("h1.series-title, h1.anime-title, h1.entry-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.poster img, img.poster, div.anime-poster img")?.attr("data-src")
                ?: document.selectFirst("div.poster img, img.poster, div.anime-poster img")?.attr("src")
        )

        val description = document.selectFirst("div.synopsis, div.anime-details p, p.series-description, meta[property=og:description]")?.let {
            if (it.tagName().equals("meta", ignoreCase = true)) it.attr("content") else it.text()
        }?.trim()

        val year = document.selectFirst("div.info-row:contains(Yıl), div.meta:contains(Yıl), span.year")?.text()
            ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = document.select("div.genres a, div.categories a, a[href*='/kategori/']").map { it.text().trim() }

        val episodes = mutableListOf<Episode>()

        // Check __NEXT_DATA__
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

        // HTML Episode parsing
        if (episodes.isEmpty()) {
            document.select("div.episodes a, ul.episodes-list a, a[href*='/video/'], a[href*='/bolum/'], a[href*='-bolum']").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epText = a.text().trim().ifBlank { a.attr("title") }
                val epNum = Regex("""(\d+)\.\s*[Bb]ölüm|(\d+)\s*[Bb]ölüm|bolum-(\d+)|episode-(\d+)""", RegexOption.IGNORE_CASE)
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
        val res = try {
            app.get(
                fixedData,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            )
        } catch (_: Exception) {
            return false
        }

        val document = res.document
        var hasFound = false

        // 1. Check iframes
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

        // 2. Direct video sources
        val videoSources = document.select("video source").mapNotNull { it.attr("src") }
        for (vSrc in videoSources) {
            val fixed = fixUrl(vSrc)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "TRanimeci Stream",
                    url = fixed,
                    type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = fixedData
                }
            )
            hasFound = true
        }

        // 3. Fallback: try extractor on the data page itself
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
            if (value is JSONArray && key.contains("episode", ignoreCase = true) || key.contains("bolum", ignoreCase = true)) {
                for (i in 0 until value.length()) {
                    val item = value.optJSONObject(i) ?: continue
                    val epNum = item.optInt("episode_number", item.optInt("number", i + 1))
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
            } else if (value is JSONObject) {
                parseNextDataEpisodes(value, episodes)
            }
        }
    }
}
