package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class FamelackProvider : MainAPI() {
    override var name = "Famelack"
    override var mainUrl = "https://famelack.com"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        Pair("tr_ulusal", "Ulusal Kanallar"),
        Pair("tr_haber", "Haber Kanalları"),
        Pair("tr_muzik", "Müzik & Gençlik"),
        Pair("tr_cocuk", "Çocuk Kanalları"),
        Pair("tr_yerel", "Yerel & Bölgesel Kanallar"),
        Pair("tr_tum", "Tüm Türkiye Kanalları"),
        Pair("az_tum", "Azerbaycan Kanalları"),
        Pair("dunya_belgesel", "Dünya Belgesel Kanalları")
    )

    data class ChannelItem(
        val nanoid: String,
        val name: String,
        val streams: List<String>,
        val youtube: List<String>,
        val country: String,
        val posterUrl: String
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val TR_DATA_URL =
            "https://raw.githubusercontent.com/famelack/famelack-data/main/tv/compressed/countries/tr.json"

        private const val AZ_DATA_URL =
            "https://raw.githubusercontent.com/famelack/famelack-data/main/tv/compressed/countries/az.json"

        private const val DOC_DATA_URL =
            "https://raw.githubusercontent.com/famelack/famelack-data/main/tv/compressed/categories/documentary.json"

        private const val DEFAULT_ICON =
            "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"

        // In-memory cache
        private val channelCache = mutableMapOf<String, ChannelItem>()
        private var trChannelsCache: List<ChannelItem>? = null
        private var azChannelsCache: List<ChannelItem>? = null
        private var docChannelsCache: List<ChannelItem>? = null

        fun getChannelLogo(name: String): String {
            val lower = name.lowercase().trim()
            return when {
                lower == "trt 1" || lower == "trt1" -> "https://raw.githubusercontent.com/Bushidotv/Bushido/master/netspor_logos/Trt1.png"
                lower == "trt spor" || lower == "trtspor" -> "https://raw.githubusercontent.com/Bushidotv/Bushido/master/netspor_logos/Trtspor.png"
                lower == "trt haber" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cd/TRT_Haber_2020_logo.svg/320px-TRT_Haber_2020_logo.svg.png"
                lower == "trt cocuk" || lower == "trt çocuk" -> "https://upload.wikimedia.org/wikipedia/tr/thumb/8/87/Trt_cocuk_2021.png/320px-Trt_cocuk_2021.png"
                lower == "trt muzik" || lower == "trt müzik" -> "https://upload.wikimedia.org/wikipedia/tr/thumb/2/27/TRT_M%C3%BCzik.png/320px-TRT_M%C3%BCzik.png"
                lower == "trt 2" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1b/TRT_2_logo_2019.svg/320px-TRT_2_logo_2019.svg.png"
                lower == "trt turk" || lower == "trt türk" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3f/TRT_T%C3%BCrk_logo_2020.svg/320px-TRT_T%C3%BCrk_logo_2020.svg.png"
                lower == "trt world" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/1/19/TRT_World_logo.svg/320px-TRT_World_logo.svg.png"
                lower == "trt avaz" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/TRT_Avaz_logo_2020.svg/320px-TRT_Avaz_logo_2020.svg.png"
                lower == "atv" || lower == "a tv" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ee/ATV_%28Turkish_TV_channel%29_logo.svg/320px-ATV_%28Turkish_TV_channel%29_logo.svg.png"
                lower == "a2tv" || lower == "a2" -> "https://upload.wikimedia.org/wikipedia/tr/thumb/5/52/A2_logosu.png/320px-A2_logosu.png"
                lower == "a haber" -> "https://upload.wikimedia.org/wikipedia/tr/thumb/2/29/A_Haber_logosu.png/320px-A_Haber_logosu.png"
                lower == "a spor" -> "https://raw.githubusercontent.com/Bushidotv/Bushido/master/netspor_logos/Aspor.png"
                lower == "tv8" || lower == "tv 8" || lower == "tv 8.5" || lower == "tv8.5" -> "https://tr.canlitv.watch/channels/tv8.webp"
                lower == "beyaz tv" || "beyaz" in lower -> "https://raw.githubusercontent.com/Bushidotv/Bushido/master/netspor_logos/Beyaztv.png"
                lower == "halk tv" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ea/Halk_TV_logosu.svg/320px-Halk_TV_logosu.svg.png"
                lower == "haber global" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/b/be/Haber_Global_logosu.png/320px-Haber_Global_logosu.png"
                lower == "haberturk tv" || lower == "habertürk tv" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Habert%C3%BCrk_TV_logosu.svg/320px-Habert%C3%BCrk_TV_logosu.svg.png"
                lower == "bloomberg ht" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/d/de/Bloomberg_HT_logosu.svg/320px-Bloomberg_HT_logosu.svg.png"
                lower == "tgrt haber" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/0/05/TGRT_Haber_logo.svg/320px-TGRT_Haber_logo.svg.png"
                lower == "tbmm tv" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/TBMM_TV_logosu.png/320px-TBMM_TV_logosu.png"
                lower == "ekol tv" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/6/65/Ekol_TV_logosu.png/320px-Ekol_TV_logosu.png"
                lower == "diyanet tv" -> "https://upload.wikimedia.org/wikipedia/tr/thumb/4/47/Diyanet_TV_logosu.png/320px-Diyanet_TV_logosu.png"
                lower == "24 tv" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/24_TV_logosu.svg/320px-24_TV_logosu.svg.png"
                lower == "360" -> "https://upload.wikimedia.org/wikipedia/commons/thumb/9/90/360_logosu.svg/320px-360_logosu.svg.png"
                "power" in lower -> "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
                "number 1" in lower || "nr1" in lower -> "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
                "minika" in lower -> "https://upload.wikimedia.org/wikipedia/tr/thumb/2/25/Minika_%C3%87ocuk_logosu.png/320px-Minika_%C3%87ocuk_logosu.png"
                else -> DEFAULT_ICON
            }
        }
    }

    private suspend fun fetchDecompressedJson(url: String): String {
        val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
        val bytes = response.body.bytes()
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun parseChannelsJson(jsonStr: String): List<ChannelItem> {
        val list = mutableListOf<ChannelItem>()
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val nanoid = obj.optString("nanoid")
            val name = obj.optString("name")
            val country = obj.optString("country")
            val sources = obj.optJSONObject("sources")

            val streams = mutableListOf<String>()
            val youtube = mutableListOf<String>()

            if (sources != null) {
                val streamsArr = sources.optJSONArray("streams")
                if (streamsArr != null) {
                    for (j in 0 until streamsArr.length()) {
                        val s = streamsArr.getString(j).trim()
                        // Filter out dead/pirate streams
                        if (s.isNotEmpty() && !s.contains("mangeinde4") && !s.contains(".lat/") && !s.endsWith(".lat")) {
                            streams.add(s)
                        }
                    }
                }

                val ytArr = sources.optJSONArray("youtube")
                if (ytArr != null) {
                    for (j in 0 until ytArr.length()) {
                        val y = ytArr.getString(j).trim()
                        if (y.isNotEmpty()) {
                            youtube.add(y)
                        }
                    }
                }
            }

            // Only add channels that have at least one valid stream or youtube link
            if (streams.isNotEmpty() || youtube.isNotEmpty()) {
                val logo = getChannelLogo(name)
                val item = ChannelItem(
                    nanoid = nanoid,
                    name = name,
                    streams = streams,
                    youtube = youtube,
                    country = country,
                    posterUrl = logo
                )
                list.add(item)
                channelCache[nanoid] = item
                channelCache[name] = item
            }
        }
        return list
    }

    private suspend fun getTrChannels(): List<ChannelItem> {
        val cached = trChannelsCache
        if (cached != null) return cached
        return try {
            val json = fetchDecompressedJson(TR_DATA_URL)
            val channels = parseChannelsJson(json)
            trChannelsCache = channels
            channels
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getAzChannels(): List<ChannelItem> {
        val cached = azChannelsCache
        if (cached != null) return cached
        return try {
            val json = fetchDecompressedJson(AZ_DATA_URL)
            val channels = parseChannelsJson(json)
            azChannelsCache = channels
            channels
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getDocChannels(): List<ChannelItem> {
        val cached = docChannelsCache
        if (cached != null) return cached
        return try {
            val json = fetchDecompressedJson(DOC_DATA_URL)
            val channels = parseChannelsJson(json)
            docChannelsCache = channels
            channels
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data
        val items = when (category) {
            "tr_ulusal" -> {
                val all = getTrChannels()
                val nationalKeywords = listOf(
                    "trt 1", "trt 2", "atv", "a2", "360", "24 tv", "tv8", "tv 8", "tv 4", "tv4",
                    "show", "star", "kanal d", "beyaz tv", "now", "teve2", "flash", "cine 1", "bi kanal",
                    "kanal 7", "trt turk", "trt türk", "trt avaz", "trt world", "diyanet"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    nationalKeywords.any { lower.contains(it) }
                }
            }
            "tr_haber" -> {
                val all = getTrChannels()
                val newsKeywords = listOf(
                    "haber", "news", "cnn", "bloomberg", "ekoturk", "finans", "tbmm", "meclis",
                    "parlamento", "turkhaber", "benguturk", "halk tv", "tele1", "ekol tv",
                    "ulusal kanal", "krt tv", "tv 100", "tv100", "tvnet", "sözcü"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    newsKeywords.any { lower.contains(it) }
                }
            }
            "tr_muzik" -> {
                val all = getTrChannels()
                val musicKeywords = listOf(
                    "muzik", "müzik", "music", "power", "number 1", "kral", "nr1", "akustik",
                    "slow", "dance", "damar", "taptaze"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    musicKeywords.any { lower.contains(it) }
                }
            }
            "tr_cocuk" -> {
                val all = getTrChannels()
                val kidsKeywords = listOf(
                    "cocuk", "çocuk", "cartoon", "minika", "spacetoon", "zarok", "animasyon", "kral şakir"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    kidsKeywords.any { lower.contains(it) }
                }
            }
            "tr_yerel" -> {
                val all = getTrChannels()
                val excludedKeywords = listOf(
                    "trt 1", "trt 2", "atv", "a2", "360", "24 tv", "tv8", "tv 8", "tv 4", "tv4",
                    "show", "star", "kanal d", "beyaz tv", "now", "teve2", "flash", "cine 1", "bi kanal",
                    "kanal 7", "trt turk", "trt türk", "trt avaz", "trt world", "diyanet",
                    "haber", "news", "cnn", "bloomberg", "ekoturk", "finans", "tbmm", "meclis",
                    "parlamento", "turkhaber", "benguturk", "halk tv", "tele1", "ekol tv",
                    "ulusal kanal", "krt tv", "tv 100", "tv100", "tvnet", "sözcü",
                    "muzik", "müzik", "music", "power", "number 1", "kral", "nr1", "akustik",
                    "slow", "dance", "damar", "taptaze",
                    "cocuk", "çocuk", "cartoon", "minika", "spacetoon", "zarok", "animasyon"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    excludedKeywords.none { lower.contains(it) }
                }
            }
            "tr_tum" -> getTrChannels()
            "az_tum" -> getAzChannels()
            "dunya_belgesel" -> getDocChannels()
            else -> getTrChannels()
        }

        val searchResponses = items.map { ch ->
            newLiveSearchResponse(
                name = ch.name,
                url = "famelack://${ch.country}/${ch.nanoid}",
                type = TvType.Live
            ) {
                this.posterUrl = ch.posterUrl
            }
        }

        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.lowercase().trim()
        val allTr = getTrChannels()
        val allAz = getAzChannels()
        val allDoc = getDocChannels()

        val combined = (allTr + allAz + allDoc).distinctBy { it.nanoid }
        return combined.filter { it.name.lowercase().contains(cleanQuery) }.map { ch ->
            newLiveSearchResponse(
                name = ch.name,
                url = "famelack://${ch.country}/${ch.nanoid}",
                type = TvType.Live
            ) {
                this.posterUrl = ch.posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val nanoid = url.substringAfterLast("/")
        val item = channelCache[nanoid] ?: run {
            // If not found in cache, load TR/AZ channels and search
            getTrChannels()
            getAzChannels()
            getDocChannels()
            channelCache[nanoid]
        }

        val title = item?.name ?: nanoid
        val poster = item?.posterUrl ?: DEFAULT_ICON
        val plotText = buildString {
            append("Famelack 7/24 Canlı Yayın")
            if (item != null) {
                if (item.streams.isNotEmpty()) append(" • ${item.streams.size} M3U8 Akışı")
                if (item.youtube.isNotEmpty()) append(" • YouTube Canlı")
            }
        }

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plotText
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val nanoid = data.substringAfterLast("/")
        val item = channelCache[nanoid] ?: return false

        var emitted = false

        // 1. Direct M3U8 streams
        item.streams.forEachIndexed { index, streamUrl ->
            try {
                val streamTitle = if (item.streams.size > 1) "${item.name} - Yayın ${index + 1}" else "${item.name} - HD"
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = streamTitle,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Origin" to mainUrl
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                emitted = true
            } catch (e: Exception) {
                // Ignore single link error
            }
        }

        // 2. YouTube live streams
        item.youtube.forEach { ytUrl ->
            try {
                val cleanYtUrl = when {
                    ytUrl.contains("/embed/") -> {
                        val videoId = ytUrl.substringAfter("/embed/").substringBefore("?").substringBefore("&")
                        "https://www.youtube.com/watch?v=$videoId"
                    }
                    else -> ytUrl
                }
                val handled = loadExtractor(cleanYtUrl, "$mainUrl/", subtitleCallback, callback)
                if (handled) emitted = true
            } catch (e: Exception) {
                // Ignore single extractor error
            }
        }

        return emitted
    }
}
