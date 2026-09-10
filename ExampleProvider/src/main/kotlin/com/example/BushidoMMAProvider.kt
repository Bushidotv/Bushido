package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class BushidoMMAProvider : MainAPI() {
    override var mainUrl = "https://fullfightreplays.com"
    override var name = "Bushido MMA"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true

    override val mainPage = mainPageOf(
        Pair("$mainUrl/ufc",               "UFC Replays"),
        Pair("$mainUrl/boxing",            "Boxing Replays"),
        Pair("$mainUrl/k-1",               "K-1 / Muay Thai / Kickboxing"),
        Pair("$mainUrl/bellator",          "Bellator MMA"),
        Pair("$mainUrl/one-championship",  "One Championship"),
        Pair("$mainUrl/bkfc",              "BKFC"),
        Pair("$mainUrl/cage-warriors",     "Cage Warriors"),
        Pair("$mainUrl/ksw",               "KSW"),
        Pair("$mainUrl/invicta-fc",        "Invicta FC"),
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer"    to "$mainUrl/",
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // ─────────────────────────────────────────────
    // 1. ANA SAYFA — kategorilere göre listeyi çek
    // ─────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categoryUrl = request.data
        // Sayfalama: site /videos/0-{page}-{timestamp} gibi bir yapı kullanıyor,
        // ama normal kategori URL'leri için sayfayı GET ile çekiyoruz.
        val url = if (page == 1) categoryUrl else "$categoryUrl/$page"

        val doc = app.get(url, headers = browserHeaders).document
        val items = parseListings(doc)

        return newHomePageResponse(
            name     = request.name,
            list     = items,
            hasNext  = items.size >= 10   // 10'dan az gelirse son sayfadayız
        )
    }

    // ─────────────────────────────────────────────
    // 2. ARAMA
    // ─────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = browserHeaders).document
        return parseListings(doc)
    }

    // ─────────────────────────────────────────────
    // 3. YARDIMCI — Kart listesini parse et
    // ─────────────────────────────────────────────
    private fun parseListings(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("div.short_item").mapNotNull { el ->
            val anchor  = el.selectFirst("div.poster a") ?: return@mapNotNull null
            val href    = anchor.attr("href").trim()
            val poster  = anchor.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            val title   = el.selectFirst("div.short_content h3 a")?.text()?.trim()
                ?: return@mapNotNull null

            newMovieSearchResponse(
                name      = title,
                url       = fixUrl(href),
                type      = TvType.Movie
            ) {
                this.posterUrl = poster
            }
        }
    }

    // ─────────────────────────────────────────────
    // 4. YÜKLE — Detay sayfası
    // ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = browserHeaders).document

        val title = doc.selectFirst("h1.h_title")?.text()?.trim()
            ?: doc.title().trim()

        val poster = doc.selectFirst("div.full_img img")?.attr("src")?.let { fixUrl(it) }

        val description = doc.selectFirst("div.fullstory p:last-of-type")?.text()?.trim()
            ?: "UFC, MMA, Boxing, K-1 full fight replay."

        // Video linklerini veri olarak URL'ye ekleyerek geçiriyoruz
        return newMovieLoadResponse(
            name     = title,
            url      = url,
            dataUrl  = url,
            type     = TvType.Movie
        ) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    // ─────────────────────────────────────────────
    // 5. LİNKLERİ YÜKLE — Tüm mirror/embed linkleri
    // ─────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = browserHeaders).document
        var foundAny = false

        // Tüm linkleri topla
        val links = doc.select("div.fullstory a[href]")
            .map { it.attr("href").trim() }
            .filter { href ->
                href.startsWith("http") &&
                !href.contains("fullfightreplays.com") &&
                !href.contains("javascript") &&
                href.isNotEmpty()
            }
            .distinct()

        for (link in links) {
            try {
                // CloudStream'in built-in extractor zincirini çalıştır.
                // Dailymotion, OK.ru, Filemoon, StreamWish gibi
                // onlarca kaynağı otomatik tanır.
                loadExtractor(link, data, subtitleCallback, callback)
                foundAny = true
            } catch (_: Exception) {}
        }

        return foundAny
    }
}
