package com.playtorrio.tv.data.anime.extractors

import android.util.Log
import com.playtorrio.tv.data.anime.AnimeEmbed
import com.playtorrio.tv.data.anime.AnimeStreamResult
import com.playtorrio.tv.data.anime.AnimeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AllAnimeExtractor {
    private const val TAG = "AllAnimeExtractor"

    private const val API = "https://api.allanime.day/api"
    private const val REFR = "https://allmanga.to"
    private const val YTCHAN = "https://youtu-chan.com"
    private const val CLOCK_HOST = "https://allanime.day"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
    private const val EPISODE_QUERY_HASH = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"
    private const val SEARCH_GQL = "query(\$search: SearchInput \$limit: Int \$page: Int \$translationType: VaildTranslationTypeEnumType \$countryOrigin: VaildCountryOriginEnumType) { shows(search: \$search limit: \$limit page: \$page translationType: \$translationType countryOrigin: \$countryOrigin) { edges { _id name englishName availableEpisodes __typename } } }"

    val KNOWN_PROVIDERS = listOf(
        "Default",
        "S-mp4",
        "Yt-mp4",
        "Luf-Mp4",
        "Uv-mp4"
    )

    private val aesKey: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest("Xot36i3lK3:v1".toByteArray(Charsets.UTF_8))
    }

    suspend fun extract(embed: AnimeEmbed): AnimeStreamResult? {
        val m = Regex("^allanime://search/(\\d+)/([^/]+)/(.+)\\?t=(.+)$").find(embed.url) ?: return null
        val ep = m.groupValues[1].toInt()
        val cat = m.groupValues[2]
        val prov = m.groupValues[3]
        val titles = m.groupValues[4].split(",")
            .mapNotNull { java.net.URLDecoder.decode(it, "UTF-8").ifBlank { null } }

        return extractWithProvider(titles, ep, cat, prov)
    }

    private suspend fun extractWithProvider(
        titleCandidates: List<String>,
        episodeNumber: Int,
        category: String,
        provider: String
    ): AnimeStreamResult? = withContext(Dispatchers.IO) {
        try {
            val showId = resolveShowId(titleCandidates, category) ?: return@withContext null
            val sources = episodeSources(showId, episodeNumber, category)
            if (sources.isEmpty()) return@withContext null

            val wanted = provider.lowercase()
            val matches = sources.filter {
                (it.optString("sourceName", "").lowercase() == wanted)
            }
            if (matches.isEmpty()) return@withContext null

            for (src in matches) {
                val raw = src.optString("sourceUrl", "")
                if (raw.isBlank() || !raw.startsWith("--")) continue

                val decoded = decodeXorPath(raw) ?: continue
                val result = resolveDecodedPath(decoded, provider)
                if (result != null) {
                    return@withContext result
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "extractWithProvider $provider failed", e)
            null
        }
    }

    private suspend fun resolveShowId(titles: List<String>, cat: String): String? {
        for (raw in titles) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val id = searchOne(t, cat)
            if (id != null) return id
        }
        return null
    }

    private suspend fun searchOne(query: String, cat: String): String? {
        try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("allowAdult", false)
                    put("allowUnknown", false)
                    put("query", query)
                })
                put("limit", 40)
                put("page", 1)
                put("translationType", if (cat == "dub") "dub" else "sub")
                put("countryOrigin", "ALL")
            }
            val bodyObj = JSONObject().apply {
                put("variables", variables)
                put("query", SEARCH_GQL)
            }
            val body = bodyObj.toString()
            val resp = postObj(API, body, REFR) ?: return null

            val edges = resp.optJSONObject("data")?.optJSONObject("shows")?.optJSONArray("edges") ?: JSONArray()
            val qLower = query.lowercase()
            var best: JSONObject? = null

            for (i in 0 until edges.length()) {
                val e = edges.optJSONObject(i) ?: continue
                val name = e.optString("name", "").lowercase()
                val eng = e.optString("englishName", "").lowercase()
                if (name == qLower || eng == qLower) {
                    best = e
                    break
                }
            }
            if (best == null && edges.length() > 0) {
                best = edges.optJSONObject(0)
            }
            return best?.optString("_id")
        } catch (e: Exception) {
            Log.e(TAG, "searchOne $query failed", e)
            return null
        }
    }

    private suspend fun episodeSources(showId: String, episode: Int, cat: String): List<JSONObject> {
        try {
            val vars = JSONObject().apply {
                put("showId", showId)
                put("translationType", if (cat == "dub") "dub" else "sub")
                put("episodeString", episode.toString())
            }.toString()

            val ext = JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", EPISODE_QUERY_HASH)
                })
            }.toString()

            val urlStr = "$API?variables=${URLEncoder.encode(vars, "UTF-8")}&extensions=${URLEncoder.encode(ext, "UTF-8")}"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", YTCHAN)
                setRequestProperty("Origin", YTCHAN)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                connectTimeout = 15000
                readTimeout = 15000
            }

            if (conn.responseCode != 200) return emptyList()
            val respBody = InputStreamReader(conn.inputStream).readText()
            val json = JSONObject(respBody)

            val dataObj = json.optJSONObject("data") ?: return emptyList()
            val blob = dataObj.optString("tobeparsed", "")
            
            var episodeData: JSONObject? = null
            if (blob.isNotEmpty() && blob != "null") {
                val plain = decryptTobeparsed(blob)
                if (plain != null) {
                    val decoded = JSONObject(plain)
                    episodeData = decoded.optJSONObject("episode")
                }
            } else {
                episodeData = dataObj.optJSONObject("episode")
            }

            if (episodeData == null) return emptyList()
            val sourceUrls = episodeData.optJSONArray("sourceUrls") ?: return emptyList()

            val list = mutableListOf<JSONObject>()
            for (i in 0 until sourceUrls.length()) {
                val obj = sourceUrls.optJSONObject(i)
                if (obj != null) list.add(obj)
            }
            return list
        } catch (e: Exception) {
            Log.e(TAG, "episodeSources failed", e)
            return emptyList()
        }
    }

    private fun decodeXorPath(raw: String): String? {
        var s = raw
        if (s.startsWith("--")) s = s.substring(2)
        if (s.length < 2 || s.length % 2 != 0) return null
        val sb = java.lang.StringBuilder()
        for (i in 0 until s.length - 1 step 2) {
            val hex = s.substring(i, i + 2)
            val byte = hex.toIntOrNull(16) ?: return null
            sb.append((byte xor 0x38).toChar())
        }
        return sb.toString()
    }

    private suspend fun resolveDecodedPath(path: String, provider: String): AnimeStreamResult? {
        var p = path
        if (p.contains("/clock?") && !p.contains("/clock.json?")) {
            p = p.replaceFirst("/clock?", "/clock.json?")
        }
        val urlStr = if (p.startsWith("http")) p else "$CLOCK_HOST$p"

        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "$REFR/")
                setRequestProperty("Origin", REFR)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode != 200) return null
            val body = InputStreamReader(conn.inputStream).readText()
            val json = JSONObject(body)

            val links = json.optJSONArray("links") ?: return null
            if (links.length() == 0) return null

            var hls: JSONObject? = null
            var mp4: JSONObject? = null

            for (i in 0 until links.length()) {
                val l = links.optJSONObject(i) ?: continue
                val link = l.optString("link", "")
                if (link.isEmpty()) continue
                val isHls = l.optBoolean("hls", false) || link.lowercase().contains(".m3u8")
                val isMp4 = l.optBoolean("mp4", false) || link.lowercase().contains(".mp4")
                if (isHls && hls == null) hls = l
                if (isMp4 && mp4 == null) mp4 = l
            }
            val pick = hls ?: mp4 ?: links.optJSONObject(0) ?: return null
            val streamUrl = pick.optString("link", "")
            if (streamUrl.isEmpty()) return null

            val tracks = mutableListOf<AnimeTrack>()
            val subs = pick.optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val t = subs.optJSONObject(i) ?: continue
                    val f = t.optString("src").ifEmpty { t.optString("file") }
                    if (f.isEmpty()) continue
                    tracks.add(
                        AnimeTrack(
                            url = f,
                            label = t.optString("label").ifEmpty { t.optString("lang", "Unknown") },
                            isDefault = t.optString("default") == "default" || t.optBoolean("default", false)
                        )
                    )
                }
            }

            val referer = pick.optString("Referer", "$REFR/").ifEmpty { "$REFR/" }
            val origin = try {
                val rUrl = URL(referer)
                "${rUrl.protocol}://${rUrl.host}"
            } catch (e: Exception) {
                REFR
            }

            return AnimeStreamResult(
                url = streamUrl,
                referer = referer,
                origin = origin,
                tracks = tracks
            )
        } catch (e: Exception) {
            Log.e(TAG, "resolveDecodedPath failed", e)
            return null
        }
    }

    private fun decryptTobeparsed(blob: String): String? {
        try {
            val raw = android.util.Base64.decode(blob, android.util.Base64.DEFAULT)
            if (raw.size < 13 + 16) return null

            val iv = ByteArray(16)
            for (i in 0 until 12) {
                iv[i] = raw[1 + i]
            }
            iv[12] = 0
            iv[13] = 0
            iv[14] = 0
            iv[15] = 2

            val ctLen = raw.size - 13 - 16
            if (ctLen <= 0) return null
            val ct = ByteArray(ctLen)
            System.arraycopy(raw, 13, ct, 0, ctLen)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val plain = cipher.doFinal(ct)
            return String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decryptTobeparsed failed", e)
            return null
        }
    }

    private suspend fun postObj(urlStr: String, body: String, refr: String): JSONObject? {
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", refr)
                setRequestProperty("Origin", refr)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            conn.outputStream.close()

            if (conn.responseCode != 200) return null
            val respStr = InputStreamReader(conn.inputStream).readText()
            return JSONObject(respStr)
        } catch (e: Exception) {
            Log.e(TAG, "postObj failed", e)
            return null
        }
    }
}
