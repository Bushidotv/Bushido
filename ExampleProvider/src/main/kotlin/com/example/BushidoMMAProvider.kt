package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class BushidoMMAProvider : MainAPI() {
    override var mainUrl = "https://watchmmafull.com"
    override var name = "Bushido MMA"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        Pair("$mainUrl/new/",               "All Fights"),
        Pair("$mainUrl/search/ufc/",        "UFC"),
        Pair("$mainUrl/search/bellator/",   "Bellator MMA"),
        Pair("$mainUrl/search/one/",        "ONE Championship"),
        Pair("$mainUrl/search/boxing/",     "Boxing"),
        Pair("$mainUrl/search/kickboxing/", "Kickboxing / K-1"),
        Pair("$mainUrl/search/pfl/",        "PFL"),
        Pair("$mainUrl/search/ksw/",        "KSW"),
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
        val baseUrl = request.data

        // Ana sayfa: /new/1, /new/2 ... /new/436
        // Arama sayfaları: /search/ufc/?page=2
        val url = if (page == 1) {
            if (baseUrl.contains("/new/")) "${baseUrl}1" else baseUrl
        } else {
            if (baseUrl.contains("/new/")) "$mainUrl/new/$page" else "$baseUrl?page=$page"
        }

        val doc = app.get(url, headers = browserHeaders).document
        val items = parseListings(doc)

        return newHomePageResponse(
            name    = request.name,
            list    = items,
            hasNext = items.size >= 10
        )
    }

    // ─────────────────────────────────────────────
    // 2. ARAMA
    // ─────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.trim().replace(" ", "-")}/"
        val doc = app.get(url, headers = browserHeaders).document
        return parseListings(doc)
    }

    // ─────────────────────────────────────────────
    // 3. YARDIMCI — Kart listesini parse et
    // Kart yapısı: li.video-item-compact > h2.video-title-compact > a
    // ─────────────────────────────────────────────
    private fun parseListings(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("li.video-item-compact").mapNotNull { el ->
            val anchor = el.selectFirst("h2.video-title-compact a") ?: return@mapNotNull null
            val href   = anchor.attr("href").trim()
            val title  = anchor.text().trim().ifEmpty { return@mapNotNull null }

            // Poster: fighter fotoğrafını dene, yoksa null
            val poster = el.selectFirst("div.cfs-photo img")?.attr("src")?.let { fixUrl(it) }

            newMovieSearchResponse(
                name = title,
                url  = fixUrl(href),
                type = TvType.Movie
            ) {
                this.posterUrl = poster
            }
        }
    }

    // ─────────────────────────────────────────────
    // 4. DETAY SAYFASI
    // ─────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = browserHeaders).document

        val title = doc.selectFirst("h1.ft-title, h1")?.text()?.trim()
            ?: doc.title().trim()

        // Poster: Fight card'dan büyük resim dene
        val poster = doc.selectFirst("div.cfs-photo img, div.card-fighter-strip img")
            ?.attr("src")?.let { fixUrl(it) }

        val description = doc.selectFirst("div.ft-desc")?.text()?.trim()
            ?: "MMA full fight replay."

        return newMovieLoadResponse(
            name    = title,
            url     = url,
            dataUrl = url,
            type    = TvType.Movie
        ) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    // ─────────────────────────────────────────────
    // 5. LİNKLERİ YÜKLE
    //
    // Akış:
    //   Detay sayfası
    //     └─► div.watch-link-grid a.watch-link-btn  (embed URL'leri)
    //           https://watchmmafull.com/embed/XXXXXX
    //               └─► we-player > iframe src       (gerçek oynatıcı)
    //                     mmaringen.com/?v=...
    //                     vixeo.io/e/...
    //                     dailymotion.com/...  vb.
    //
    // Reklam sayfalarını (pubadx, histats vb.) filtrele,
    // sadece "Free Server" butonlarındaki embed linklerini işle.
    // ─────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = browserHeaders).document
        var foundAny = false

        // Free Server butonlarını topla
        val embedLinks = doc.select("div.watch-link-grid a.watch-link-btn[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") && it.contains("watchmmafull.com/embed/") }
            .distinct()

        for (embedUrl in embedLinks) {
            try {
                // Her embed sayfasını aç ve içindeki iframe src'yi çek
                val embedDoc = app.get(
                    embedUrl,
                    headers = browserHeaders + mapOf("Referer" to data)
                ).document

                val iframeSrc = embedDoc.selectFirst("div.we-player iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    ?: continue

                // Boş veya reklam URL'lerini atla
                if (iframeSrc.isBlank()) continue
                if (!iframeSrc.startsWith("http")) continue
                if (isAdUrl(iframeSrc)) continue

                // CloudStream'in extractor zincirini çalıştır
                // mmaringen.com, vixeo.io, dailymotion, streamwish, filemoon
                // gibi yüzlerce kaynağı otomatik tanır
                try {
                    loadExtractor(iframeSrc, embedUrl, subtitleCallback, callback)
                    foundAny = true
                } catch (_: Exception) {
                    // Extractor tanımazsa devam et
                }
            } catch (_: Exception) {
                // Embed sayfası açılamazsa devam et
            }
        }

        return foundAny
    }

    // ─────────────────────────────────────────────
    // Reklam / tracker URL filtresi
    // ─────────────────────────────────────────────
    private fun isAdUrl(url: String): Boolean {
        val adDomains = listOf(
            "pubadx.one", "histats.com", "cloudflareinsights.com",
            "googletagmanager.com", "googlesyndication.com",
            "llvpn.com", "adsbygoogle", "doubleclick.net",
            "amazon-adsystem.com", "exoclick.com", "trafficjunky.com",
            "juicyads.com", "adnxs.com", "adsrvr.org"
        )
        return adDomains.any { url.contains(it) }
    }
}
