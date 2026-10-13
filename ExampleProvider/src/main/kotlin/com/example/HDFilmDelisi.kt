package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class HDFilmDelisi : MainAPI() {
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://hdfilmdelisi.one/"
        )
    }

    override var mainUrl = "https://hdfilmdelisi.one"
    override var name = "HDFilmDelisi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "[HDFilmDelisi] Son Filmler",
        "${mainUrl}/populer" to "[HDFilmDelisi] En Cok Izlenenler",
        "${mainUrl}/imdb-top-250" to "[HDFilmDelisi] IMDb Top 250"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = baseHeaders).document
        val items = document.select("a[href^='/film/']")
        val list = items.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, list, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.replace(Regex("(?i)\\s*izle.*"), "")?.trim()
            ?: this.text().trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/film-kesfet?q=${query}", headers = baseHeaders).document
        return document.select("a[href^='/film/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val title = document.selectFirst("h1")?.text()?.replace(Regex("(?i)\\s*izle.*"), "")?.trim()
            ?: document.title().replace(Regex("(?i)\\s*izle.*"), "").trim()

        var posterUrl: String? = null
        var description: String? = null
        var year: Int? = null

        // JSON-LD schema parsing
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val json = JSONObject(script.data())
                val type = json.optString("@type")
                if (type == "Movie" || type == "VideoObject") {
                    if (json.has("image")) posterUrl = json.optString("image")
                    if (json.has("description")) description = json.optString("description")
                    if (json.has("dateCreated")) {
                        year = Regex("""\d{4}""").find(json.optString("dateCreated"))?.value?.toIntOrNull()
                    }
                }
            } catch (_: Exception) {}
        }

        if (posterUrl == null) {
            posterUrl = fixUrlNull(document.selectFirst("img[src*='/posters/']")?.attr("src"))
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = baseHeaders).document

        // Check JSON-LD for direct contentUrl
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val json = JSONObject(script.data())
                val contentUrl = json.optString("contentUrl")
                if (contentUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "HDFilmDelisi Full HD",
                            fixUrl(contentUrl),
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            } catch (_: Exception) {}
        }

        // Check iframes
        val iframes = document.select("iframe").mapNotNull { it.attr("src") }
        for (src in iframes) {
            val fixed = fixUrl(src)
            loadExtractor(fixed, subtitleCallback, callback)
        }

        return true
    }
}
