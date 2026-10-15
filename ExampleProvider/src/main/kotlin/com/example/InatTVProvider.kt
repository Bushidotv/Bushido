package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class InatTVProvider : MainAPI() {
    override var name = "İnat TV"
    override var mainUrl = "https://www.inattvizle493.top"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "live" to "Canlı Spor Kanalları"
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private const val DOMAINS_JSON_URL =
            "https://raw.githubusercontent.com/Wiojelt/TurkSpor/main/domains.json"

        private const val GATEWAY_URL =
            "https://www.inatgiris.com/"

        @Volatile
        private var cachedDomain: String? = null

        @Volatile
        private var cachedAuthUrl: String? = null

        @Volatile
        private var cachedSiteName: String = "inatTV"
    }

    private data class AuthResponse(
        @JsonProperty("URL") val url: String? = null,
        @JsonProperty("TOKEN") val token: String? = null,
        @JsonProperty("ERROR") val error: String? = null
    )

    private data class DomainsConfig(
        @JsonProperty("sources") val sources: Map<String, DomainSource>? = null
    )

    private data class DomainSource(
        @JsonProperty("candidates") val candidates: List<String>? = null,
        @JsonProperty("gateways") val gateways: List<String>? = null
    )

    private suspend fun resolveDomain(): String {
        cachedDomain?.let { return it }

        val candidateList = mutableListOf<String>()

        // 1. Try inatgiris.com gateway
        try {
            val gatewayResp = app.get(GATEWAY_URL, headers = mapOf("User-Agent" to USER_AGENT), timeout = 8)
            val gatewayHtml = gatewayResp.text
            val redirectUrl = gatewayResp.url
            if (redirectUrl.contains("inattvizle")) {
                candidateList.add(normalizeUrl(redirectUrl))
            }
            val match = Regex("""https?://(?:www\.)?inattvizle\d+\.top/? """.trim()).find(gatewayHtml)
            if (match != null) {
                candidateList.add(normalizeUrl(match.value))
            }
        } catch (_: Throwable) {}

        // 2. Try TurkSpor GitHub domains.json
        try {
            val jsonText = app.get(DOMAINS_JSON_URL, timeout = 5).text
            val mapper = jacksonObjectMapper()
            val config = mapper.readValue<DomainsConfig>(jsonText)
            val inatCandidates = config.sources?.get("inattv")?.candidates
            if (inatCandidates != null) {
                candidateList.addAll(inatCandidates.map { normalizeUrl(it) })
            }
        } catch (_: Throwable) {}

        // 3. Built-in candidate fallbacks
        candidateList.addAll(
            listOf(
                "https://www.inattvizle493.top/",
                "https://www.inattvizle492.top/",
                "https://www.inattvizle494.top/",
                "https://www.inattvizle495.top/",
                "https://inattvizle493.top/",
                "https://inattvizle492.top/"
            )
        )

        for (domain in candidateList.distinct()) {
            try {
                val resp = app.get(domain, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6)
                if (resp.code == 200) {
                    val html = resp.text
                    val doc = Jsoup.parse(html)
                    val title = doc.title().lowercase()
                    if (title.contains("inat") || html.contains("channel-slider")) {
                        cachedDomain = domain
                        mainUrl = domain
                        parseConfig(html, domain)
                        return domain
                    }
                }
            } catch (_: Throwable) {}
        }

        return mainUrl
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (!trimmed.endsWith("/")) "$trimmed/" else trimmed
    }

    private fun parseConfig(html: String, domainUrl: String) {
        try {
            val phptojsMatch = Regex("""var\s+phptojs\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (phptojsMatch != null) {
                val js = phptojsMatch.groupValues[1]
                val cdnMatch = Regex("""['"]stream_server_cdn_url['"]\s*:\s*['"]([^'"]*)['"]""").find(js)
                val siteMatch = Regex("""['"]site_name['"]\s*:\s*['"]([^'"]*)['"]""").find(js)

                val cdnUrl = cdnMatch?.groupValues?.getOrNull(1)
                val siteName = siteMatch?.groupValues?.getOrNull(1)

                if (!siteName.isNullOrBlank()) {
                    cachedSiteName = siteName
                }

                if (!cdnUrl.isNullOrBlank()) {
                    val baseUri = URI(domainUrl)
                    val resolvedCdn = baseUri.resolve(cdnUrl)
                    val authUri = resolvedCdn.resolve("auth.php")
                    cachedAuthUrl = authUri.toString()
                }
            }
        } catch (_: Throwable) {}

        if (cachedAuthUrl == null) {
            cachedAuthUrl = URI(domainUrl).resolve("auth.php").toString()
        }
    }

    private suspend fun fetchChannels(): List<SearchResponse> {
        val domain = resolveDomain()
        val doc = try {
            val resp = app.get(domain, headers = mapOf("User-Agent" to USER_AGENT))
            parseConfig(resp.text, domain)
            Jsoup.parse(resp.text)
        } catch (e: Throwable) {
            return emptyList()
        }

        val items = doc.select("#channel-slider .channel-item[data-name][data-target][data-source]")
        val responses = mutableListOf<SearchResponse>()

        for (item in items) {
            val name = item.attr("data-name").trim()
            val target = item.attr("data-target").trim()
            val source = item.attr("data-source").trim()

            if (name.isEmpty() || source.isEmpty()) continue

            val encodedName = URLEncoder.encode(name, "UTF-8")
            val channelUrl = "$domain?channel=$source&target=$target&name=$encodedName"

            val logoUrl = getChannelLogo(name)

            responses.add(
                newLiveSearchResponse(
                    name = name,
                    url = channelUrl,
                    type = TvType.Live
                ) {
                    this.posterUrl = logoUrl
                }
            )
        }

        return responses
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        return newHomePageResponse(request, channels)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchChannels().filter {
            it.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val nameParam = extractQueryParam(url, "name")
        val channelName = if (nameParam != null) {
            try { URLDecoder.decode(nameParam, "UTF-8") } catch (_: Throwable) { nameParam }
        } else {
            "Canlı Yayın"
        }

        val logoUrl = getChannelLogo(channelName)

        return newLiveStreamLoadResponse(
            name = channelName,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = logoUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = extractQueryParam(data, "target") ?: "viptv"
        val channel = extractQueryParam(data, "channel") ?: return false
        val nameParam = extractQueryParam(data, "name")
        val channelName = if (nameParam != null) {
            try { URLDecoder.decode(nameParam, "UTF-8") } catch (_: Throwable) { nameParam }
        } else {
            "İnat TV"
        }

        if (target == "m3u8") {
            // Direct m3u8 stream
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$channelName (Canlı)",
                    url = channel,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        // viptv target - requires auth.php token resolution
        val domain = resolveDomain()
        val authUrl = cachedAuthUrl ?: URI(domain).resolve("auth.php").toString()
        val origin = domain.trimEnd('/')

        val authHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to origin,
            "Referer" to domain,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        val authResp = try {
            app.post(
                authUrl,
                headers = authHeaders,
                data = mapOf("channel" to channel),
                referer = domain,
                timeout = 10
            )
        } catch (e: Throwable) {
            return false
        }

        if (authResp.code != 200) return false

        val mapper = jacksonObjectMapper()
        val authData = try {
            mapper.readValue<AuthResponse>(authResp.text)
        } catch (e: Throwable) {
            return false
        }

        val streamUrl = authData.url
        val token = authData.token ?: ""

        if (streamUrl.isNullOrBlank()) return false

        val streamHeaders = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to domain,
            "Origin" to origin
        )
        if (token.isNotEmpty()) {
            streamHeaders["usertoken"] = token
            streamHeaders["pl"] = cachedSiteName
        }

        // Try inspecting the m3u8 playlist to see if multiple qualities exist
        var hasEmittedVariants = false
        try {
            val playlistResp = app.get(streamUrl, headers = streamHeaders, timeout = 8)
            val playlistText = playlistResp.text.trimStart()

            if (playlistText.startsWith("#EXTM3U")) {
                val lines = playlistText.lines()
                val streamBaseUri = URI(streamUrl)

                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        // find variant url in subsequent line
                        val variantLine = lines.getOrNull(i + 1)?.trim()
                        if (!variantLine.isNullOrBlank() && !variantLine.startsWith("#")) {
                            val variantUrl = streamBaseUri.resolve(variantLine).toString()
                            val quality = parseResolutionQuality(line)
                            val qualityLabel = if (quality > 0) "${quality}p" else "Otomatik"

                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$channelName ($qualityLabel)",
                                    url = variantUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = domain
                                    this.headers = streamHeaders
                                    this.quality = if (quality > 0) quality else Qualities.Unknown.value
                                }
                            )
                            hasEmittedVariants = true
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Emit master stream link if no variants found
        if (!hasEmittedVariants) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$channelName (Canlı)",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = domain
                    this.headers = streamHeaders
                    this.quality = Qualities.P1080.value
                }
            )
        }

        return true
    }

    private fun parseResolutionQuality(infLine: String): Int {
        val match = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(infLine)
        if (match != null) {
            val height = match.groupValues[2].toIntOrNull()
            if (height != null) return height
        }
        return Qualities.Unknown.value
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val qIndex = url.indexOf('?')
        if (qIndex == -1) return null
        val query = url.substring(qIndex + 1)
        for (pair in query.split('&')) {
            val parts = pair.split('=')
            if (parts.size == 2 && parts[0] == key) {
                return parts[1]
            }
        }
        return null
    }

    private fun getChannelLogo(channelName: String): String {
        val nameLower = channelName.lowercase()
        return when {
            nameLower.contains("bein 1") || nameLower.contains("bein sports 1") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-1-hz-int.png"
            nameLower.contains("bein 2") || nameLower.contains("bein sports 2") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-2-hz-int.png"
            nameLower.contains("bein 3") || nameLower.contains("bein sports 3") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-3-hz-int.png"
            nameLower.contains("bein 4") || nameLower.contains("bein sports 4") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-4-hz-int.png"
            nameLower.contains("bein 5") || nameLower.contains("bein sports 5") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-5-hz-int.png"
            nameLower.contains("bein max 1") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-max-1-hz-int.png"
            nameLower.contains("bein max 2") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/bein-sports-max-2-hz-int.png"
            nameLower.contains("s-sport 1") || nameLower.contains("s sport 1") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-tr.png"
            nameLower.contains("s-sport 2") || nameLower.contains("s sport 2") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/s-sport-2-tr.png"
            nameLower.contains("tivibuspor 1") || nameLower.contains("tivibu spor 1") ->
                "https://i.imgur.com/xLrgt2O.png"
            nameLower.contains("tivibuspor 2") || nameLower.contains("tivibu spor 2") ->
                "https://i.imgur.com/LgGxe7z.png"
            nameLower.contains("tivibuspor 3") || nameLower.contains("tivibu spor 3") ->
                "https://i.imgur.com/qvrKQY3.png"
            nameLower.contains("tivibuspor 4") || nameLower.contains("tivibu spor 4") ->
                "https://i.imgur.com/fZMSjNE.png"
            nameLower.contains("smartspor 1") || nameLower.contains("smart spor 1") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/spor-smart-hd-tr.png"
            nameLower.contains("smartspor 2") || nameLower.contains("smart spor 2") ->
                "https://i.imgur.com/qyUKCUa.png"
            nameLower.contains("trt spor y") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-yildiz-tr.png"
            nameLower.contains("trt spor") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-spor-tr.png"
            nameLower.contains("trt 1") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/trt-1-tr.png"
            nameLower.contains("a spor") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/a-spor-tr.png"
            nameLower.contains("tv8,5") || nameLower.contains("tv 8,5") ->
                "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/tv85-tr.png"
            nameLower.contains("nba") ->
                "https://upload.wikimedia.org/wikipedia/en/thumb/d/d2/NBA_TV.svg/960px-NBA_TV.svg.png"
            nameLower.contains("eurosport 1") ->
                "https://i.imgur.com/olQJgm7.png"
            nameLower.contains("eurosport 2") ->
                "https://i.imgur.com/f56dHgR.png"
            else ->
                "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
        }
    }
}
