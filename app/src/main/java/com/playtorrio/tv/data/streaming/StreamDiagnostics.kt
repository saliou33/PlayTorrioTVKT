package com.playtorrio.tv.data.streaming

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log of stream-source extraction attempts, surfaced as an HTML page
 * through the phone web server (SettingsConfigServer → /diag). Lets us see —
 * from a phone browser, no adb — exactly which sources were tried for a title
 * and why each failed. Keeps the last few "Play Now" sessions.
 */
object StreamDiagnostics {

    data class Attempt(
        val atMs: Long,
        val source: String,
        val outcome: String,   // "ok" | "no-stream" | "timeout" | "error"
        val detail: String,
        val durationMs: Long,
    )

    data class Session(
        val atMs: Long,
        val label: String,
        val attempts: MutableList<Attempt> = mutableListOf(),
    )

    private const val MAX_SESSIONS = 15
    private val sessions = ArrayDeque<Session>()   // newest first

    @Synchronized
    fun startSession(label: String) {
        sessions.addFirst(Session(System.currentTimeMillis(), label))
        while (sessions.size > MAX_SESSIONS) sessions.removeLast()
    }

    @Synchronized
    fun record(source: String, outcome: String, detail: String, durationMs: Long) {
        val s = sessions.firstOrNull() ?: Session(System.currentTimeMillis(), "(ad-hoc)").also {
            sessions.addFirst(it)
        }
        s.attempts.add(Attempt(System.currentTimeMillis(), source, outcome, detail.take(160), durationMs))
    }

    @Synchronized
    fun clear() = sessions.clear()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun renderHtml(): String {
        val rows = StringBuilder()
        if (sessions.isEmpty()) {
            rows.append("<p class='empty'>No playback attempts yet. Press “Play Now” on the TV, then refresh.</p>")
        }
        for (s in sessions) {
            rows.append("<div class='session'>")
            rows.append("<div class='shead'><span class='title'>${esc(s.label)}</span>" +
                "<span class='time'>${clock.format(Date(s.atMs))}</span></div>")
            if (s.attempts.isEmpty()) {
                rows.append("<div class='att pending'>… extracting …</div>")
            }
            for (a in s.attempts) {
                val cls = when (a.outcome) {
                    "ok" -> "ok"; "timeout" -> "warn"; "error" -> "err"; else -> "miss"
                }
                val badge = when (a.outcome) {
                    "ok" -> "STREAM"; "no-stream" -> "none"; "timeout" -> "timeout"; else -> "error"
                }
                rows.append("<div class='att $cls'>")
                rows.append("<span class='src'>${esc(a.source)}</span>")
                rows.append("<span class='badge'>$badge</span>")
                rows.append("<span class='dur'>${a.durationMs} ms</span>")
                if (a.detail.isNotBlank()) rows.append("<div class='detail'>${esc(a.detail)}</div>")
                rows.append("</div>")
            }
            rows.append("</div>")
        }

        return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="3">
<title>PlayTorrio TV — Source Diagnostics</title>
<style>
  :root{color-scheme:dark}
  body{margin:0;background:#0b0b0f;color:#e5e7eb;font:15px/1.4 -apple-system,Segoe UI,Roboto,sans-serif;padding:16px}
  h1{font-size:18px;margin:0 0 4px}
  .sub{color:#9ca3af;font-size:12px;margin:0 0 16px}
  .session{background:#15151c;border:1px solid #23232e;border-radius:12px;padding:12px;margin-bottom:14px}
  .shead{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:8px}
  .title{font-weight:700}
  .time{color:#6b7280;font-size:12px}
  .att{display:flex;align-items:center;gap:10px;padding:7px 10px;border-radius:8px;margin:4px 0;background:#1c1c26;flex-wrap:wrap}
  .src{font-weight:600;min-width:90px}
  .badge{font-size:12px;padding:2px 8px;border-radius:999px;font-weight:700}
  .dur{margin-left:auto;color:#9ca3af;font-size:12px}
  .detail{flex-basis:100%;color:#9ca3af;font-size:11px;word-break:break-all}
  .ok .badge{background:#064e3b;color:#6ee7b7}
  .miss .badge{background:#3f1d1d;color:#fca5a5}
  .warn .badge{background:#422006;color:#fdba74}
  .err .badge{background:#3f1d1d;color:#fca5a5}
  .pending{color:#9ca3af;font-style:italic}
  .empty{color:#9ca3af}
</style></head><body>
<h1>Source Diagnostics</h1>
<p class="sub">Auto-refreshes every 3s • newest first • order shown = try order</p>
$rows
</body></html>
""".trimIndent()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
