package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * disk.yandex.com.tr video oynaticisi (BelgeselX kaynaklarinda kullaniliyor).
 * Sayfadaki master-playlist.m3u8 adresini bulur.
 */
open class DiskYandexComTr : ExtractorApi() {
    override val name = "Yandex Disk"
    override val mainUrl = "https://disk.yandex.com.tr"
    override val requiresReferer = false

    private val masterPlaylistRegex = Regex("""https?://[^\s"]*?master-playlist\.m3u8""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(
            url = url,
            referer = "https://disk.yandex.com.tr/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )

        val masterUrl = masterPlaylistRegex.find(response.text)?.value
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
            ?: throw ErrorLoadingException("master-playlist.m3u8 bulunamadı")

        Log.d(name, "m3u8 » $masterUrl")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: "https://disk.yandex.com.tr/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
