package com.playtorrio.tv.data.anime.extractors

import android.util.Base64
import android.util.Log
import com.playtorrio.tv.data.anime.AnimeEmbed
import com.playtorrio.tv.data.anime.AnimeStreamResult
import com.playtorrio.tv.data.anime.AnimeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object MiruroExtractor {
    private const val TAG = "MiruroExtractor"

    private const val BASE_URL = "https://www.miruro.tv"
    private const val PIPE_OBF_KEY_HEX = "71951034f8fbcf53d89db52ceb3dc22c"
    private const val PROTOCOL_VERSION = "0.2.0"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    val KNOWN_PROVIDERS = listOf(
        "zoro", "kiwi", "arc", "jet", "hop", "bee", "bun", "kuz", "telli"
    )

    private val obfKey: ByteArray by lazy {
        val len = PIPE_OBF_KEY_HEX.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(PIPE_OBF_KEY_HEX[i], 16) shl 4)
                    + Character.digit(PIPE_OBF_KEY_HEX[i + 1], 16)).toByte()
        }
        data
    }

    suspend fun extract(embed: AnimeEmbed): AnimeStreamResult? {
        val m = Regex("^miruro://anilist/(\\d+)/(\\d+)/([^/]+)/(.+)$").find(embed.url) ?: return null
        val anilistId = m.groupValues[1].toInt()
        val episode = m.groupValues[2].toInt()
        val cat = m.groupValues[3]
        val prov = m.groupValues[4]
        return extractWithProvider(anilistId, episode, cat, prov)
    }

    private suspend fun extractWithProvider(
        anilistId: Int,
        episodeNumber: Int,
        category: String,
        provider: String
    ): AnimeStreamResult? = withContext(Dispatchers.IO) {
        try {
            val epData = episodes(anilistId) ?: return@withContext null
            val providersMap = epData.optJSONObject("providers") ?: return@withContext null
            val prov = providersMap.optJSONObject(provider) ?: return@withContext null

            val eps = prov.optJSONObject("episodes") ?: return@withContext null
            val list = eps.optJSONArray(category) ?: return@withContext null
            if (list.length() == 0) return@withContext null

            var hit: JSONObject? = null
            for (i in 0 until list.length()) {
                val raw = list.optJSONObject(i) ?: continue
                val n = raw.optInt("number", -1)
                if (n == episodeNumber) {
                    hit = raw
                    break
                }
            }
            if (hit == null) return@withContext null

            val epId = hit.optString("id", "")
            if (epId.isEmpty()) return@withContext null

            val src = apiGet("sources", mapOf(
                "episodeId" to epId,
                "provider" to provider,
                "category" to category,
                "anilistId" to anilistId.toString()
            )) ?: return@withContext null

            val streams = src.optJSONArray("streams") ?: JSONArray()
            var hls: JSONObject? = null
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                val type = s.optString("type", "")
                if (type == "hls" || type.isEmpty()) {
                    hls = s
                    break
                }
            }
            if (hls == null) return@withContext null

            val url = hls.optString("url", "")
            if (url.isEmpty()) return@withContext null

            val referer = hls.optString("referer", "$BASE_URL/").ifEmpty { "$BASE_URL/" }
            val origin = try {
                val rUrl = URL(referer)
                "${rUrl.protocol}://${rUrl.host}"
            } catch (e: Exception) {
                BASE_URL
            }

            val tracks = mutableListOf<AnimeTrack>()
            val subs = src.optJSONArray("subtitles") ?: JSONArray()
            for (i in 0 until subs.length()) {
                val t = subs.optJSONObject(i) ?: continue
                val fileUrl = t.optString("file", "").ifEmpty { t.optString("url", "") }
                if (fileUrl.isEmpty()) continue
                tracks.add(AnimeTrack(
                    url = fileUrl,
                    label = t.optString("label", "Unknown"),
                    isDefault = t.optBoolean("default", false)
                ))
            }

            AnimeStreamResult(
                url = url,
                referer = referer,
                origin = origin,
                tracks = tracks
            )
        } catch (e: Exception) {
            Log.e(TAG, "extractWithProvider $provider failed", e)
            null
        }
    }

    private suspend fun episodes(anilistId: Int): JSONObject? {
        return apiGet("episodes", mapOf("anilistId" to anilistId.toString()))
    }

    private suspend fun apiGet(path: String, query: Map<String, String>): JSONObject? {
        try {
            val queryObj = JSONObject()
            for ((k, v) in query) {
                queryObj.put(k, v)
            }
            val payload = JSONObject().apply {
                put("path", path)
                put("method", "GET")
                put("query", queryObj)
                put("body", JSONObject.NULL)
                put("version", PROTOCOL_VERSION)
            }.toString()

            val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val urlStr = "$BASE_URL/api/secure/pipe?e=$encoded"

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "$BASE_URL/")
                setRequestProperty("Origin", BASE_URL)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                connectTimeout = 15000
                readTimeout = 15000
            }

            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.readBytes()
            val body = String(bytes, Charsets.UTF_8)
            val xObf = conn.getHeaderField("x-obfuscated")

            if (xObf.isNullOrEmpty()) {
                return JSONObject(body)
            }
            return JSONObject(deobfuscate(body, xObf))
        } catch (e: Exception) {
            Log.e(TAG, "apiGet $path failed", e)
            return null
        }
    }

    private fun deobfuscate(body: String, level: String): String {
        var b64 = body.replace('-', '+').replace('_', '/')
        val pad = b64.length % 4
        if (pad != 0) b64 += "=".repeat(4 - pad)
        var data = Base64.decode(b64, Base64.DEFAULT)

        if (level == "2") {
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor obfKey[i % obfKey.size].toInt()).toByte()
            }
            data = out
        }
        return String(decompress(data), Charsets.UTF_8)
    }

    private fun decompress(data: ByteArray): ByteArray {
        try {
            if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                return GZIPInputStream(ByteArrayInputStream(data)).readBytes()
            }
        } catch (_: Exception) {}
        try {
            return InflaterInputStream(ByteArrayInputStream(data)).readBytes()
        } catch (_: Exception) {}
        return data
    }
}
