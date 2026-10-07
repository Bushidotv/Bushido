package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class RapidExtractor : ExtractorApi() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val name = "Rapid"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: mainUrl)
        val rawHtml = response.text
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null

        // ── 1. sources: [{file: VAR}] değişken adını bul ──
        val sourceVarMatch = Regex("""sources:\s*\[\s*\{file:\s*(\w+)""").find(rawHtml)
        val sourceVar = sourceVarMatch?.groupValues?.get(1)?.takeIf { it != "atob" }
        Log.d(name, "Rapid source değişkeni: $sourceVar")

        // ── 2. Packer JS bloğunu bul ──
        val startMarker = "eval(function(p,a,c,k,e,d){"
        val endMarker = ",0,{}))"
        val startIdx = rawHtml.indexOf(startMarker)
        val endIdx = if (startIdx != -1) rawHtml.indexOf(endMarker, startIdx) else -1

        if (startIdx != -1 && endIdx != -1) {
            val packedBlock = rawHtml.substring(startIdx, endIdx + endMarker.length)
            Log.d(name, "Packer bloğu bulundu (uzunluk: ${packedBlock.length}), Rhino ile çalıştırılıyor...")

            // Packer JS kodunu çalıştır ve sourceVar değerini al
            val jsCode = if (sourceVar != null) {
                """
                    $packedBlock;
                    var __target = typeof $sourceVar !== 'undefined' ? $sourceVar : null;
                    __target;
                """.trimIndent()
            } else {
                """
                    var __res = $packedBlock;
                    __res;
                """.trimIndent()
            }

            val rhinoResult = executeJsWithRhino(jsCode)?.trim()
            if (!rhinoResult.isNullOrBlank() && rhinoResult.startsWith("http")) {
                videoUrl = rhinoResult
                Log.d(name, "Rhino ile doğrudan URL bulundu: $videoUrl")
            } else if (rhinoResult != null && sourceVar == null) {
                // Eğer packedBlock bir string döndürdüyse (unpacked kod), içinde URL veya değişken ara
                val urlMatch = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(rhinoResult)
                    ?: Regex("""(https?://[^\s"'<>]+\.txt[^\s"'<>]*)""").find(rhinoResult)
                videoUrl = urlMatch?.groupValues?.get(1)
            }
        }

        // ── 3. Fallback: JSON-LD ──
        if (videoUrl.isNullOrBlank() || !videoUrl.startsWith("http")) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)?.replace(".txt", ".m3u8")
            Log.d(name, "Fallback JSON-LD: $videoUrl")
        }

        // ── 4. Fallback: Doğrudan m3u8 regex ──
        if (videoUrl.isNullOrBlank() || !videoUrl.startsWith("http")) {
            val directMatch = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(rawHtml)
            videoUrl = directMatch?.groupValues?.get(1)?.replace("\\/", "/")
            Log.d(name, "Fallback direkt m3u8: $videoUrl")
        }

        if (videoUrl.isNullOrBlank() || !videoUrl.startsWith("http")) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        parseSubtitles(rawHtml, subtitleCallback)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainUrl
                )
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl")
    }

    private fun executeJsWithRhino(jsCode: String): String? {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1 // Android için interpreter modu
            val scope = cx.initStandardObjects()

            val atobFunc = object : BaseFunction() {
                override fun call(
                    cx: Context?,
                    scope: Scriptable?,
                    thisObj: Scriptable?,
                    args: Array<out Any>?
                ): Any {
                    val str = args?.getOrNull(0)?.toString() ?: return ""
                    val trimmed = str.trim()
                    val pad = trimmed.length % 4
                    val padded = if (pad != 0) trimmed + "=".repeat(4 - pad) else trimmed
                    val bytes = Base64.decode(padded, Base64.DEFAULT)
                    return String(bytes, Charsets.ISO_8859_1)
                }
            }
            ScriptableObject.putProperty(scope, "atob", atobFunc)

            val btoaFunc = object : BaseFunction() {
                override fun call(
                    cx: Context?,
                    scope: Scriptable?,
                    thisObj: Scriptable?,
                    args: Array<out Any>?
                ): Any {
                    val str = args?.getOrNull(0)?.toString() ?: return ""
                    val bytes = str.toByteArray(Charsets.ISO_8859_1)
                    return Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
            ScriptableObject.putProperty(scope, "btoa", btoaFunc)

            val result = cx.evaluateString(scope, jsCode, "rapid_decrypter", 1, null)
            return Context.toString(result)
        } catch (e: Exception) {
            Log.e(name, "Rhino çalıştırma hatası: ${e.message}")
            return null
        } finally {
            Context.exit()
        }
    }

    private suspend fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val subMatches = Regex(
                """"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)".*?"language"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(tracksStr).toList()

            subMatches.forEachIndexed { index, match ->
                var subUrl = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                val subLabel = match.groupValues[2]
                val langCode = match.groupValues[3]
                if (!subUrl.startsWith("http")) {
                    subUrl = mainUrl.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                }

                val lang = when {
                    langCode == "forced" || subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    langCode == "tr" || subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    langCode == "en" || subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEachIndexed
                }

                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }
        }
    }
}
