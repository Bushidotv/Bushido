@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.panates

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder

data class CmxServer(
    val type: String? = null,
    val streamUrl: String? = null,
    val src: String? = null,
    val label: String? = null,
    val embedId: Int? = null,
    val videoId: String? = null,
)

data class CmxStream(
    val id: Int? = null,
    val url: String? = null,
    val urls: List<CmxQuality>? = null,
    val captions: List<CmxCaption>? = null,
)

data class CmxQuality(
    val label: String? = null,
    val url: String? = null,
)

data class CmxCaption(
    val lang: String? = null,
    val name: String? = null,
    val url: String? = null,
)

class CizgiMax : MainAPI() {
    companion object {
        private const val TAG = "CizgiMax"
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override var mainUrl        = "https://cizgimax.online"
    override var name           = "CizgiMax"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Cartoon)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "/"                    to "Son Eklenenler",
        "/diziler/anime/"      to "Animeler",
        "/diziler/cizgi-film/" to "Çizgi Film",
        "/diziler/dizi/"       to "Çizgi Diziler",
        "/tur/aile/"           to "Aile",
        "/tur/aksiyon/"        to "Aksiyon",
        "/tur/komedi/"         to "Komedi",
        "/tur/bilim-kurgu/"    to "Bilim Kurgu",
        "/tur/cocuk/"          to "Çocuk"
    )

    /**
     * Two-tier fetch: try OkHttp+CloudflareKiller first (fast).
     * If the response is a Cloudflare challenge page, fall back
     * to a full WebView load that lets the browser engine solve
     * Turnstile natively.
     */
    private suspend fun safeFetch(url: String): Document? {
        // Tier 1 – OkHttp (fast, works when CF is not active)
        try {
            val res: NiceResponse = app.get(url, interceptor = cloudflareKiller)
            val text = res.text
            if (text.length > 500 && !text.contains("Just a moment")) {
                return res.document
            }
        } catch (e: Exception) {
            Log.d(TAG, "OkHttp attempt failed for $url: ${e.message}")
        }

        // Tier 2 – WebView (handles Turnstile)
        val ctx = CizgiMaxPlugin.pluginContext ?: return null
        return CizgiMaxWebView.fetchDocument(ctx, url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "$mainUrl${request.data}${sep}page=$page"
        } else {
            "$mainUrl${request.data}"
        }

        val document = safeFetch(url) ?: return newHomePageResponse(request.name, emptyList())
        val home = document
            .select("div.film-item, div.film-list div.film-item, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.size >= 10)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val nameEl = this.selectFirst("a.film-name, h2 a, h3 a, a.title, .film-name")
        val anchor = this.selectFirst("a.poster, a[href*='/dizi/'], a[href*='/film/'], a")

        val rawHref = nameEl?.attr("href")?.trim()?.ifBlank { null }
            ?: anchor?.attr("href")?.trim()?.ifBlank { null }
            ?: return null
        val href = fixUrlNull(rawHref) ?: return null

        val rawTitle = nameEl?.text()?.trim()?.ifBlank { null }
            ?: this.attr("data-anime-name").trim().ifBlank { null }
            ?: anchor?.attr("title")?.trim()?.ifBlank { null }
            ?: this.selectFirst("h1, h2, h3, h4, .title, .film-name")?.text()?.trim()?.ifBlank { null }
            ?: href.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

        if (rawTitle.isBlank()) return null

        val img = this.selectFirst("a.poster img, img")
        val poster = img?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank {
                    it.attr("srcset").split(" ").firstOrNull()?.ifBlank { null }
                        ?: it.attr("src")
                }
            }
        }

        return newTvSeriesSearchResponse(rawTitle, href, TvType.Cartoon) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = safeFetch("$mainUrl/ara/?q=$encoded") ?: return emptyList()
        return document
            .select("div.film-list div.film-item, div.film-item, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = safeFetch(url) ?: return null

        val title = document.selectFirst("div.anime-head-titles h1, h1.entry-title, h1")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.poster")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        )

        val description = document.selectFirst("p.anime-desc, div.synopsis, meta[property=og:description]")?.let {
            if (it.tagName().equals("meta", ignoreCase = true)) it.attr("content") else it.text()
        }?.trim()

        val tags = document.select("li.meta-genres strong a, div.genres a").mapNotNull { it.text().trim().ifBlank { null } }
        val rating = document.selectFirst("span.ep-score-value")?.text()?.trim()?.replace(",", ".")?.toRatingInt()

        val episodes = document.select("div.ep-grid-numbers").flatMap { pane ->
            val season = pane.attr("data-season-pane").toIntOrNull()

            pane.select("a.ep-num-btn").mapNotNull { ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val num  = ep.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull()
                val alt  = ep.attr("title").trim().ifBlank { ep.text().trim() }

                val epName = when {
                    num == null && alt.isBlank() -> return@mapNotNull null
                    num == null                  -> alt
                    alt.isBlank() || alt == "$num. Bölüm" -> "$num. Bölüm"
                    else                         -> "$num. Bölüm - $alt"
                }

                newEpisode(href) {
                    this.name    = epName
                    this.season  = season
                    this.episode = num
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = safeFetch(data) ?: return false
        val html = document.html()

        // Server list base64-embedded: var servers = JSON.parse(atob("..."))
        val b64 = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)""")
            .find(html)?.groupValues?.get(1)

        val servers: List<CmxServer> = if (b64 != null) {
            val decoded = try {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            } catch (_: Exception) { b64.replace("\\/", "/") }
            runCatching {
                ObjectMapper().readValue(decoded, object : TypeReference<List<CmxServer>>() {})
            }.getOrNull() ?: emptyList()
        } else {
            emptyList()
        }

        for (server in servers) {
            val route = server.streamUrl ?: server.src ?: continue

            if (server.type.equals("byse", true)) {
                val resolve = runCatching {
                    val resp = app.get(fixUrl(route), referer = data, interceptor = cloudflareKiller).text
                    ObjectMapper().readValue(resp, CmxStream::class.java)
                }.getOrNull() ?: continue

                val qualities = resolve.urls.orEmpty().filter { !it.url.isNullOrBlank() }
                for (quality in qualities) {
                    val link = quality.url ?: continue
                    callback.invoke(
                        newExtractorLink(
                            source = server.label ?: name,
                            name   = "${server.label ?: name} ${quality.label ?: ""}".trim(),
                            url    = fixUrl(link),
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("Referer" to "$mainUrl/")
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                if (qualities.isNotEmpty()) continue

                val direct = resolve.url
                if (!direct.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = server.label ?: name,
                            name   = server.label ?: name,
                            url    = fixUrl(direct),
                            type   = if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to "$mainUrl/")
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                continue
            }

            // iframe servers
            try {
                val resp = app.get(fixUrl(route), referer = data, interceptor = cloudflareKiller)
                val embedUrl = resp.url
                if (fixUrl(route) == embedUrl || embedUrl == null) continue
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        // Classic iframes
        for (frame in document.select("iframe[src]")) {
            val src = frame.attr("src").trim()
            if (src.isBlank() || src.contains("/oynat/") || !src.startsWith("http")) continue
            loadExtractor(fixUrl(src), "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
