package com.playtorrio.tv.data.iptv

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Multi-source Xtream-Codes scraper.
 *
 * Sources (in priority order):
 *  1. Xtreamity R2  — pre-validated portal database (~8 000 portals) from Cloudflare R2
 *  2. GitHub XML2 dumps — fast plain-text URL lists, very reliable
 *  3. Reddit r/IPTV_ZONENEW — Googlebot UA first, then 3 CORS proxies as fallback
 */

/** A page of scraped portals plus a pagination cursor for the next call. */
data class ScrapePage(
    val portals: List<IptvPortal>,
    val nextAfter: String?,
) {
    val hasMore: Boolean get() = !nextAfter.isNullOrEmpty()
}

object IptvScraper {
    private const val TAG = "IptvScraper"

    // ── Reddit ────────────────────────────────────────────────────────────
    private const val CATALOG_SUB = "IPTV_ZONENEW"
    private const val REDDIT_DIRECT_HOST = "https://www.reddit.com"
    // Googlebot UA is whitelisted by Reddit's anti-bot rules
    private const val GOOGLEBOT_UA = "Googlebot/2.1 (+http://www.google.com/bot.html)"

    // Reddit OAuth2 "installed_client" anonymous auth — far more reliable than
    // Googlebot/CORS-proxy hits. Uses public open-source app client IDs to get
    // an anonymous bearer token (no account needed). Ported from the mobile app.
    private const val OAUTH_UA = "PlayTorrio/1.0 (by /u/PlayTorrioApp)"
    private val OAUTH_CLIENT_IDS = listOf(
        "ohXpoqrZYub1kg", // Slide for Reddit
        "NOe2iKrPPzwscA", // RedReader
        "JrPdG8Z6dkWNxA", // Stealth
    )
    @Volatile private var oauthToken: String? = null
    @Volatile private var oauthTokenExpiryMs: Long = 0L
    @Volatile private var oauthClientIdx = 0
    // CORS proxies ordered by observed reliability. {URL} = URL-encoded target.
    private val FETCH_PROXIES = listOf(
        "https://corsproxy.io/?{URL}",
        "https://api.codetabs.com/v1/proxy?quest={URL}",
        "https://api.allorigins.win/raw?url={URL}",
    )

    // ── Xtreamity Cloudflare R2 database ────────────────────────────────────
    // Pre-validated ~8 000 portal CSV signed with AWS SigV4 (R2 is S3-compatible).
    // CSV schema: url, username, password, "MM/DD/YYYY", " HH:MM", region
    private const val XTREAMITY_HOST = "145ef3f7a9832804bef0e31548db8a83.r2.cloudflarestorage.com"
    private const val XTREAMITY_BUCKET = "xtreamity"
    private const val XTREAMITY_OBJECT = "xtreamity-plus-db.csv.gz"
    private const val XTREAMITY_ACCESS_KEY = "4b36152b6b64b8a9f4d7010b84f535fc"
    private const val XTREAMITY_SECRET_KEY =
        "7ad1ed517b6baa6af2fa00d50a1a18b0ce416bb0b6fb14f4c122a2960f1ab9bc"
    private const val XTREAMITY_TTL_MS = 6 * 3600 * 1000L
    @Volatile private var xtreamityPortals: List<IptvPortal>? = null
    @Volatile private var xtreamityFetchedAt: Long = 0L

    // ── GitHub XML2 dumps ─────────────────────────────────────────────────
    private const val XML2_BASE =
        "https://raw.githubusercontent.com/akeotaseo/world_repo/main/Updater_Matrix/XML2/"
    private const val XML2_LIST_API =
        "https://api.github.com/repos/akeotaseo/world_repo/contents/Updater_Matrix/XML2?ref=main"
    private val XML2_FALLBACK_FILES = listOf(
        "25.txt", "71.txt", "ABN.txt", "DOV.txt",
        "%5BK_B_W_%20Client%5D.txt", "br.txt",
        "channels_fulltime%20(OR).txt", "channels_fulltime.txt",
        "kgen%20(4).txt", "kgen.txt", "rg.txt", "x.txt",
        "%7BAllTelegram%7D2.txt",
    )
    @Volatile private var xml2Files: List<String>? = null
    @Volatile private var xml2FetchedAt: Long = 0L
    private const val XML2_TTL_MS = 6 * 3600 * 1000L

    // ── Paste sites ───────────────────────────────────────────────────────
    private val PASTE_DOMAINS = listOf(
        "paste.sh", "pastebin.com", "justpaste.it", "controlc.com",
        "pastes.dev", "text.is", "rentry.co",
    )

    // ── HTTP ──────────────────────────────────────────────────────────────
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private const val UA =
        "Mozilla/5.0 (Linux; Android 11; PlayTorrio) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    // ── Regex ─────────────────────────────────────────────────────────────
    private val B64_REGEX = Regex("aHR0c[a-zA-Z0-9+/=]{10,}")
    private val RAW_PASTE_REGEX = Regex(
        "https?://(?:paste\\.sh|pastebin\\.com|justpaste\\.it|controlc\\.com|" +
            "pastes\\.dev|text\\.is|rentry\\.co)/[a-zA-Z0-9#_=-]+",
        RegexOption.IGNORE_CASE,
    )
    private val URL_PARAM_REGEX = Regex(
        "(https?://[^?\\s\"'<]+)\\?(?:[^\\s\"'<]*?&)?" +
            "(?:username|user)=([^&\\s\"'<]+)\\s*&(?:password|pass)=([^&\\s\"'<]+)",
        RegexOption.IGNORE_CASE,
    )
    private val LABEL_REGEX = Regex(
        "(?:Portal|Host(?:\\s*URL)?|Panel|Real|URL|🔗|🌍|🌐)\\W*?" +
            "(https?://[^<\\s\"']+)" +
            "[\\s\\S]{1,500}?(?:Username|Usu[áa]rio|Usuario|User|👤)\\W*?([^\\s|<\"'\\n]+)" +
            "[\\s\\S]{1,200}?(?:Password|Senha|Contrase[ñn]a|Pass|🔑)\\W*?([^\\s|<\"'\\n]+)",
        RegexOption.IGNORE_CASE,
    )
    private val JUNK_TOKENS = listOf(
        "type=m3u", "output=ts", "password=", "username=", "password", "username",
    )

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /** Returns up to [maxResults] portals. Tries XML2 first, then Reddit. */
    suspend fun scrapeReddit(maxResults: Int = 50): List<IptvPortal> =
        scrapeRedditPage(maxResults = maxResults, after = null).portals

    /**
     * Paginated scrape. Cursor encoding:
     *  - `null`               → start with Xtreamity (offset 0)
     *  - `xtreamity:<offset>` → next chunk of Xtreamity portal database
     *  - `xml2:N`             → fetch XML2 file at index N
     *  - `reddit:`            → first Reddit page
     *  - `reddit:<tok>`       → Reddit page with after=<tok>
     */
    suspend fun scrapeRedditPage(maxResults: Int = 50, after: String? = null): ScrapePage {
        return when {
            // ── Xtreamity ──
            after == null || after.startsWith("xtreamity:") -> {
                val portals = getXtreamityPortals()
                val offset = if (after == null) 0
                             else after.removePrefix("xtreamity:").toIntOrNull() ?: 0
                if (portals.isNotEmpty() && offset < portals.size) {
                    val end = minOf(offset + maxResults, portals.size)
                    val next = if (end < portals.size) "xtreamity:$end" else "xml2:0"
                    Log.d(TAG, "[Xtreamity] page offset=$offset (${end - offset} portals, next=$next)")
                    ScrapePage(portals.subList(offset, end), next)
                } else {
                    // Xtreamity empty/exhausted → XML2
                    val files = getXml2Files()
                    if (files.isNotEmpty()) scrapeXml2File(0, files, maxResults)
                    else scrapeRedditCatalog(maxResults = maxResults, after = null)
                }
            }
            // ── XML2 ──
            after.startsWith("xml2:") -> {
                val files = getXml2Files()
                val idx = after.removePrefix("xml2:").toIntOrNull() ?: 0
                if (idx < files.size) scrapeXml2File(idx, files, maxResults)
                else scrapeRedditCatalog(maxResults = maxResults, after = null)
            }
            // ── Reddit ──
            after.startsWith("reddit:") -> {
                val token = after.removePrefix("reddit:").takeIf { it.isNotEmpty() }
                scrapeRedditCatalog(maxResults = maxResults, after = token)
            }
            else -> scrapeRedditCatalog(maxResults = maxResults, after = after)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Xtreamity R2 source
    // ═══════════════════════════════════════════════════════════════════════

    private fun getXtreamityPortals(): List<IptvPortal> {
        val now = System.currentTimeMillis()
        val cached = xtreamityPortals
        if (cached != null && now - xtreamityFetchedAt < XTREAMITY_TTL_MS) return cached

        return try {
            val bytes = fetchXtreamityObject()
            if (bytes != null) {
                val csv = GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readText()
                val portals = mutableListOf<IptvPortal>()
                for (rawLine in csv.lines()) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    val cols = line.split(",")
                    if (cols.size < 3) continue
                    val url = cols[0].trim()
                    val user = cols[1].trim()
                    val pass = cols[2].trim()
                    if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) continue
                    if (!url.lowercase().startsWith("http")) continue
                    portals += IptvPortal(url, user, pass, "Xtreamity")
                }
                portals.shuffle()
                Log.d(TAG, "[Xtreamity] loaded ${portals.size} portals from R2")
                xtreamityPortals = portals
                xtreamityFetchedAt = now
                portals
            } else {
                xtreamityPortals = emptyList()
                xtreamityFetchedAt = now
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Xtreamity] fetch/parse failed: ${e.message}")
            xtreamityPortals = emptyList()
            xtreamityFetchedAt = now
            emptyList()
        }
    }

    private fun fetchXtreamityObject(): ByteArray? {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateSdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = Date()
        val amzDate = sdf.format(now)
        val dateStamp = dateSdf.format(now)
        val region = "auto"
        val service = "s3"
        val path = "/$XTREAMITY_BUCKET/$XTREAMITY_OBJECT"
        val payloadHash = "UNSIGNED-PAYLOAD"

        val canonicalHeaders = "host:$XTREAMITY_HOST\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "GET\n$path\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        val kDate = hmacSha256("AWS4$XTREAMITY_SECRET_KEY".toByteArray(), dateStamp.toByteArray())
        val kRegion = hmacSha256(kDate, region.toByteArray())
        val kService = hmacSha256(kRegion, service.toByteArray())
        val kSigning = hmacSha256(kService, "aws4_request".toByteArray())
        val signature = hex(hmacSha256(kSigning, stringToSign.toByteArray()))

        val auth = "AWS4-HMAC-SHA256 Credential=$XTREAMITY_ACCESS_KEY/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return try {
            val req = Request.Builder()
                .url("https://$XTREAMITY_HOST$path")
                .header("Authorization", auth)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("User-Agent", "aws-sdk-android/2.x kotlin")
                .build()
            client.newCall(req).execute().use { resp ->
                Log.d(TAG, "[Xtreamity] R2 HTTP ${resp.code}")
                if (resp.code == 200) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Xtreamity] R2 request failed: ${e.message}")
            null
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    // ═══════════════════════════════════════════════════════════════════════
    // XML2 GitHub dump source
    // ═══════════════════════════════════════════════════════════════════════

    private fun getXml2Files(): List<String> {
        val now = System.currentTimeMillis()
        val cached = xml2Files
        if (cached != null && now - xml2FetchedAt < XML2_TTL_MS) return cached

        return try {
            val resp = httpGet(XML2_LIST_API, UA, "application/vnd.github+json")
            val arr = JSONArray(resp)
            val entries = mutableListOf<Pair<String, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") != "file") continue
                val name = o.optString("name")
                if (!name.endsWith(".txt", ignoreCase = true)) continue
                val size = o.optInt("size", Int.MAX_VALUE)
                entries += java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") to size
            }
            entries.sortBy { it.second }
            val files = entries.map { it.first }
            Log.d(TAG, "[XML2] listed ${files.size} files from GitHub")
            xml2Files = files
            xml2FetchedAt = now
            files
        } catch (e: Exception) {
            Log.w(TAG, "[XML2] list failed: ${e.message} — using fallback")
            xml2Files = XML2_FALLBACK_FILES
            xml2FetchedAt = now
            XML2_FALLBACK_FILES
        }
    }

    private suspend fun scrapeXml2File(
        idx: Int,
        files: List<String>,
        maxResults: Int,
    ): ScrapePage {
        val encoded = files[idx]
        val url = "$XML2_BASE$encoded"
        val pretty = java.net.URLDecoder.decode(encoded, "UTF-8").removeSuffix(".txt")
        Log.d(TAG, "[XML2] [$idx/${files.size}] fetching $pretty")

        val body = try {
            val b = httpGet(url, UA, "text/plain,*/*")
            b.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "[XML2] fetch failed: ${e.message}")
            null
        }

        val next = if (idx + 1 < files.size) "xml2:${idx + 1}" else "reddit:"
        if (body == null) return ScrapePage(emptyList(), next)

        val portals = extractPortals(body, "XML2/$pretty")
        Log.d(TAG, "[XML2] $pretty → ${portals.size} portals")
        return ScrapePage(portals.take(maxResults), next)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reddit catalog source
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun scrapeRedditCatalog(
        maxResults: Int = 50,
        after: String? = null,
    ): ScrapePage {
        val out = LinkedHashMap<String, IptvPortal>()
        val catalogJson = fetchRedditJson(after) ?: run {
            Log.e(TAG, "[Reddit] all fetch strategies failed")
            return ScrapePage(emptyList(), null)
        }

        val data = try {
            JSONObject(catalogJson).getJSONObject("data")
        } catch (e: Exception) {
            Log.e(TAG, "[Reddit] JSON parse failed: ${e.message}")
            return ScrapePage(emptyList(), null)
        }

        val posts = data.optJSONArray("children") ?: return ScrapePage(emptyList(), null)
        val nextAfterRaw = data.optString("after").takeIf { it.isNotEmpty() && it != "null" }
        val nextAfter = if (nextAfterRaw != null) "reddit:$nextAfterRaw" else null
        Log.d(TAG, "[Reddit] ${posts.length()} posts (after=$after, next=$nextAfterRaw)")

        for (i in 0 until posts.length()) {
            if (out.size >= maxResults) break
            val pdata = posts.getJSONObject(i).optJSONObject("data") ?: continue
            val title = pdata.optString("title")
            val body = ("$title ${pdata.optString("selftext")}").trim()

            // 1. Direct extraction
            extractPortals(body, "Reddit").forEach { addPortal(out, it, maxResults) }
            if (out.size >= maxResults) break

            // 2. Base64 deep links
            val deepLinks = mutableListOf<String>()
            B64_REGEX.findAll(body).forEach { m ->
                runCatching {
                    val decoded = String(Base64.decode(m.value, Base64.DEFAULT))
                    if (decoded.startsWith("http") && isPasteSite(decoded)) {
                        deepLinks += decoded
                    } else if (!decoded.startsWith("http") && decoded.contains(":")) {
                        extractPortals(decoded, "Reddit (decoded)")
                            .forEach { addPortal(out, it, maxResults) }
                    }
                }
            }

            // 3. Raw paste links
            RAW_PASTE_REGEX.findAll(body).forEach { deepLinks += it.value }

            // 4. Fetch deep links
            for (dl in deepLinks.distinct().take(4)) {
                if (out.size >= maxResults) break
                val text = runCatching { fetchPaste(dl) }.getOrNull()
                if (!text.isNullOrBlank()) {
                    extractPortals(text, "Reddit (deep)").forEach { addPortal(out, it, maxResults) }
                }
            }
        }

        Log.d(TAG, "[Reddit] scraped ${out.size} portals")
        return ScrapePage(out.values.toList(), nextAfter)
    }

    /**
     * Anonymous Reddit OAuth2 "installed_client" bearer token, cached and
     * auto-refreshed, with client-ID rotation on failure. No account needed.
     */
    private fun getOAuthToken(): String? {
        val now = System.currentTimeMillis()
        oauthToken?.let { if (now < oauthTokenExpiryMs) return it }
        for (i in OAUTH_CLIENT_IDS.indices) {
            val idx = (oauthClientIdx + i) % OAUTH_CLIENT_IDS.size
            val clientId = OAUTH_CLIENT_IDS[idx]
            try {
                val basic = Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
                val form = okhttp3.FormBody.Builder()
                    .add("grant_type", "https://oauth.reddit.com/grants/installed_client")
                    .add("device_id", "DO_NOT_TRACK_THIS_DEVICE")
                    .build()
                val req = Request.Builder()
                    .url("https://www.reddit.com/api/v1/access_token")
                    .header("User-Agent", OAUTH_UA)
                    .header("Authorization", "Basic $basic")
                    .post(form)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val token = json.optString("access_token").takeIf { it.isNotEmpty() }
                        if (token != null) {
                            oauthToken = token
                            oauthTokenExpiryMs = now + (json.optInt("expires_in", 3600) - 60) * 1000L
                            oauthClientIdx = idx
                            Log.d(TAG, "[Reddit] OAuth token obtained (client #$idx)")
                            return token
                        }
                    }
                    Log.d(TAG, "[Reddit] OAuth auth failed (client #$idx): ${resp.code}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "[Reddit] OAuth error (client #$idx): ${e.message}")
            }
        }
        oauthClientIdx = (oauthClientIdx + 1) % OAUTH_CLIENT_IDS.size
        oauthToken = null; oauthTokenExpiryMs = 0L
        return null
    }

    /**
     * Fetches Reddit's JSON listing.
     * Strategy:
     *  0. OAuth2 installed_client bearer (most reliable)
     *  1. Direct hit with Googlebot UA (Reddit whitelists search crawlers)
     *  2. Each CORS proxy in turn
     * Accepts only responses that start with '{' or '['.
     */
    private fun fetchRedditJson(after: String?): String? {
        fun looksLikeJson(body: String) = body.trimStart().let {
            it.startsWith('{') || it.startsWith('[')
        }

        // 0. OAuth2 installed_client — far more reliable than proxies.
        val token = getOAuthToken()
        if (token != null) {
            val oauthBase = "https://oauth.reddit.com/r/$CATALOG_SUB/new?limit=100&sort=new&raw_json=1"
            val oauthTarget = if (after.isNullOrEmpty()) oauthBase else "$oauthBase&after=$after"
            Log.d(TAG, "[Reddit] OAuth GET")
            try {
                val req = Request.Builder()
                    .url(oauthTarget)
                    .header("User-Agent", OAUTH_UA)
                    .header("Authorization", "Bearer $token")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 200 && looksLikeJson(body)) {
                        Log.d(TAG, "[Reddit] OAuth hit succeeded")
                        return body
                    }
                    if (resp.code == 401 || resp.code == 403) { oauthToken = null; oauthTokenExpiryMs = 0L }
                }
            } catch (e: Exception) {
                Log.d(TAG, "[Reddit] OAuth GET failed: ${e.message}")
            }
        }

        val base = "$REDDIT_DIRECT_HOST/r/$CATALOG_SUB/new/.json?limit=100&sort=new&raw_json=1"
        val target = if (after.isNullOrEmpty()) base else "$base&after=$after"

        // 1. Direct Googlebot hit
        Log.d(TAG, "[Reddit] direct GET (Googlebot UA)")
        try {
            val body = httpGet(target, GOOGLEBOT_UA, "application/json")
            if (looksLikeJson(body)) {
                Log.d(TAG, "[Reddit] direct hit succeeded")
                return body
            }
            Log.d(TAG, "[Reddit] direct returned non-JSON (len=${body.length})")
        } catch (e: Exception) {
            Log.d(TAG, "[Reddit] direct failed: ${e.message}")
        }

        // 2. CORS proxies
        val encoded = java.net.URLEncoder.encode(target, "UTF-8")
        for (tmpl in FETCH_PROXIES) {
            val proxyUrl = tmpl.replace("{URL}", encoded)
            Log.d(TAG, "[Reddit] proxy ${proxyUrl.take(50)}…")
            try {
                val body = httpGet(proxyUrl, UA, "application/json, text/plain, */*")
                if (looksLikeJson(body)) {
                    Log.d(TAG, "[Reddit] proxy succeeded")
                    return body
                }
                Log.d(TAG, "[Reddit] proxy non-JSON (len=${body.length})")
            } catch (e: Exception) {
                Log.d(TAG, "[Reddit] proxy failed: ${e.message}")
            }
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun addPortal(sink: LinkedHashMap<String, IptvPortal>, p: IptvPortal, max: Int) {
        if (sink.size >= max) return
        val key = "${p.url}|${p.username}|${p.password}".lowercase()
        if (key !in sink) sink[key] = p
    }

    private fun extractPortals(rawText: String, source: String): List<IptvPortal> {
        if (rawText.length < 15 || isJunkCode(rawText)) return emptyList()
        val cleaned = rawText
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace(Regex("<(?:p|br|div|li|h\\d)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")

        val acc = LinkedHashMap<String, IptvPortal>()
        URL_PARAM_REGEX.findAll(cleaned).forEach {
            finalize(acc, it.groupValues[1], it.groupValues[2], it.groupValues[3], source)
        }
        LABEL_REGEX.findAll(cleaned).forEach {
            finalize(acc, it.groupValues[1], it.groupValues[2], it.groupValues[3], source)
        }
        return acc.values.toList()
    }

    private fun isJunkCode(text: String): Boolean {
        val markers = listOf(
            "Array.isArray", "prototype.", "function(", "var ", "const ",
            "let ", "return!", "void ", ".message}", "window.", "document.",
        )
        return markers.count { text.contains(it) } >= 2
    }

    private fun finalize(
        acc: LinkedHashMap<String, IptvPortal>,
        rawUrl: String, rawUser: String, rawPass: String, source: String,
    ) {
        val url = cleanPortalUrl(rawUrl)
        val user = cleanCred(rawUser)
        val pass = cleanCred(rawPass)
        if (url.isEmpty() || user.length < 3 || pass.length < 3) return
        if (user.contains("http") || pass.contains("http")) return
        if (JUNK_TOKENS.any { user.contains(it, true) || pass.contains(it, true) }) return
        val key = "$url|$user|$pass".lowercase()
        if (key !in acc) acc[key] = IptvPortal(url, user, pass, source)
    }

    private fun cleanPortalUrl(raw: String): String {
        var clean = raw.replace(Regex("\\s+"), "").substringBefore('?').trim()
        if (clean.contains('@')) clean = "http://" + clean.substringAfterLast('@')
        clean = clean.replace(
            Regex(
                "/(?:get|live|portal|c|index|playlist|player_api|xmltv|index\\.php|portal\\.php)\\.php$",
                RegexOption.IGNORE_CASE,
            ),
            "",
        ).trimEnd('/')
        if (!clean.startsWith("http")) clean = "http://$clean"
        return clean
    }

    private fun cleanCred(raw: String): String =
        raw.trimStart('=').split(' ', '\n', '&', '?').firstOrNull().orEmpty().trim()

    private fun isPasteSite(url: String): Boolean = PASTE_DOMAINS.any { url.contains(it) }

    // ── Paste fetch helpers ───────────────────────────────────────────────

    private suspend fun fetchPaste(url: String): String {
        if (url.contains("paste.sh/") && url.contains('#')) {
            return PasteShDecryptor.decrypt(url)
        }
        if (url.contains("pastebin.com/") && !url.contains("/raw/")) {
            val id = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
            return httpGet("https://pastebin.com/raw/$id", UA, "text/plain,*/*")
        }
        if (url.contains("pastes.dev/")) {
            val id = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
            return httpGet("https://api.pastes.dev/$id", UA, "text/plain,*/*")
        }
        if (url.contains("rentry.co/") && !url.contains("/raw")) {
            val id = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
            return httpGet("https://rentry.co/$id/raw", UA, "text/plain,*/*")
        }
        return httpGet(url, UA, "text/html,application/json,*/*")
    }

    // ── Core HTTP ─────────────────────────────────────────────────────────

    private fun httpGet(url: String, userAgent: String, accept: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { resp ->
            Log.d(TAG, "HTTP ${resp.code} ← $url")
            return resp.body?.string().orEmpty()
        }
    }
}
