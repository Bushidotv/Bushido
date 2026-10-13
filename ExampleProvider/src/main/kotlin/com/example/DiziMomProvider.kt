package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.json.JSONObject

class DiziMomProvider : MainAPI() {
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www.dizimom.cafe/"
        )
    }

    override var mainUrl = "https://www.dizimom.cafe"
    override var name = "DiziMom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/" to "[DiziMom] Son Bolumler",
        "${mainUrl}/yabanci-dizi-izle/page/" to "[DiziMom] Yabanci Diziler",
        "${mainUrl}/yerli-dizi-izle/page/" to "[DiziMom] Yerli Diziler",
        "${mainUrl}/tv-programlari-izle/page/" to "[DiziMom] TV Programlari"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/", headers = baseHeaders).document
        val home = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-box").mapNotNull { it.sonBolumler() }
        } else {
            document.select("div.single-item").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name = this.selectFirst("div.episode-name a")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val title = name.replace(".Sezon ", "x").replace(".Bölüm", "")

        val epHref = fixUrlNull(this.selectFirst("div.episode-name a")?.attr("href")) ?: return null
        val epDoc = app.get(epHref, headers = baseHeaders).document
        val href = fixUrlNull(epDoc.selectFirst("div#benzerli a")?.attr("href")) ?: epHref

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("div.categorytitle a")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("div.categorytitle a")?.attr("href")) ?: return null
        val img = this.selectFirst("div.cat-img img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", headers = baseHeaders).document
        return document.select("div.single-item").mapNotNull { it.diziler() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document

        val title = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val img = document.selectFirst("div.category_image img")
        val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        val year = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags = document.select("div.genres a").mapNotNull { it.text().trim() }
        val rating = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim().toFloatOrNull()
        val actors = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(",").map {
            Actor(it.trim())
        }

        val episodes = document.select("div.bolumust").mapNotNull {
            val epName = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val epHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name = epName.substringBefore(" izle").replace(title, "").trim()
                this.season = epSeason
                this.episode = epEpisode
                this.posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZM", "loading links from >> $data")
        val document = app.get(data, headers = baseHeaders).document

        // Collect all iframes (both data-src and src)
        val iframes = mutableSetOf<String>()
        document.select("iframe").forEach { ifr ->
            val src = ifr.attr("data-src").ifBlank { ifr.attr("src") }
            if (src.isNotBlank() && !src.startsWith("about:")) {
                iframes.add(fixUrl(src))
            }
        }

        document.select("div.sources a").forEach {
            val subHref = fixUrlNull(it.attr("href")) ?: return@forEach
            try {
                val subDoc = app.get(subHref, headers = baseHeaders).document
                subDoc.select("iframe").forEach { ifr ->
                    val src = ifr.attr("data-src").ifBlank { ifr.attr("src") }
                    if (src.isNotBlank() && !src.startsWith("about:")) {
                        iframes.add(fixUrl(src))
                    }
                }
            } catch (_: Exception) {}
        }

        var foundStream = false

        for (iframe in iframes) {
            Log.d("DZM", "processing iframe >> $iframe")

            // Özel Çözücü: hdplayersystem.com / hdplayersystem.live
            if (iframe.contains("hdplayersystem.")) {
                val videoId = Regex("""/video/([a-zA-Z0-9]+)""").find(iframe)?.groupValues?.get(1)
                if (!videoId.isNullOrBlank()) {
                    try {
                        val apiUrl = "https://hdplayersystem.com/player/index.php?data=${videoId}&do=getVideo"
                        val apiRes = app.post(
                            apiUrl,
                            data = mapOf("hash" to videoId, "r" to mainUrl),
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to iframe,
                                "Origin" to "https://hdplayersystem.com",
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).text

                        val json = JSONObject(apiRes)
                        val m3u8 = json.optString("securedLink").ifBlank { json.optString("videoSource") }

                        if (m3u8.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "DiziMom Full HD (HLS)",
                                    fixUrl(m3u8),
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://hdplayersystem.com/"
                                }
                            )
                            foundStream = true
                        }
                    } catch (e: Exception) {
                        Log.e("DZM", "hdplayersystem error: ${e.message}")
                    }
                }
            } else {
                // Diğer standart iframe'leri genel CloudStream extractor'larına ilet
                loadExtractor(iframe, mainUrl, subtitleCallback, callback)
                foundStream = true
            }
        }

        return foundStream
    }
}
