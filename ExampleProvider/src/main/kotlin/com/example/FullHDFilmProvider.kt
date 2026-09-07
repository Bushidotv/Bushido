package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FullHDFilmProvider : MainAPI() {
    override var name = "FullHDFilmizlesene"
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        Pair("$mainUrl/yeni-filmler", "Son Eklenen Filmler"),
        Pair("$mainUrl/populer-filmler", "Popüler Filmler"),
        Pair("$mainUrl/en-cok-izlenen-filmler", "En Çok İzlenenler"),
        Pair("$mainUrl/filmizle/imdb-puani-yuksek-filmler", "IMDb 7+ Filmler"),
        Pair("$mainUrl/filmizle/turkce-dublaj-filmler-1", "Türkçe Dublaj Filmler"),
        Pair("$mainUrl/filmizle/turkce-altyazili-filmler-1", "Türkçe Altyazılı Filmler")
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun rot13(input: String): String {
            val sb = StringBuilder()
            for (c in input) {
                when (c) {
                    in 'a'..'z' -> sb.append((((c - 'a' + 13) % 26) + 'a'.code).toChar())
                    in 'A'..'Z' -> sb.append((((c - 'A' + 13) % 26) + 'A'.code).toChar())
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun decodeBase64(input: String): String {
            return try {
                val clean = input.trim().replace('-', '+').replace('_', '/')
                val pad = clean.length % 4
                val padded = if (pad > 0) clean + "=".repeat(4 - pad) else clean
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Throwable) {
                ""
            }
        }

        fun decodeRapidvid(arg: String): String? {
            return try {
                val reversed = arg.reversed()
                val t = decodeBase64(reversed)
                val key = "K9L"
                val sb = StringBuilder()
                for (i in t.indices) {
                    val r = key[i % 3]
                    val n = t[i].code - (r.code % 5 + 1)
                    sb.append(n.toChar())
                }
                val result = decodeBase64(sb.toString())
                if (result.startsWith("http")) result else null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun Element.toMovieSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a.tt, a[href*=/film/]") ?: return null
        val href = a.attr("href").trim()
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl/${href.trimStart('/')}"

        val title = this.selectFirst(".film-title")?.text()?.trim()
            ?: a.text().replace(Regex("(?i)izle"), "").trim()
        if (title.isEmpty()) return null

        val img = this.selectFirst("img")
        val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            ?: img?.attr("src")

        val year = this.selectFirst(".film-yil")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(
            name = title,
            url = fullUrl,
            type = TvType.Movie
        ) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val pageUrl = if (page > 1) {
            "${request.data.trimEnd('/')}/$page"
        } else {
            request.data
        }

        val html = try {
            app.get(pageUrl, headers = headers).text
        } catch (e: Exception) {
            return newHomePageResponse(request, emptyList())
        }

        val doc = Jsoup.parse(html)
        val filmElements = doc.select(".film, .filmler li, div.film-item")
        val list = filmElements.mapNotNull { it.toMovieSearchResponse() }

        return newHomePageResponse(request, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/arama/$encodedQuery"

        val html = try {
            app.get(searchUrl, headers = headers).text
        } catch (e: Exception) {
            return emptyList()
        }

        val doc = Jsoup.parse(html)
        val filmElements = doc.select(".film, .filmler li, div.film-item")
        return filmElements.mapNotNull { it.toMovieSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1.film-title, h1, .film-info h1")?.text()
            ?.replace(Regex("(?i)izle"), "")?.trim() ?: "Film"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".film-afis img, .afis, img")?.attr("data-src")
            ?: doc.selectFirst(".film-afis img, .afis, img")?.attr("src")

        val plot = doc.selectFirst(".film-ozet, p.ozet, div[itemprop=description], .film-content p")?.text()?.trim()

        val yearText = doc.selectFirst(".film-yil, .info-yil, span:contains(Yapım)")?.text()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(yearText.orEmpty())?.value?.toIntOrNull()

        val rating = doc.selectFirst(".imdb, span[itemprop=ratingValue]")?.text()?.toRatingInt()
        val tags = doc.select(".kategori a, .film-tur a").map { it.text().trim() }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.rating = rating
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val html = try {
            app.get(data, headers = headers).text
        } catch (e: Exception) {
            return false
        }

        val scxMatch = Regex("""var\s+scx\s*=\s*(\{.+?\});""").find(html) ?: return false
        val scxJson = scxMatch.groupValues[1]

        var foundAny = false

        try {
            val rootObj = JSONObject(scxJson)
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val sourceKey = keys.next()
                val sourceObj = rootObj.optJSONObject(sourceKey) ?: continue
                val sx = sourceObj.optJSONObject("sx") ?: continue

                val tokens = mutableListOf<String>()
                val tArray = sx.optJSONArray("t")
                if (tArray != null) {
                    for (i in 0 until tArray.length()) {
                        tokens.add(tArray.getString(i))
                    }
                }
                val pArray = sx.optJSONArray("p")
                if (pArray != null) {
                    for (i in 0 until pArray.length()) {
                        tokens.add(pArray.getString(i))
                    }
                }

                for (token in tokens) {
                    try {
                        val rot = rot13(token)
                        val playerUrl = decodeBase64(rot)
                        if (playerUrl.isEmpty()) continue

                        if (playerUrl.contains("rapidvid")) {
                            val playerHeaders = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to data
                            )
                            val playerHtml = app.get(playerUrl, headers = playerHeaders).text

                            // Subtitles
                            val trackMatch = Regex("""jwSetup\.tracks\s*=\s*(\[.+?\]);""").find(playerHtml)
                            if (trackMatch != null) {
                                try {
                                    val trackArray = JSONArray(trackMatch.groupValues[1])
                                    for (ti in 0 until trackArray.length()) {
                                        val tObj = trackArray.getJSONObject(ti)
                                        val trackFile = tObj.optString("file")
                                        val trackLabel = tObj.optString("label", "Türkçe")
                                        if (trackFile.isNotEmpty()) {
                                            subtitleCallback.invoke(
                                                SubtitleFile(trackLabel, trackFile)
                                            )
                                        }
                                    }
                                } catch (e: Exception) { }
                            }

                            // Stream URL
                            val avMatch = Regex("""av\(["']([^"']+)["']\)""").find(playerHtml)
                            if (avMatch != null) {
                                val streamUrl = decodeRapidvid(avMatch.groupValues[1])
                                if (!streamUrl.isNullOrEmpty()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "FullHDFilmizlesene",
                                            name = "FullHD - $sourceKey",
                                            url = streamUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://rapidvid.net/"
                                            this.headers = mapOf(
                                                "User-Agent" to USER_AGENT,
                                                "Referer" to "https://rapidvid.net/"
                                            )
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    foundAny = true
                                }
                            }
                        } else {
                            loadExtractor(playerUrl, data, subtitleCallback, callback)
                            foundAny = true
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }

        return foundAny
    }
}
