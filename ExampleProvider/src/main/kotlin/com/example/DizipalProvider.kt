package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class DizipalProvider : MainAPI() {
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://dizipal3008.com/"
        )
    }

    override var mainUrl = "https://dizipal3008.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "[Dizipal] Son Eklenenler",
        "${mainUrl}/kategori/aksiyon/" to "[Dizipal] Aksiyon",
        "${mainUrl}/kategori/komedi/" to "[Dizipal] Komedi",
        "${mainUrl}/kategori/korku/" to "[Dizipal] Korku",
        "${mainUrl}/kategori/bilim-kurgu/" to "[Dizipal] Bilim Kurgu",
        "${mainUrl}/kategori/dram/" to "[Dizipal] Dram",
        "${mainUrl}/kategori/gerilim/" to "[Dizipal] Gerilim",
        "${mainUrl}/kategori/yerli/" to "[Dizipal] Yerli"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = baseHeaders).document
        val items = document.select("div.post-item, div.grid div.post-item")
        val list = items.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, list, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val title = a.attr("title").ifBlank { img?.attr("alt")?.trim() ?: a.text().trim() }
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", headers = baseHeaders).document
        return document.select("div.post-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val title = document.selectFirst("h1")?.text()?.replace(Regex("(?i)\\s*izle.*"), "")?.trim()
            ?: document.title().replace(Regex("(?i)\\s*izle.*"), "").trim()
        val img = document.selectFirst("div.poster img, img.wp-post-image")
        val poster = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        val description = document.selectFirst("div.story, div.summary, div.content p")?.text()?.trim()

        val isSeries = url.contains("/dizi/")
        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val epLinks = document.select("a[href*='/bolum/']")
            epLinks.forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
                val epText = ep.text().trim()
                val seasonMatch = Regex("""(\d+)\.\s*Sezon""").find(epText)
                val epMatch = Regex("""(\d+)\.\s*Bölüm""").find(epText)
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(newEpisode(epHref) {
                    this.name = epText.ifBlank { "$seasonNum. Sezon $epNum. Bölüm" }
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "loading links from >> $data")
        val document = app.get(data, headers = baseHeaders).document
        val iframes = mutableSetOf<String>()

        document.select("iframe").forEach { ifr ->
            val src = ifr.attr("src").ifBlank { ifr.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("about:")) {
                iframes.add(fixUrl(src))
            }
        }

        var foundStream = false

        for (iframe in iframes) {
            Log.d("DZP", "processing iframe >> $iframe")

            // ag2m5.cfd veya türevi özel embed oynatıcı çözücüsü
            if (iframe.contains("ag2m5.") || iframe.contains("/embed-")) {
                try {
                    val embedReq = app.get(
                        iframe,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data
                        )
                    )
                    val embedHtml = embedReq.text
                    val endpointMatch = Regex("""fetch\('(/dl\?op=get_stream[^']+)'\)""").find(embedHtml)

                    if (endpointMatch != null) {
                        val host = Regex("""https?://[^/]+""").find(iframe)?.value ?: "https://x.ag2m5.cfd"
                        val streamUrl = host + endpointMatch.groupValues[1]

                        val cookieMatches = Regex("""\$\.cookie\('([^']+)',\s*'([^']+)'""").findAll(embedHtml)
                        val dynamicCookies = cookieMatches.map { "${it.groupValues[1]}=${it.groupValues[2]}" }.joinToString("; ")
                        val cookieHeader = if (dynamicCookies.isNotBlank()) dynamicCookies else "aff=1; ref_url=dizipal3008.com"

                        val streamRes = app.get(
                            streamUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to iframe,
                                "Origin" to host,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Cookie" to cookieHeader
                            )
                        ).text

                        val json = JSONObject(streamRes)
                        val m3u8Url = json.optString("url")

                        if (m3u8Url.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "Dizipal Full HD (HLS)",
                                    m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "${host}/"
                                }
                            )
                            foundStream = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DZP", "ag2m5 error: ${e.message}")
                }
            } else {
                loadExtractor(iframe, mainUrl, subtitleCallback, callback)
                foundStream = true
            }
        }

        return foundStream
    }
}
