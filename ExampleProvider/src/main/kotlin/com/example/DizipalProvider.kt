package com.example

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DizipalProvider : MainAPI() {
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://dizipal1588.com/"
        )
    }

    override var mainUrl = "https://dizipal1588.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    override val mainPage = mainPageOf(
        "${mainUrl}/yabanci-dizi-izle" to "[Dizipal] Yeni Diziler",
        "${mainUrl}/hd-film-izle" to "[Dizipal] Yeni Filmler",
        "${mainUrl}/kanal/netflix" to "[Dizipal] Netflix",
        "${mainUrl}/kanal/exxen" to "[Dizipal] Exxen",
        "${mainUrl}/kanal/max" to "[Dizipal] Max",
        "${mainUrl}/kanal/disney" to "[Dizipal] Disney+",
        "${mainUrl}/kanal/amazon" to "[Dizipal] Amazon Prime",
        "${mainUrl}/kanal/tod" to "[Dizipal] TOD (beIN)",
        "${mainUrl}/kanal/tabii" to "[Dizipal] Tabii",
        "${mainUrl}/kanal/hulu" to "[Dizipal] Hulu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && !request.data.contains("/kanal/")) {
            if (request.data.contains("?")) "${request.data}&sayfa=$page" else "${request.data}?sayfa=$page"
        } else {
            request.data
        }

        val document = app.get(url, headers = baseHeaders).document
        val home = mutableListOf<SearchResponse>()

        if (!request.data.contains("/kanal/") || page == 1) {
            if (request.data.contains("/yabanci-dizi-izle") || request.data.contains("/hd-film-izle")) {
                home.addAll(document.select("div.new-added-list div.bg-\\[\\#22232a\\]").mapNotNull { it.sonBolumler() })
            } else {
                home.addAll(document.select("div.bg-\\[\\#22232a\\]").mapNotNull { it.diziler() })
            }
        }

        if (request.data.contains("/kanal/")) {
            val channelIdFromDoc = document.selectFirst("input[name=channelId]")?.attr("value")
                ?: Regex("""channelId\s*[:=]\s*(\d+)""").find(document.html())?.groupValues?.get(1)
            val channelSlug = request.data.substringAfterLast("/")

            try {
                val apiResponse = app.post(
                    "${mainUrl}/bg/getserielistbychannel",
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = request.data,
                    data = mapOf(
                        "cKey" to "c61f91c5141d178450934fe81c0a2029",
                        "cValue" to "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA==",
                        "curPage" to page.toString(),
                        "channelId" to (channelIdFromDoc ?: "1"),
                        "languageId" to "2,3,4",
                        "slug" to channelSlug
                    )
                )

                val mapper = jacksonObjectMapper()
                val rootNode = mapper.readTree(apiResponse.text)
                val htmlNode = rootNode.at("/data/html")
                if (!htmlNode.isMissingNode) {
                    val parsedDoc = Jsoup.parse(htmlNode.asText())
                    home.addAll(parsedDoc.select("div.bg-\\[\\#22232a\\]").mapNotNull { it.diziler() })
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name = this.selectFirst("h2")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(name, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(name, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseRaw = app.post(
                "${mainUrl}/bg/searchcontent",
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "${mainUrl}/",
                data = mapOf(
                    "cKey" to "c61f91c5141d178450934fe81c0a2029",
                    "cValue" to "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA==",
                    "type" to "hepsi",
                    "searchterm" to query
                )
            )

            val mapper = jacksonObjectMapper()
            val rootNode = mapper.readTree(responseRaw.text)
            val resultArrayNode = rootNode.at("/data/result")

            if (resultArrayNode.isMissingNode || !resultArrayNode.isArray) {
                return emptyList()
            }

            val searchItems: List<DizipalSearchItem> = mapper.readValue(resultArrayNode.traverse())
            searchItems.mapNotNull {
                val title = it.title ?: return@mapNotNull null
                val href = "${mainUrl}/${it.slug ?: ""}"
                val poster = it.poster

                if (it.type == "series") {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val poster = fixUrlNull(document.selectFirst("div.page-top img[alt]")?.attr("src"))
        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags = document.selectXpath("//div[text()='Kategoriler']//following-sibling::div").text().trim().split(" ").map { it.trim() }
        val duration = Regex("(\\d+)").find(document.selectXpath("//div[text()='Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        if (url.contains("/series/")) {
            val title = document.selectFirst("div.flex h2")?.text()?.trim() ?: return null
            val episodes = document.select("div.relative.w-full.flex.items-start.gap-4").mapNotNull { element ->
                val linkElement = element.selectFirst("a[data-dizipal-pageloader]") ?: return@mapNotNull null
                val epHref = fixUrlNull(linkElement.attr("href")) ?: return@mapNotNull null
                val epName = linkElement.selectFirst("h2")?.text()?.trim() ?: "Bölüm"
                val infoText = linkElement.selectFirst("div.text-white.text-sm.opacity-80")?.text()?.trim() ?: ""

                val epSeason = Regex("""(\d+)\.\s*Sezon""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
                val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

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
                this.duration = duration
            }
        } else {
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim().ifBlank {
                document.selectFirst("h1")?.text()?.trim() ?: "Film"
            }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPal", "--> loadLinks: $data")
        val doc = app.get(data, headers = baseHeaders).document

        val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
        var iframeUrl = if (encryptedText.isNotEmpty()) {
            decryptDizipalData(encryptedText)
        } else {
            doc.selectFirst("iframe")?.attr("src") ?: ""
        }

        if (iframeUrl.isNotEmpty()) {
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            } else if (!iframeUrl.startsWith("http")) {
                iframeUrl = "https://$iframeUrl"
            }

            Log.d("DiziPal", "--> Final Iframe: $iframeUrl")
            DizipalPlayerExtractor().getUrl(
                url = iframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        return true
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) ?: return ""
            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) ?: return ""
            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) ?: return ""

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = android.util.Base64.decode(ctMatch, android.util.Base64.DEFAULT)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = SecretKeySpec(secretKey.encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPal", "Decryption error: ${e.message}")
            ""
        }
    }
}
