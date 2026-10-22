package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TRanimaci : MainAPI() {
    companion object {
        private const val TAG = "TRanimaci"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://tranimaci.com"
    override var name = "TRanimeci"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }

    // Son isteğin durumu (liste boş dönerse ekranda gösterilir; sorunu telefondan anlamak için)
    @Volatile private var lastDiag: String = "henüz istek yok"

    override val mainPage = mainPageOf(
        "${mainUrl}/"                            to "Ana Sayfa",
        "${mainUrl}/populer"                     to "Popüler",
        "${mainUrl}/yeni"                        to "Yeni",
        "${mainUrl}/yeni-cikanlar"               to "Yeni Çıkanlar",
        "${mainUrl}/klasikler"                   to "Klasikler",
        "${mainUrl}/anime-filmleri"              to "Anime Filmleri",
        "${mainUrl}/anime"                       to "Tüm Animeler",
        "${mainUrl}/kategori/aksiyon-anime"      to "Aksiyon",
        "${mainUrl}/kategori/macera-anime"       to "Macera",
        "${mainUrl}/kategori/komedi-anime"       to "Komedi",
        "${mainUrl}/kategori/fantastik-anime"    to "Fantastik",
        "${mainUrl}/kategori/romantik-anime"     to "Romantik",
        "${mainUrl}/kategori/drama-anime"        to "Drama"
    )

    // Küçük sonuç taşıyıcı: HTTP kodu + ham metin + ayrıştırılmış Document
    private class PageResult(val code: Int, val text: String, val document: Document)

    private fun buildHeaders(referer: String): MutableMap<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to (TRanimaciCloudflare.userAgent ?: DEFAULT_USER_AGENT),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to referer
        )
        val cookie = TRanimaciCloudflare.adoptFromWebView(TRanimaciPlugin.pluginContext)
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        return headers
    }

    // Cloudflare "Just a moment" / engel sayfası mı? (normal CF'li sayfalarda da bulunan
    // "challenge-platform" betiğini bilerek ölçüt almıyoruz, yoksa her sayfa engelli sanılır.)
    private fun looksLikeChallenge(code: Int, body: String): Boolean {
        if (body.contains("<title>Just a moment")) return true
        return (code == 403 || code == 503) &&
            (body.contains("cf-chl") || body.contains("Just a moment") || body.contains("Attention Required"))
    }

    private suspend fun requestPage(url: String, referer: String): PageResult? {
        return try {
            val res = app.get(url, headers = buildHeaders(referer), interceptor = cloudflareKiller)
            PageResult(res.code, res.text, res.document)
        } catch (e: Exception) {
            Log.e(TAG, "request error for $url: ${e.message}")
            null
        }
    }

    // ÖNCE normal istek atılır. WebView yalnızca gerçekten Cloudflare engeli varsa
    // (ve son denemeden beri bekleme süresi dolduysa) devreye girer; böylece engel
    // yokken eklenti hiç beklemez.
    private suspend fun safeGet(url: String, referer: String = "$mainUrl/"): Document? {
        var res = requestPage(url, referer)
        var note = ""

        if (res == null || looksLikeChallenge(res.code, res.text)) {
            note = if (res == null) "ilk istek hata verdi" else "Cloudflare engeli (HTTP ${res.code})"
            val context = TRanimaciPlugin.pluginContext
            if (context != null) {
                val cookie = TRanimaciCloudflare.resolveClearance(context, "$mainUrl/")
                if (!cookie.isNullOrBlank()) {
                    note += " -> WebView çerezi alındı"
                    res = requestPage(url, referer)
                } else {
                    note += " -> WebView çerez alamadı"
                }
            } else {
                note += " (context yok)"
            }
        }

        val r = res
        if (r == null) {
            lastDiag = "$note; yanıt alınamadı"
            return null
        }
        if (looksLikeChallenge(r.code, r.text)) {
            // Kayıtlı çerez reddedildiyse temizle ki yenisi alınabilsin
            TRanimaciCloudflare.invalidate(TRanimaciPlugin.pluginContext)
            lastDiag = "$note; hâlâ engelli (HTTP ${r.code}). Çözüm: Ayarlar > Uzantılar > TRanimeci dişlisine bas ve doğrulamayı kendin tamamla."
            return null
        }
        if (r.code == 404 || r.code == 410) {
            lastDiag = "HTTP ${r.code} (sayfa yok)"
            return null
        }
        lastDiag = "HTTP ${r.code}, ${r.text.length} bayt, başlık: ${r.document.title().take(40)}" +
            (if (note.isNotEmpty()) " [$note]" else "")
        return r.document
    }

    private fun diagnosticCard(label: String): SearchResponse {
        val short = if (lastDiag.contains("engel")) "Cloudflare doğrulaması gerekli — dokun" else "Sorun — dokun"
        return newAnimeSearchResponse("⚠ $short", "$mainUrl/anime/teshis", TvType.Anime) {
        }
    }

    private fun slugFromUrl(url: String): String =
        url.substringBefore("?").trimEnd('/').substringAfterLast('/')

    // Site "/en" gibi bir dil öneki kullanmıyor; tek istek yeterli.
    private suspend fun fetchPage(url: String): Pair<Document, String>? {
        val doc = safeGet(url) ?: return null
        return Pair(doc, url)
    }

    // ---------------------------------------------------------------------------------
    // Sitenin GERÇEK yapısı (kaydedilen sayfalardan doğrulandı):
    //  * Kartlar:  <a href="/anime/<slug>"> ... <img alt="Ad" src="/images/remote/..webp"> ... <h3>Ad</h3>
    //  * Arama:    /arama?q=<kelime>
    //  * Anime sayfası: <h1>, og:image, JSON-LD (TVSeries: description, genre, datePublished) ve
    //    bölümlerin TAMAMI Next.js (RSC) verisinde "episodes":[{href,title,number,thumbnail,...}]
    //    içinde bulunur (HTML'de yalnızca ilk 60 tanesi görünür).
    //  * Bölüm sayfası: /video/<slug>-<no>-bolum
    // ---------------------------------------------------------------------------------

    private fun Element.toAnimeCard(): SearchResponse? {
        val href = this.attr("href").trim().removePrefix(mainUrl)
        if (!href.startsWith("/anime/")) return null

        val img = this.selectFirst("img")
        val title = this.selectFirst("h3")?.text()?.trim().orEmpty()
            .ifBlank { img?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null

        val poster = fixUrlNull(img?.attr("src"))
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (a in document.select("a[href]")) {
            val path = a.attr("href").trim().removePrefix(mainUrl)
            if (!path.startsWith("/anime/")) continue
            if (a.selectFirst("img") == null) continue
            val card = a.toAnimeCard()
            if (card != null) out.add(card)
        }
        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sayfalama parametresi bilinmediği için yalnızca ilk sayfa getirilir (tekrar eden liste olmasın)
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val fetched = fetchPage(request.data)
            ?: return newHomePageResponse(request.name, listOf(diagnosticCard(request.name)), hasNext = false)

        val cards = parseCards(fetched.first)
        if (cards.isEmpty()) {
            lastDiag += "; 0 kart bulundu"
            return newHomePageResponse(request.name, listOf(diagnosticCard(request.name)), hasNext = false)
        }
        return newHomePageResponse(request.name, cards, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val (document, _) = fetchPage("$mainUrl/arama?q=$encoded") ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---- Next.js (RSC) verisini çözme -------------------------------------------------

    // <script>self.__next_f.push([1,"...."])</script> parçalarını birleştirip düz metne çevirir
    private fun rscPayload(document: Document): String {
        val marker = "self.__next_f.push("
        val sb = StringBuilder()
        for (script in document.select("script")) {
            val t = script.data()
            val idx = t.indexOf(marker)
            if (idx < 0) continue
            val inner = t.substring(idx + marker.length).trim().removeSuffix(";").trim().removeSuffix(")")
            try {
                val arr = JSONArray(inner)
                if (arr.length() > 1) sb.append(arr.optString(1, ""))
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    // '[' konumundan başlayıp eşleşen ']' konumunu bulur (metin içindeki köşeli parantezleri atlar)
    private fun matchBracket(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
            } else {
                if (c == '"') {
                    inStr = true
                } else if (c == '[') {
                    depth++
                } else if (c == ']') {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // payload içinde  "<key>":[ ... ]  dizisini bulur; accept() true dönen ilk diziyi verir
    private fun findArray(payload: String, key: String, accept: (JSONArray) -> Boolean): JSONArray? {
        val needle = "\"$key\":["
        var from = 0
        while (true) {
            val idx = payload.indexOf(needle, from)
            if (idx < 0) return null
            val start = idx + needle.length - 1
            val end = matchBracket(payload, start)
            if (end > start) {
                try {
                    val arr = JSONArray(payload.substring(start, end + 1))
                    if (accept(arr)) return arr
                } catch (_: Exception) {
                }
            }
            from = idx + needle.length
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestedUrl = fixUrl(url)

        // Teşhis kartına dokunulunca durumu ve çözümü göster
        if (requestedUrl.endsWith("/anime/teshis")) {
            val help = "Cloudflare doğrulaması için: CloudStream > Ayarlar > Uzantılar > TRanimeci yanındaki dişliye bas. " +
                "Açılan pencerede doğrulamayı kendin tamamla, sonra ana sayfayı yenile."
            return newMovieLoadResponse("Durum", requestedUrl, TvType.AnimeMovie, requestedUrl) {
                this.plot = "$lastDiag\n\n$help"
            }
        }

        val (document, fixedUrl) = fetchPage(requestedUrl) ?: return null

        val ld = parseJsonLd(document)

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" İzle")?.trim()
            ?: slugFromUrl(fixedUrl).replace('-', ' ').replaceFirstChar { it.uppercase() }

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: ld?.optJSONObject("image")?.optString("url")
        )

        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val year = ld?.optString("datePublished")?.take(4)?.toIntOrNull()

        val tags = mutableListOf<String>()
        val genres = ld?.optJSONArray("genre")
        if (genres != null) {
            for (i in 0 until genres.length()) {
                val g = genres.optString(i)
                if (g.isNotBlank()) tags.add(g)
            }
        }

        // 1) Bölümlerin tamamı RSC verisinde
        val episodes = mutableListOf<Episode>()
        val payload = rscPayload(document)
        val epArray = findArray(payload, "episodes", { arr ->
            val first = if (arr.length() > 0) arr.optJSONObject(0) else null
            first != null && (first.has("href") || first.has("slug"))
        })
        if (epArray != null) {
            for (i in 0 until epArray.length()) {
                val item = epArray.optJSONObject(i) ?: continue
                val href = item.optString("href").ifBlank {
                    item.optString("slug").takeIf { it.isNotBlank() }?.let { "/video/$it" } ?: ""
                }
                if (href.isBlank()) continue
                val number = if (item.has("number")) item.optInt("number") else i + 1
                val season = item.optInt("seasonNumber", 0).takeIf { it > 0 }
                val epTitle = item.optString("title").ifBlank { "$number. Bölüm" }
                val thumb = fixUrlNull(item.optString("thumbnail"))
                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = epTitle
                        this.episode = number
                        this.season = season
                        this.posterUrl = thumb
                    }
                )
            }
        }

        // 2) Yedek: HTML'deki bölüm bağlantıları (ilk 60 tanesi)
        if (episodes.isEmpty()) {
            document.select("a[href^=/video/]").forEach { a ->
                val epHref = a.attr("href")
                val num = Regex("-(\\d+)-bolum").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                if (num != null) {
                    episodes.add(
                        newEpisode(fixUrl(epHref)) {
                            this.name = "$num. Bölüm"
                            this.episode = num
                        }
                    )
                }
            }
        }

        val distinct = episodes.distinctBy { it.data }
        return if (distinct.isNotEmpty()) {
            newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addEpisodes(DubStatus.Subbed, distinct)
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, fixedUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // JSON-LD içindeki TVSeries kaydı
    private fun parseJsonLd(document: Document): JSONObject? {
        for (script in document.select("script[type=application/ld+json]")) {
            val txt = script.data().trim()
            try {
                if (txt.startsWith("[")) {
                    val arr = JSONArray(txt)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("@type") == "TVSeries") return o
                    }
                } else {
                    val o = JSONObject(txt)
                    if (o.optString("@type") == "TVSeries") return o
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ---- Oynatma -----------------------------------------------------------------------
    // NOT: Site, video kaynaklarını sayfaya GÖMMÜYOR. Kaydedilen bölüm sayfasında
    //   videoSources = [] ve viewerHasVideoAccess = false geliyor; kaynaklar yalnızca sitenin
    //   kendi oynatıcısı Cloudflare Turnstile doğrulamasını geçince ayrıca isteniyor.
    // Burada yalnızca sunucunun sayfada kendiliğinden verdiği kaynaklar kullanılır; Turnstile
    // doğrulaması aşılmaya çalışılmaz. Kaynak yoksa false döner.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val requestedData = fixUrl(data)
        val (document, pageUrl) = fetchPage(requestedData) ?: return false
        var hasFound = false

        // 1) Sayfadaki iframe / video etiketleri
        for (iframe in document.select("iframe")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && loadExtractor(fixUrl(src), pageUrl, subtitleCallback, callback)) {
                hasFound = true
            }
        }
        for (v in document.select("video source, video")) {
            val src = v.attr("src")
            if (src.isBlank()) continue
            val fixed = fixUrl(src)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "TRanimeci Direct",
                    url = fixed,
                    type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                }
            )
            hasFound = true
        }

        // 2) Sunucunun sayfaya yazdığı videoSources / mirrors (boş değilse)
        val payload = rscPayload(document)
        for (key in listOf("videoSources", "mirrors")) {
            val found = findArray(payload, key, { a -> a.length() > 0 })
            if (found == null) continue
            val arr: JSONArray = found
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val candidates = mutableListOf<String>()
                if (item is String) candidates.add(item)
                if (item is JSONObject) {
                    for (k in listOf("url", "src", "file", "link", "embed")) {
                        val v = item.optString(k)
                        if (v.startsWith("http") || v.startsWith("//")) candidates.add(v)
                    }
                }
                for (c in candidates) {
                    val link = fixUrl(c)
                    if (link.contains(".m3u8") || link.contains(".mp4")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "TRanimeci Stream",
                                url = link,
                                type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                            }
                        )
                        hasFound = true
                    } else if (loadExtractor(link, pageUrl, subtitleCallback, callback)) {
                        hasFound = true
                    }
                }
            }
        }

        return hasFound
    }
}
