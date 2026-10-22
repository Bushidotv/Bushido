@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.panates

import android.util.Log
import org.jsoup.Jsoup
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
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override var mainUrl        = "https://cizgimax.online"
    override var name           = "CizgiMax"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Cartoon)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

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

    private suspend fun safeGet(url: String, referer: String = "$mainUrl/"): NiceResponse? {
        val context = CizgiMaxPlugin.pluginContext

        if (CizgiMaxCloudflare.clearanceCookie.isNullOrBlank() && context != null) {
            CizgiMaxCloudflare.resolveClearance(context, "$mainUrl/")
        }

        val ua = CizgiMaxCloudflare.userAgent ?: DEFAULT_USER_AGENT
        val cookie = CizgiMaxCloudflare.clearanceCookie

        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to referer
        )
        if (!cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
        }

        return try {
            val res = app.get(
                url,
                headers = headers,
                interceptor = cloudflareKiller
            )

            if (res.text.contains("Just a moment") && context != null) {
                CizgiMaxCloudflare.clearanceCookie = null
                val newCookie = CizgiMaxCloudflare.resolveClearance(context, url)
                if (!newCookie.isNullOrBlank()) {
                    headers["Cookie"] = newCookie
                    app.get(url, headers = headers, interceptor = cloudflareKiller)
                } else {
                    res
                }
            } else {
                res
            }
        } catch (e: Exception) {
            Log.e(TAG, "safeGet error for $url: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "$mainUrl${request.data}${sep}page=$page"
        } else {
            "$mainUrl${request.data}"
        }

        val res = safeGet(url) ?: return newHomePageResponse(request.name, emptyList())
        val home = res.document
            .select("div.film-item, div.film-list div.film-item, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.size >= 10)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val nameEl = this.selectFirst("a.film-name, h2 a, h3 a, a.title, .film-name")
        val anchor = this.selectFirst("a.poster, a[href*='/dizi/'], a[href*='/film/'], a")

        val rawHref = nameEl?.attr("href")?.trim()
            ?.ifBlank { anchor?.attr("href")?.trim() }
            ?: return null
        val href = fixUrlNull(rawHref) ?: return null

        val rawTitle = nameEl?.text()?.trim()
            ?.ifBlank { this.attr("data-anime-name").trim() }
            ?.ifBlank { anchor?.attr("title")?.trim() }
            ?.ifBlank { this.selectFirst("h1, h2, h3, h4, .title, .film-name")?.text()?.trim() }
            ?: href.trimEnd('/').substringAfterLast('/')
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }

        if (rawTitle.isBlank()) return null

        val img = this.selectFirst("a.poster img, img")
        val poster = img?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank {
                    it.attr("srcset").split(" ").firstOrNull()?.ifBlank {
                        it.attr("src")
                    }
                }
            }
        }

        return newTvSeriesSearchResponse(rawTitle, href, TvType.Cartoon) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val res = safeGet("$mainUrl/ara/?q=$encoded") ?: return emptyList()
        return res.document
            .select("div.film-list div.film-item, div.film-item, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val res = safeGet(url) ?: return null
        val document = res.document

        val title = document.selectFirst("div.anime-head-titles h1, h1.entry-title, h1")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

        val poster = fixUrlNull(
            document.selectFirst("div.anime-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.poster")?.attr("data-src")
                ?: document.selectFirst("img.poster")?.attr("src")
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
        val res = safeGet(data) ?: return false
        val html = res.text

        // Server list base64-embedded: var servers = JSON.parse(atob("..."))
        val servers = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)""")
            .find(html)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.let { json -> runCatching { ObjectMapper().readValue(json, object : TypeReference<List<CmxServer>>() {}) }.getOrNull() }
            ?: emptyList()

        for (server in servers) {
            val route = server.streamUrl ?: server.src ?: continue

            // byse servers: /api/stream sequence -> json {url, urls[], captions}
            if (server.type.equals("byse", true)) {
                val resolve = runCatching {
                    val resp = safeGet(fixUrl(route), referer = data)?.text ?: ""
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

            // iframe servers: /oynat/?t=<jwt> 302-redirects to the embed host
            try {
                val resp = safeGet(fixUrl(route), referer = data)
                val embedUrl = resp?.url
                if (fixUrl(route) == embedUrl || embedUrl == null) continue
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
                // embed unreachable - try next server
            }
        }

        // Classic extractor chain for direct iframes on the page
        for (frame in Jsoup.parse(html).select("iframe[src]")) {
            val src = frame.attr("src").trim()
            if (src.isBlank() || src.contains("/oynat/") || !src.startsWith("http")) continue
            loadExtractor(fixUrl(src), "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
