package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DizipalProvider : MainAPI() {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://dizipal2139.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler" to "Son Bölümler",
        "${mainUrl}/diziler" to "Yeni Diziler",
        "${mainUrl}/filmler" to "Yeni Filmler",
        "${mainUrl}/platform/netflix" to "Netflix",
        "${mainUrl}/platform/exxen" to "Exxen",
        "${mainUrl}/platform/blutv" to "BluTV",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/prime-video" to "Amazon Prime",
        "${mainUrl}/platform/tabii" to "Tabii",
        "${mainUrl}/platform/gain" to "Gain",
        "${mainUrl}/platform/max" to "Max",
        "${mainUrl}/kategori/bilim-kurgu" to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi" to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel" to "Belgesel Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name = this.selectFirst(".ep-title")?.text()?.trim() ?: return null
        val episode = this.selectFirst(".ep-info")?.text()?.trim()
            ?.replace(". Sezon ", "x")
            ?.replace(". Bölüm", "") ?: return null
        val title = "$name $episode"

        val href = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
            .replace("/bolum/", "/dizi/")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("div.card-info h3")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val jsonResponse = try {
            AppUtils.parseJson<DizipalSearchData>(responseRaw.text)
        } catch (_: Exception) {
            return emptyList()
        }

        val searchResponses = mutableListOf<SearchResponse>()

        jsonResponse.results?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.url ?: return@forEach
            val poster = item.poster

            if (item.type.equals("Dizi", ignoreCase = true)) {
                searchResponses.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            } else {
                searchResponses.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            }
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a")
            .map { it.text().trim() }

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null

                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epEpisode
                    this.season = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
                ?: ""

            if (title.isEmpty()) return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
        )

        val document = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            return false
        }

        val cookies = getResponse.headers.values("Set-Cookie")
            .mapNotNull { it.split(";").firstOrNull()?.trim()?.takeIf { p -> p.contains("=") } }
            .distinct()
            .joinToString("; ")
            .ifEmpty {
                getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }

        val configJson: JSONObject = if (configToken.trimStart().startsWith("{")) {
            try {
                JSONObject(configToken)
            } catch (e: Exception) {
                return false
            }
        } else {
            val cfgResponse = app.post(
                url = "$mainUrl/ajax-player-config",
                data = mapOf("cfg" to configToken),
                referer = data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Cookie" to cookies
                )
            )
            try {
                JSONObject(cfgResponse.text)
            } catch (e: Exception) {
                return false
            }
        }

        val innerConfig = configJson.optJSONObject("config") ?: configJson

        val embedUrlRaw: String? = when {
            innerConfig.has("v") -> {
                val vObj = innerConfig.optJSONObject("v")
                if (vObj != null) {
                    decryptEnc(vObj)
                } else {
                    val vStr = innerConfig.optString("v", "").trim()
                    when {
                        vStr.startsWith("{") -> {
                            try {
                                decryptEnc(JSONObject(vStr))
                            } catch (e: Exception) {
                                vStr
                            }
                        }
                        vStr.isNotBlank() -> vStr
                        else -> null
                    }
                }
            }
            else -> null
        }

        if (embedUrlRaw.isNullOrEmpty()) {
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)

        // 1. Imagestoo Player Kontrolü
        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"

            val apiResponse = app.post(
                url = imagestooApiUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                )
            )

            var sessionCookie = ""
            val playerToken = apiResponse.cookies["fireplayer_player"]

            if (!playerToken.isNullOrEmpty()) {
                sessionCookie = "fireplayer_player=$playerToken"
            } else {
                val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]
                if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                    val cleanCookie = rawSetCookie.split(";").firstOrNull()
                    if (cleanCookie != null) {
                        sessionCookie = "$cleanCookie;"
                    }
                }
            }

            val responseText = apiResponse.text
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.getOrNull(1)

            if (videoSourceRaw != null) {
                val cleanUrl = videoSourceRaw.replace("\\/", "/")
                val finalM3u8Url = fixUrl(cleanUrl)

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Imagestoo)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.headers = mapOf("Cookie" to sessionCookie)
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }

        // 2. Ana Sunucu Embed / M3U8 Kontrolü
        val embedSource = try {
            app.get(
                url = embedUrl,
                referer = data,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (_: Exception) { "" }

        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)
            ?: embedUrl.takeIf { it.contains("embed-") && it.contains(".html") }

        var hasFoundLink = false

        if (extractedUrl != null) {
            val finalM3u8Url = if (extractedUrl.contains(".html")) {
                val idMatch = Regex("""embed-([^.]+)\.html""").find(extractedUrl)?.groupValues?.getOrNull(1)
                if (idMatch != null) {
                    "https://s7.superadjacentsoddenly.xyz/hls2/01/00016/${idMatch}_,n,h,.urlset/master.m3u8"
                } else {
                    null
                }
            } else {
                extractedUrl
            }

            if (finalM3u8Url != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Ana Sunucu)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                hasFoundLink = true
            }
        }

        // 3. Altyazıları Yakala
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource)
        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]
                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)

                val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                val label = labelMatch?.groupValues?.getOrNull(1) ?: "Türkçe"

                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = fixUrl(fileUrl)
                        )
                    )
                }
            }
        }

        // 4. Standart Extractor API Fallback
        if (!hasFoundLink) {
            hasFoundLink = loadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        return hasFoundLink
    }

    private fun b64ToBytes(s: String): ByteArray? = try {
        val normalized = s.replace('-', '+').replace('_', '/')
            .padEnd((s.length + 3) / 4 * 4, '=')
        Base64.decode(normalized, Base64.DEFAULT)
    } catch (_: Exception) { null }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        return ByteArray(len) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    private fun decryptEnc(enc: JSONObject): String? {
        return try {
            val k1 = b64ToBytes(enc.getString("k1")) ?: return null
            val k2 = b64ToBytes(enc.getString("k2")) ?: return null
            val iv = b64ToBytes(enc.getString("iv")) ?: return null
            val ct = b64ToBytes(enc.getString("c")) ?: return null

            val key = xorBytes(k1, k2)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            String(cipher.doFinal(ct), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
