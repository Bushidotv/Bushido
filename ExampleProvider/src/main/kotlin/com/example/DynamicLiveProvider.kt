package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class DynamicLiveProvider : MainAPI() {
    override var name = "Bushido TR"
    override var mainUrl = "https://netspor70.top"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        Pair("channels", "7/24 TV Kanallari")
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val LOGO_BASE =
            "https://raw.githubusercontent.com/nftdisk-cmyk/TestPlugins/master/netspor_logos"

        private val DEFAULT_CHANNELS = listOf(
            Pair("BEIN SPORTS 1", "/canli-mac/bein-sports-1"),
            Pair("BEIN SPORTS 2", "/canli-mac/bein-sports-2"),
            Pair("BEIN SPORTS 3", "/canli-mac/bein-sports-3"),
            Pair("BEIN SPORTS 4", "/canli-mac/bein-sports-4"),
            Pair("BEIN SPORTS 5", "/canli-mac/bein-sports-5"),
            Pair("BEIN SPORTS MAX 1", "/canli-mac/bein-sports-max-1"),
            Pair("BEIN SPORTS MAX 2", "/canli-mac/bein-sports-max-2"),
            Pair("S SPORT", "/canli-mac/s-sport"),
            Pair("S SPORT 2", "/canli-mac/s-sport-2"),
            Pair("TRT SPOR", "/canli-mac/trt-spor"),
            Pair("TRT 1", "/canli-mac/trt-1"),
            Pair("A SPOR", "/canli-mac/a-spor")
        )

        fun decodeBase64(input: String): String {
            val clean = input.trim()
            val pad = clean.length % 4
            val padded = if (pad > 0) clean + "=".repeat(4 - pad) else clean
            return try {
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Throwable) {
                try {
                    String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
                } catch (e2: Throwable) {
                    ""
                }
            }
        }

        fun formatChannelName(name: String): String {
            val lower = name.lowercase().trim()
            return when {
                "max 1" in lower || "max-1" in lower || "max1" in lower -> "BEIN SPORTS MAX 1"
                "max 2" in lower || "max-2" in lower || "max2" in lower -> "BEIN SPORTS MAX 2"
                "bein" in lower && "1" in lower -> "BEIN SPORTS 1"
                "bein" in lower && "2" in lower -> "BEIN SPORTS 2"
                "bein" in lower && "3" in lower -> "BEIN SPORTS 3"
                "bein" in lower && "4" in lower -> "BEIN SPORTS 4"
                "bein" in lower && "5" in lower -> "BEIN SPORTS 5"
                "s sport 2" in lower || "s-sport 2" in lower || "s sport2" in lower || "ssport 2" in lower || "ssport2" in lower -> "S SPORT 2"
                "s sport" in lower || "s-sport" in lower || "ssport" in lower -> "S SPORT"
                "trt spor" in lower || "trtspor" in lower -> "TRT SPOR"
                "trt 1" in lower || "trt1" in lower -> "TRT 1"
                "a spor" in lower || "aspor" in lower || "a-spor" in lower -> "A SPOR"
                else -> name.replace("▶", "").trim()
            }
        }

        fun getChannelLogo(name: String): String {
            val lower = name.lowercase().trim()
            return when {
                "max 1" in lower || "max-1" in lower || "max1" in lower -> "$LOGO_BASE/beinsportsmax1.png"
                "max 2" in lower || "max-2" in lower || "max2" in lower -> "$LOGO_BASE/beinsportsmax2.png"
                "bein" in lower && "1" in lower -> "$LOGO_BASE/beinsports1.png"
                "bein" in lower && "2" in lower -> "$LOGO_BASE/beinsports2.png"
                "bein" in lower && "3" in lower -> "$LOGO_BASE/beinsports3.png"
                "bein" in lower && "4" in lower -> "$LOGO_BASE/beinsports4.png"
                "bein" in lower && "5" in lower -> "$LOGO_BASE/beinsports5.png"
                "s sport 2" in lower || "s-sport 2" in lower || "s sport2" in lower || "ssport 2" in lower || "ssport2" in lower -> "$LOGO_BASE/Ssport2.png"
                "s sport" in lower || "s-sport" in lower || "ssport" in lower -> "$LOGO_BASE/Ssport.png"
                "trt spor" in lower || "trtspor" in lower -> "$LOGO_BASE/Trtspor.png"
                "trt 1" in lower || "trt1" in lower -> "$LOGO_BASE/Trt1.png"
                "a spor" in lower || "aspor" in lower || "a-spor" in lower -> "$LOGO_BASE/Aspor.png"
                else -> ""
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val channelList = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        try {
            val html = app.get(mainUrl, headers = headers).text
            val doc = Jsoup.parse(html)
            val links = doc.select("a[href*=/canli-mac/]")

            for (a in links) {
                val href = a.attr("href").trim()
                val fullUrl = if (href.startsWith("http")) href else "${mainUrl.trimEnd('/')}/${href.trimStart('/')}"
                val rawTitle = a.text().replace("▶", "").trim()
                if (rawTitle.isEmpty()) continue

                val logo = getChannelLogo(rawTitle)
                // Only include channels that have mapped logos
                if (logo.isNotEmpty() && seen.add(fullUrl)) {
                    val formattedName = formatChannelName(rawTitle)
                    channelList.add(
                        newLiveSearchResponse(
                            name = formattedName,
                            url = fullUrl,
                            type = TvType.Live
                        ) {
                            this.posterUrl = logo
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback to static channel list if scraping fails
        }

        // Ensure all 12 channels are always present in order
        for ((name, path) in DEFAULT_CHANNELS) {
            val fullUrl = "${mainUrl.trimEnd('/')}$path"
            if (seen.add(fullUrl)) {
                channelList.add(
                    newLiveSearchResponse(
                        name = name,
                        url = fullUrl,
                        type = TvType.Live
                    ) {
                        this.posterUrl = getChannelLogo(name)
                    }
                )
            }
        }

        return newHomePageResponse(request, channelList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        return DEFAULT_CHANNELS
            .filter { it.first.lowercase().contains(q) }
            .map { (name, path) ->
                newLiveSearchResponse(
                    name = name,
                    url = "${mainUrl.trimEnd('/')}$path",
                    type = TvType.Live
                ) {
                    this.posterUrl = getChannelLogo(name)
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val doc = try {
            app.get(url, headers = headers).document
        } catch (e: Exception) {
            null
        }

        val rawTitle = doc?.selectFirst("h1, .match-title, title")?.text()
            ?.replace("| Canlı Maç İzle", "")
            ?.replace("▶", "")
            ?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").uppercase()

        val title = formatChannelName(rawTitle)
        val logo = getChannelLogo(title)

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            if (logo.isNotEmpty()) {
                this.posterUrl = logo
            }
            this.plot = "7/24 Canli TV Yayini"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseHeaders = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        // 1. Fetch match page
        val mainHtml = try {
            app.get(data, headers = baseHeaders).text
        } catch (e: Exception) {
            return false
        }

        val doc1 = Jsoup.parse(mainHtml)
        val iframe1 = doc1.selectFirst("#playerFrame, iframe[src*=channel/watch], iframe[src*=loadstream]")
        val rawWatchPath = iframe1?.attr("src")?.trim() ?: return false
        val watchUrl = if (rawWatchPath.startsWith("http")) rawWatchPath else "${mainUrl.trimEnd('/')}/${rawWatchPath.trimStart('/')}"

        // 2. Fetch watch page
        val loadUrl = if (watchUrl.contains("loadstream")) {
            watchUrl
        } else {
            baseHeaders["Referer"] = data
            val watchHtml = try {
                app.get(watchUrl, headers = baseHeaders).text
            } catch (e: Exception) {
                return false
            }
            val doc2 = Jsoup.parse(watchHtml)
            val iframe2 = doc2.selectFirst("iframe[src*=loadstream]")
            val rawLoadPath = iframe2?.attr("src")?.trim() ?: return false
            if (rawLoadPath.startsWith("http")) rawLoadPath else "${mainUrl.trimEnd('/')}/${rawLoadPath.trimStart('/')}"
        }

        // 3. Fetch loadstream.php
        baseHeaders["Referer"] = watchUrl
        val loadHtml = try {
            app.get(loadUrl, headers = baseHeaders).text
        } catch (e: Exception) {
            return false
        }

        // 4. Extract m3u8
        var m3u8Url: String? = null
        val directMatch = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(loadHtml)
        if (directMatch != null) {
            m3u8Url = directMatch.value
        } else {
            val atobIndex = loadHtml.indexOf("|atob")
            if (atobIndex != -1) {
                val beforeAtob = loadHtml.substring(0, atobIndex)
                val lastQuote = maxOf(beforeAtob.lastIndexOf('\''), beforeAtob.lastIndexOf('"'))
                if (lastQuote != -1) {
                    val b64Str = beforeAtob.substring(lastQuote + 1)
                    val decoded = decodeBase64(b64Str)
                    val m = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(decoded)
                    if (m != null) {
                        m3u8Url = m.value
                    }
                }
            }
        }

        if (m3u8Url == null) {
            Regex("""[A-Za-z0-9+/=]{40,}""").findAll(loadHtml).forEach { match ->
                if (m3u8Url == null) {
                    val dec = decodeBase64(match.value)
                    val m = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(dec)
                    if (m != null && !m.value.contains("bsky.app") && !m.value.contains("preroll")) {
                        m3u8Url = m.value
                    }
                }
            }
        }

        if (m3u8Url.isNullOrEmpty()) return false

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "${this.name} - HD",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
