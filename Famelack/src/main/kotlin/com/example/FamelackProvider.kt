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
        // --- TÜRKİYE KANALLARI ---
        Pair("tr_ulusal", "🇹🇷 Türkiye: Ulusal Kanallar"),
        Pair("tr_haber", "🇹🇷 Türkiye: Haber Kanalları"),
        Pair("tr_muzik", "🇹🇷 Türkiye: Müzik & Gençlik"),
        Pair("tr_cocuk", "🇹🇷 Türkiye: Çocuk Kanalları"),
        Pair("tr_yerel", "🇹🇷 Türkiye: Yerel & Bölgesel"),
        Pair("tr_tum", "🇹🇷 Türkiye: Tüm Kanallar (165+)"),

        // --- DÜNYA TEMATİK KATEGORİLERİ ---
        Pair("cat_movies", "🎬 Dünya: Film & Sinema"),
        Pair("cat_series", "📺 Dünya: Diziler"),
        Pair("cat_news", "📰 Dünya: Haber"),
        Pair("cat_documentary", "🌍 Dünya: Belgesel"),
        Pair("cat_music", "🎵 Dünya: Müzik"),
        Pair("cat_kids", "👶 Dünya: Çocuk & Animasyon"),
        Pair("cat_entertainment", "🎭 Dünya: Eğlence & Şov"),
        Pair("cat_sports", "⚽ Dünya: Spor"),
        Pair("cat_animation", "🎨 Dünya: Animasyon"),
        Pair("cat_comedy", "😂 Dünya: Komedi"),
        Pair("cat_culture", "🏛️ Dünya: Kültür & Sanat"),
        Pair("cat_science", "🔬 Dünya: Bilim & Doğa"),

        // --- POPÜLER DÜNYA ÜLKELERİ ---
        Pair("country_az", "🇦🇿 Azerbaycan (16)"),
        Pair("country_de", "🇩🇪 Almanya (166)"),
        Pair("country_us", "🇺🇸 Amerika Birleşik Devletleri (1435)"),
        Pair("country_uk", "🇬🇧 Birleşik Krallık (219)"),
        Pair("country_fr", "🇫🇷 Fransa (147)"),
        Pair("country_it", "🇮🇹 İtalya (188)"),
        Pair("country_es", "🇪🇸 İspanya (152)"),
        Pair("country_ru", "🇷🇺 Rusya (238)"),
        Pair("country_nl", "🇳🇱 Hollanda (105)"),
        Pair("country_ca", "🇨🇦 Kanada (159)"),
        Pair("country_br", "🇧🇷 Brezilya (131)"),
        Pair("country_ar", "🇦🇷 Arjantin (150)"),
        Pair("country_cl", "🇨🇱 Şili (189)"),
        Pair("country_mx", "🇲🇽 Meksika (113)"),
        Pair("country_in", "🇮🇳 Hindistan (599)"),
        Pair("country_jp", "🇯🇵 Japonya (30)"),
        Pair("country_kr", "🇰🇷 Güney Kore (70)"),
        Pair("country_gr", "🇬🇷 Yunanistan (52)"),
        Pair("country_ua", "🇺🇦 Ukrayna (65)"),
        Pair("country_pl", "🇵🇱 Polonya (69)"),
        Pair("country_ro", "🇷🇴 Romanya (93)"),
        Pair("country_be", "🇧🇪 Belçika (20)"),
        Pair("country_at", "🇦🇹 Avusturya (36)"),
        Pair("country_ch", "🇨🇭 İsviçre (18)"),
        Pair("country_se", "🇸🇪 İsveç (13)"),
        Pair("country_no", "🇳🇴 Norveç (16)"),
        Pair("country_pt", "🇵🇹 Portekiz (44)"),
        Pair("country_au", "🇦🇺 Avustralya (52)"),
        Pair("country_ge", "🇬🇪 Gürcistan (20)"),
        Pair("country_kz", "🇰🇿 Kazakistan (42)"),
        Pair("country_uz", "🇺🇿 Özbekistan (35)"),

        // --- TÜM DİĞER ÜLKELER (A-Z) ---
        Pair("country_af", "Afganistan (14)"),
        Pair("country_al", "Arnavutluk (8)"),
        Pair("country_dz", "Cezayir (3)"),
        Pair("country_ad", "Andorra (2)"),
        Pair("country_ao", "Angola (3)"),
        Pair("country_am", "Ermenistan (5)"),
        Pair("country_aw", "Aruba (9)"),
        Pair("country_bs", "Bahamalar (2)"),
        Pair("country_bh", "Bahreyn (5)"),
        Pair("country_bd", "Bangladeş (22)"),
        Pair("country_by", "Belarus (14)"),
        Pair("country_bz", "Belize (1)"),
        Pair("country_bj", "Benin (6)"),
        Pair("country_bo", "Bolivya (49)"),
        Pair("country_ba", "Bosna Hersek (6)"),
        Pair("country_bg", "Bulgaristan (18)"),
        Pair("country_kh", "Kamboçya (9)"),
        Pair("country_cm", "Kamerun (9)"),
        Pair("country_co", "Kolombiya (77)"),
        Pair("country_cr", "Kosta Rika (53)"),
        Pair("country_hr", "Hırvatistan (22)"),
        Pair("country_cu", "Küba (11)"),
        Pair("country_cy", "Kıbrıs (10)"),
        Pair("country_cz", "Çekya (34)"),
        Pair("country_dk", "Danimarka (11)"),
        Pair("country_do", "Dominik Cumhuriyeti (169)"),
        Pair("country_ec", "Ekvador (36)"),
        Pair("country_eg", "Mısır (24)"),
        Pair("country_sv", "El Salvador (16)"),
        Pair("country_ee", "Estonya (4)"),
        Pair("country_fi", "Finlandiya (8)"),
        Pair("country_gh", "Gana (20)"),
        Pair("country_gt", "Guatemala (24)"),
        Pair("country_hn", "Honduras (32)"),
        Pair("country_hk", "Hong Kong (11)"),
        Pair("country_hu", "Macaristan (15)"),
        Pair("country_is", "İzlanda (4)"),
        Pair("country_id", "Endonezya (108)"),
        Pair("country_ir", "İran (62)"),
        Pair("country_iq", "Irak (21)"),
        Pair("country_ie", "İrlanda (11)"),
        Pair("country_il", "İsrail (17)"),
        Pair("country_jm", "Jamaika (6)"),
        Pair("country_jo", "Ürdün (12)"),
        Pair("country_ke", "Kenya (33)"),
        Pair("country_xk", "Kosova (5)"),
        Pair("country_kw", "Kuveyt (7)"),
        Pair("country_lv", "Letonya (16)"),
        Pair("country_lb", "Lübnan (13)"),
        Pair("country_ly", "Libya (11)"),
        Pair("country_lt", "Litvanya (8)"),
        Pair("country_lu", "Lüksemburg (5)"),
        Pair("country_my", "Malezya (21)"),
        Pair("country_mt", "Malta (9)"),
        Pair("country_md", "Moldova (24)"),
        Pair("country_mc", "Monako (3)"),
        Pair("country_mn", "Moğolistan (23)"),
        Pair("country_me", "Karadağ (9)"),
        Pair("country_ma", "Fas (12)"),
        Pair("country_np", "Nepal (12)"),
        Pair("country_nz", "Yeni Zelanda (15)"),
        Pair("country_ni", "Nikaragua (16)"),
        Pair("country_ng", "Nijerya (30)"),
        Pair("country_mk", "Kuzey Makedonya (17)"),
        Pair("country_om", "Umman (3)"),
        Pair("country_pk", "Pakistan (57)"),
        Pair("country_ps", "Filistin (6)"),
        Pair("country_pa", "Panama (19)"),
        Pair("country_py", "Paraguay (12)"),
        Pair("country_pe", "Peru (94)"),
        Pair("country_ph", "Filipinler (36)"),
        Pair("country_pr", "Porto Riko (16)"),
        Pair("country_qa", "Katar (9)"),
        Pair("country_sm", "San Marino (3)"),
        Pair("country_sa", "Suudi Arabistan (23)"),
        Pair("country_sn", "Senegal (17)"),
        Pair("country_rs", "Sırbistan (26)"),
        Pair("country_sg", "Singapur (10)"),
        Pair("country_sk", "Slovakya (31)"),
        Pair("country_si", "Slovenya (16)"),
        Pair("country_so", "Somali (13)"),
        Pair("country_za", "Güney Afrika (20)"),
        Pair("country_tw", "Tayvan (35)"),
        Pair("country_th", "Tayland (51)"),
        Pair("country_tn", "Tunus (4)"),
        Pair("country_ae", "Birleşik Arap Emirlikleri (31)"),
        Pair("country_uy", "Uruguay (2)"),
        Pair("country_ve", "Venezuela (49)"),
        Pair("country_vn", "Vietnam (47)"),
        Pair("country_ye", "Yemen (3)")
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

        private const val BASE_DATA_URL =
            "https://raw.githubusercontent.com/famelack/famelack-data/main/tv/compressed"

        private const val DEFAULT_ICON =
            "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"

        private val countryCache = mutableMapOf<String, List<ChannelItem>>()
        private val categoryCache = mutableMapOf<String, List<ChannelItem>>()
        private val channelCache = mutableMapOf<String, ChannelItem>()
        private var allChannelsCache: List<ChannelItem>? = null

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
                        // Filter out dead/broken pirate links
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

    private suspend fun getChannelsByCountry(code: String): List<ChannelItem> {
        val lowerCode = code.lowercase().trim()
        val cached = countryCache[lowerCode]
        if (cached != null) return cached
        return try {
            val url = "$BASE_DATA_URL/countries/$lowerCode.json"
            val json = fetchDecompressedJson(url)
            val channels = parseChannelsJson(json)
            countryCache[lowerCode] = channels
            channels
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getChannelsByCategory(category: String): List<ChannelItem> {
        val lowerCat = category.lowercase().trim()
        val cached = categoryCache[lowerCat]
        if (cached != null) return cached
        return try {
            val url = "$BASE_DATA_URL/categories/$lowerCat.json"
            val json = fetchDecompressedJson(url)
            val channels = parseChannelsJson(json)
            categoryCache[lowerCat] = channels
            channels
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getAllWorldChannels(): List<ChannelItem> {
        val cached = allChannelsCache
        if (cached != null) return cached
        return try {
            val url = "$BASE_DATA_URL/categories/all.json"
            val json = fetchDecompressedJson(url)
            val channels = parseChannelsJson(json)
            allChannelsCache = channels
            channels
        } catch (e: Exception) {
            countryCache["tr"] ?: emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val items = when {
            data.startsWith("country_") -> {
                val code = data.removePrefix("country_")
                getChannelsByCountry(code)
            }
            data.startsWith("cat_") -> {
                val cat = data.removePrefix("cat_")
                getChannelsByCategory(cat)
            }
            data == "tr_ulusal" -> {
                val all = getChannelsByCountry("tr")
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
            data == "tr_haber" -> {
                val all = getChannelsByCountry("tr")
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
            data == "tr_muzik" -> {
                val all = getChannelsByCountry("tr")
                val musicKeywords = listOf(
                    "muzik", "müzik", "music", "power", "number 1", "kral", "nr1", "akustik",
                    "slow", "dance", "damar", "taptaze"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    musicKeywords.any { lower.contains(it) }
                }
            }
            data == "tr_cocuk" -> {
                val all = getChannelsByCountry("tr")
                val kidsKeywords = listOf(
                    "cocuk", "çocuk", "cartoon", "minika", "spacetoon", "zarok", "animasyon", "kral şakir"
                )
                all.filter { ch ->
                    val lower = ch.name.lowercase()
                    kidsKeywords.any { lower.contains(it) }
                }
            }
            data == "tr_yerel" -> {
                val all = getChannelsByCountry("tr")
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
            data == "tr_tum" -> getChannelsByCountry("tr")
            else -> getChannelsByCountry("tr")
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
        val allChannels = getAllWorldChannels()
        return allChannels.filter { it.name.lowercase().contains(cleanQuery) }.map { ch ->
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
        val country = url.removePrefix("famelack://").substringBefore("/")
        val item = channelCache[nanoid] ?: run {
            if (country.isNotEmpty() && country != "famelack:") {
                getChannelsByCountry(country)
            }
            channelCache[nanoid] ?: run {
                getAllWorldChannels()
                channelCache[nanoid]
            }
        }

        val title = item?.name ?: nanoid
        val poster = item?.posterUrl ?: DEFAULT_ICON
        val plotText = buildString {
            append("Famelack 7/24 Canlı Yayın")
            if (item != null) {
                if (item.country.isNotEmpty()) append(" • Ülke: ${item.country.uppercase()}")
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
