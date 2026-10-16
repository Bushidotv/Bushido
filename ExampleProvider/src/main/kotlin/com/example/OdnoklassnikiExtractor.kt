package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ok.ru / odnoklassniki.ru video oynaticisi (BelgeselX kaynaklarinda kullaniliyor).
 */
open class Odnoklassniki : ExtractorApi() {
    override val name = "Odnoklassniki"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val userAgent = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        )

        val html = app.get(url, headers = userAgent).text
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { m ->
                Integer.parseInt(m.groupValues[1], 16).toChar().toString()
            }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Video bulunamadı")

        var found = false
        Regex("""\{[^{}]*}""").findAll(videosStr).forEach { obj ->
            val body = obj.value
            val vName = Regex(""""name"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1) ?: return@forEach
            val rawUrl = Regex(""""url"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: return@forEach
            if (rawUrl.isBlank()) return@forEach

            val videoUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            val quality = vName.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "4k")

            Log.d(name, "video » $vName -> $videoUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = getQualityFromName(quality)
                    this.headers = userAgent
                }
            )
            found = true
        }

        if (!found) throw ErrorLoadingException("Video bulunamadı")
    }
}
