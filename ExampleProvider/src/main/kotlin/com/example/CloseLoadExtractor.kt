package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: "")
        val rawHtml = response.text
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null

        // ── 1. JWPlayer sources:{file: VAR} içerisinden gerçek değişken adını bul ──
        val sourceVarMatch = Regex("""sources:\s*\[\s*\{file:\s*(\w+)""").find(rawHtml)
        val sourceVar = sourceVarMatch?.groupValues?.get(1)?.takeIf { it != "atob" }
        Log.d(name, "JWPlayer source değişkeni: $sourceVar")

        if (sourceVar != null) {
            // var sourceVar = funcName(...) çağrısını bul
            val assignMatch = Regex("""var\s+$sourceVar\s*=\s*([^;]+);""").find(rawHtml)
            if (assignMatch != null) {
                val callExpr = assignMatch.groupValues[1].trim()
                val funcNameMatch = Regex("""^(\w+)\s*\(""").find(callExpr)
                val funcName = funcNameMatch?.groupValues?.get(1)

                if (funcName != null) {
                    val funcDef = extractFunctionDefinition(rawHtml, funcName)
                    if (funcDef != null) {
                        Log.d(name, "Fonksiyon tanımı bulundu ($funcName), Rhino ile çalıştırılıyor...")
                        val jsCode = """
                            $funcDef;
                            var __res = $callExpr;
                            __res;
                        """.trimIndent()

                        videoUrl = executeJsWithRhino(jsCode)?.trim()
                        Log.d(name, "Rhino çözülen URL: $videoUrl")
                    }
                }
            }
        }

        // ── 2. Fallback: JSON-LD contentUrl ──
        if (videoUrl.isNullOrBlank() || !videoUrl.startsWith("http")) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)
            Log.d(name, "Fallback JSON-LD: $videoUrl")
        }

        if (videoUrl.isNullOrBlank() || !videoUrl.startsWith("http")) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        parseSubtitles(rawHtml, subtitleCallback)

        // HLS Linkini CloudStream'e ilet
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
                    "Origin" to "https://closeload.filmmakinesi.to"
                )
            }
        )
        Log.d(name, "ExtractorLink başarıyla eklendi: $videoUrl")
    }

    /**
     * rawHtml içerisinden `var funcName = function(...) { ... }` veya `function funcName(...) { ... }` bloğunu çıkarır
     */
    private fun extractFunctionDefinition(rawHtml: String, funcName: String): String? {
        var startIdx = rawHtml.indexOf("var $funcName = function")
        if (startIdx == -1) startIdx = rawHtml.indexOf("function $funcName(")
        if (startIdx == -1) startIdx = rawHtml.indexOf("$funcName = function")
        if (startIdx == -1) return null

        val braceIdx = rawHtml.indexOf('{', startIdx)
        if (braceIdx == -1) return null

        var count = 1
        var i = braceIdx + 1
        while (count > 0 && i < rawHtml.length) {
            when (rawHtml[i]) {
                '{' -> count++
                '}' -> count--
            }
            i++
        }
        return if (count == 0) rawHtml.substring(startIdx, i) else null
    }

    /**
     * Pure Java Rhino JS Engine kullanarak scripti değerlendirir
     */
    private fun executeJsWithRhino(jsCode: String): String? {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1 // Android için yorumlayıcı (interpreter) modu
            val scope = cx.initStandardObjects()

            // atob fonksiyonunu ekle
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

            val result = cx.evaluateString(scope, jsCode, "closeload_decrypter", 1, null)
            return Context.toString(result)
        } catch (e: Exception) {
            Log.e(name, "Rhino çalıştırma hatası: ${e.message}")
            return null
        } finally {
            Context.exit()
        }
    }

    private suspend fun parseSubtitles(rawHtml: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(rawHtml) ?: return
        val tracksStr = tracksMatch.groupValues[1]
        Regex(""""file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(tracksStr).toList()
            .forEachIndexed { i, match ->
                val subUrl = match.groupValues[1].replace("\\/", "/")
                val subLabel = match.groupValues[2]
                val lang = when {
                    subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEachIndexed
                }
                Log.d(name, "Altyazı #$i - lang: '$lang'")
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }
    }
}
