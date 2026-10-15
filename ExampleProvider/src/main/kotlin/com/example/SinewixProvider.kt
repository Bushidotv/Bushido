package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class SinewixProvider : MainAPI() {
    companion object {
        private const val DEFAULT_API_BASE = "https://ydfvfdizipanel.ru/public/api"
        private const val DEFAULT_PANEL_BASE = "https://ydfvfdizipanel.ru"
        private const val API_KEY = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
        private const val USER_AGENT = "EasyPlex (Android 14; SM-A546B; Samsung Galaxy A54 5G; tr)"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        private val apiHeaders = mapOf(
            "hash256" to "711bff4afeb47f07ab08a0b07e85d3835e739295e8a6361db77eebd93d96306b",
            "signature" to "3082058830820370a00302010202145bbfbba9791db758ad12295636e094ab4b07dc24300d06092a864886f70d01010b05003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643020170d3231313231353232303433335a180f32303531313231353232303433335a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820222300d06092a864886f70d01010105000382020f003082020a0282020100a5106a24bb3f9c0aaf3a2b228f794b5eaf1757ba758b19736a39d1bdc73fc983a7237b8d5ca5156cfa999c1dab3418bbc2be0920e0ee001c8aa4812d1dae75d080f09e91e0abda83ff9a76e8384a4429f4849248069a59505b12ac2c14ba2e4d1a13afcdaf54e508697ff928a9f738e6f4a6fc27409c55329eb149b5ff89c5a2d7c06bf9e62086f955cad17d7be2623ee9d5ec56068eadc23cb0965a13ff97d49fe10ef41afc6eeca36b4ace9582097faff89f590bc831cdb3a69eec5d15b67c3f2cad49e37ed053733e3d2d400c47755b932bdbe15d749fd6ad1dce30ba5e66094dfb6ee6f64cafb807e11b19a990c5d078c6d6701cda0bdeb21e99404ff166074f4c89b04c418f4e7940db5c78647c475bcfb85d4c4e836ee7d7c1d53e9e736b5d96d4b4d8b98209064b729ac6a682d55a6a930e518d849898bb28329ca0aaa133b5e5270a9d5940cac6af4802a57fd971efda91abb602882dd6aa6ce2b236b57b52ee2481498f0cacbcc2c36c238bc84becad7eaaf1125b9a1ca9ded6c79f3f283a52050377809b2a9995d66e1636b0ed426fdd8685c47cb18e82077f4aefcc07887e1dc58b4d64be1632f0e7b4625da6f40c65a8512a6454a4b96963e7f876136e6c0069a519a79ad632078ed965aa12482458060c030ed50db706d854f88cb004630b49285d8af8b471ff8f6070687826412287b50049bcb7d1b6b62ef90203010001a310300e300c0603551d13040530030101ff300d06092a864886f70d01010b0500038202010051c0b7bd793181dc29ca777d3773f928a366c8469ecf2fa3cfb076e8831970d19bb2b96e44e8ccc647cf0696bb824ac61c23d958525d283cab26037b04d58aa79bf92192db843adf5c26a980f081d2f0e14f759fc5ff4c5bb3dce0860299bfe7b349a8155a2efaf731ba25ce796a80c1442c7bf80f8c1a7912ff0b6f6592264315337251a846460194fa594f81f38f9e5233a63201e931ad9cab5bf119f24025613f307194eaa6eb39a83f3c05a49ba34455b1aff7c6839bbb657d9392ffdf397432af6e56ba9534a8b07d7060fe09691c6cf07cb5324f67b3cc0871a8c621d81fe71d71085c55206a4f57e25f774fd4b979b299e8bb076b50fca42fa57da2d519fd35a4a7c0137babaed4345f8031b63b6a71f5e8268f709d658ccd7c2a58849379d25bfa598c3f4a2c3d9b7d89285fefeb7f0ec65137d38b08ce432a15688b624a179e6a4a505ebc3bcdfbc4d4330508ee2d8d0f016924dcec21a6838ef7d834c6f43bde4a5201ed0b3bb4e9bd377b470e36bcf5bc3d56169dbd8e39567aa7dce4d1a8a8a54a5e1aa6fb1a8aab0062669a966f96e15ccce6fe12ea5e6a8b8c8823bdc94988ca39759fd1cc8fd8ae5c3d74db50b174cf7d77655016c075c91d439ed01cc0a9f695c99fad3b5495fb6cb1e01a5fa020cc6022a85c07ec55f9eba89719f86e49d34ab5bd208c5f70cced2b7b7963c014f8404432979b506de29e",
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json"
        )
    }

    override var mainUrl = DEFAULT_PANEL_BASE
    override var name = "Sinewix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var apiBase = DEFAULT_API_BASE
    private var panelBase = DEFAULT_PANEL_BASE
    private var lastConfigCheck = 0L

    override val mainPage = mainPageOf(
        "$mainUrl/search/film" to "SineWix - Popüler Filmler",
        "$mainUrl/series/popular" to "SineWix - Popüler Diziler"
    )

    private suspend fun checkConfig() {
        val now = System.currentTimeMillis()
        if (now - lastConfigCheck < 15 * 60 * 1000L) return
        lastConfigCheck = now
        try {
            val res = app.get("https://raw.githubusercontent.com/falsisdev/anthology/main/config.json").text
            val json = JSONObject(res)
            val sinewix = json.optJSONObject("urls")?.optJSONObject("movies")?.optJSONObject("sinewix")
            val newApi = sinewix?.optString("api_base")
            val newPanel = sinewix?.optString("panel_base")
            if (!newApi.isNullOrBlank()) apiBase = newApi.trimEnd('/')
            if (!newPanel.isNullOrBlank()) {
                panelBase = newPanel.trimEnd('/')
                mainUrl = panelBase
            }
        } catch (_: Exception) {}
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkConfig()
        val isMovie = request.data.contains("/search/film")
        val url = if (isMovie) "$apiBase/search/film/$API_KEY" else "$apiBase/series/popular/$API_KEY"

        val res = app.get(url, headers = apiHeaders).text
        val list = mutableListOf<SearchResponse>()
        val json = JSONObject(res)

        if (isMovie) {
            val arr = json.optJSONArray("search")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val id = item.optString("id")
                    val title = item.optString("title").ifBlank { item.optString("name") }
                    val poster = item.optString("poster_path").replace("http://", "https://")
                    if (title.isNotBlank() && id.isNotBlank()) {
                        list.add(newMovieSearchResponse(title, "?type=movie&id=$id", TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        } else {
            val arr = json.optJSONArray("popularSeries")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val id = item.optString("id")
                    val name = item.optString("name")
                    val poster = item.optString("poster_path").replace("http://", "https://")
                    if (name.isNotBlank() && id.isNotBlank()) {
                        list.add(newTvSeriesSearchResponse(name, "?type=series&id=$id", TvType.TvSeries) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }

        return newHomePageResponse(request.name, list, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkConfig()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiBase/search/$encodedQuery/$API_KEY"

        val res = app.get(url, headers = apiHeaders).text
        val json = JSONObject(res)
        val arr = json.optJSONArray("search") ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("id")
            val title = item.optString("title").ifBlank { item.optString("name") }
            val isMovie = item.optString("type") == "movie" || item.has("title")
            val poster = item.optString("poster_path").replace("http://", "https://")

            if (title.isNotBlank() && id.isNotBlank()) {
                if (isMovie) {
                    list.add(newMovieSearchResponse(title, "?type=movie&id=$id", TvType.Movie) {
                        this.posterUrl = poster
                    })
                } else {
                    list.add(newTvSeriesSearchResponse(title, "?type=series&id=$id", TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return list
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        checkConfig()
        val uri = url.substringAfter("?")
        val params = uri.split("&").associate {
            val p = it.split("=")
            p[0] to (p.getOrNull(1) ?: "")
        }
        val type = params["type"] ?: "movie"
        val id = params["id"] ?: return null

        if (type == "movie") {
            val detailUrl = "$apiBase/media/detail/$id/$API_KEY"
            val res = app.get(detailUrl, headers = apiHeaders).text
            val item = JSONObject(res)
            val title = item.optString("title").ifBlank { item.optString("name") }
            val poster = item.optString("poster_path").replace("http://", "https://")
            val plot = item.optString("overview")
            val year = item.optString("release_date").split("-").firstOrNull()?.toIntOrNull()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val showUrl = "$apiBase/series/show/$id/$API_KEY"
            val res = app.get(showUrl, headers = apiHeaders).text
            val item = JSONObject(res)
            val name = item.optString("name")
            val poster = item.optString("poster_path").replace("http://", "https://")
            val plot = item.optString("overview")
            val year = item.optString("first_air_date").split("-").firstOrNull()?.toIntOrNull()

            val episodes = mutableListOf<Episode>()
            val seasonsArr = item.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (sIdx in 0 until seasonsArr.length()) {
                    val seaObj = seasonsArr.getJSONObject(sIdx)
                    val sNum = seaObj.optInt("season_number", sIdx + 1)
                    val epArr = seaObj.optJSONArray("episodes")
                    if (epArr != null) {
                        for (eIdx in 0 until epArr.length()) {
                            val epObj = epArr.getJSONObject(eIdx)
                            val eNum = epObj.optInt("episode_number", eIdx + 1)
                            val epName = epObj.optString("name").ifBlank { "$sNum. Sezon $eNum. Bölüm" }
                            val still = epObj.optString("still_path").replace("http://", "https://")
                            val epData = "?type=series&id=$id&season=$sNum&episode=$eNum"
                            episodes.add(newEpisode(epData) {
                                this.name = epName
                                this.season = sNum
                                this.episode = eNum
                                this.posterUrl = still
                            })
                        }
                    }
                }
            }

            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        checkConfig()
        val uri = data.substringAfter("?")
        val params = uri.split("&").associate {
            val p = it.split("=")
            p[0] to (p.getOrNull(1) ?: "")
        }
        val type = params["type"] ?: "movie"
        val id = params["id"] ?: return false

        val videosJsonArray: JSONArray = if (type == "movie") {
            val detailUrl = "$apiBase/media/detail/$id/$API_KEY"
            val res = app.get(detailUrl, headers = apiHeaders).text
            val item = JSONObject(res)
            item.optJSONArray("videos") ?: JSONArray()
        } else {
            val sNum = params["season"]?.toIntOrNull() ?: 1
            val eNum = params["episode"]?.toIntOrNull() ?: 1
            val showUrl = "$apiBase/series/show/$id/$API_KEY"
            val res = app.get(showUrl, headers = apiHeaders).text
            val item = JSONObject(res)
            var foundVideos: JSONArray? = null
            val seasonsArr = item.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (sIdx in 0 until seasonsArr.length()) {
                    val seaObj = seasonsArr.getJSONObject(sIdx)
                    if (seaObj.optInt("season_number") == sNum) {
                        val epArr = seaObj.optJSONArray("episodes")
                        if (epArr != null) {
                            for (eIdx in 0 until epArr.length()) {
                                val epObj = epArr.getJSONObject(eIdx)
                                if (epObj.optInt("episode_number") == eNum) {
                                    foundVideos = epObj.optJSONArray("videos")
                                    break
                                }
                            }
                        }
                        break
                    }
                }
            }
            foundVideos ?: JSONArray()
        }

        val streamHeaders = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "$panelBase/",
            "Origin" to panelBase
        )

        for (i in 0 until videosJsonArray.length()) {
            val v = videosJsonArray.getJSONObject(i)
            val link = v.optString("link")
            if (link.isBlank()) continue

            val serverName = v.optString("server").ifBlank { "Sunucu" }
            val lang = v.optString("lang")
            val isDual = lang.contains("dual", ignoreCase = true) || link.contains("dual", ignoreCase = true)
            val langStr = if (isDual) "DUAL (TR/EN)" else if (lang.isNotBlank()) lang else "Türkçe"
            val isMF = link.contains("mediafire.com")
            val title = "SineWix | ${if (isMF) "MEDIAFIRE" else serverName.uppercase()} ($langStr - 1080p)"

            val linkType = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            if (isMF) {
                val directUrl = resolveMediaFireLink(link)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = title,
                        url = directUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$panelBase/"
                        this.quality = Qualities.P1080.value
                        this.headers = streamHeaders
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = title,
                        url = link,
                        type = linkType
                    ) {
                        this.referer = "$panelBase/"
                        this.quality = Qualities.P1080.value
                        this.headers = streamHeaders
                    }
                )
            }
        }

        return true
    }

    private suspend fun resolveMediaFireLink(link: String): String {
        return try {
            val html = app.get(link).text
            val match = Regex("""href="(https://download\d+\.mediafire\.com[^"]+)"""").find(html)
            match?.groupValues?.getOrNull(1) ?: link
        } catch (_: Exception) {
            link
        }
    }
}
