package com.playtorrio.tv.ui.screens.reader

/**
 * Holds the chapter context for the active reader session. Avoids passing
 * giant nav-arg encoded JSON between screens. Set by the details screen
 * before navigating to "reader", consumed by [ReaderScreen].
 */
object ReaderSession {
    enum class Source { MANGA, COMIC }

    data class ChapterRef(
        val title: String,
        /** For manga: the chapter id. For comics: the chapter URL. */
        val key: String,
    )

    @Volatile var source: Source = Source.COMIC
    @Volatile var workKey: String = ""
    @Volatile var workTitle: String = ""
    @Volatile var workCoverUrl: String = ""
    @Volatile var chapters: List<ChapterRef> = emptyList()
    @Volatile var startIndex: Int = 0
    /** Page within startIndex chapter to seek to on first open (Continue Reading). Reset to 0 after consumed. */
    @Volatile var startPageIndex: Int = 0
    /** For comics: "" or "rcoru". Used by Continue Reading to re-open the right detail screen. */
    @Volatile var comicSourceTag: String = ""
    @Volatile var comicSummary: String = ""

    /** Index step that moves reading FORWARD (chronologically next chapter).
     *  Sources usually list chapters newest-first, so +1 in list order would go
     *  BACKWARD — inferred from the chapter numbers in the first/last titles. */
    @Volatile var forwardStep: Int = 1

    fun set(
        source: Source,
        workKey: String,
        workTitle: String,
        workCoverUrl: String,
        chapters: List<ChapterRef>,
        startIndex: Int,
        comicSourceTag: String = "",
        comicSummary: String = "",
        startPageIndex: Int = 0,
    ) {
        this.source = source
        this.workKey = workKey
        this.workTitle = workTitle
        this.workCoverUrl = workCoverUrl
        this.chapters = chapters
        this.startIndex = startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        this.startPageIndex = startPageIndex.coerceAtLeast(0)
        this.comicSourceTag = comicSourceTag
        this.comicSummary = comicSummary
        this.forwardStep = run {
            val nums = Regex("""\d+(?:\.\d+)?""")
            val first = chapters.firstOrNull()?.title?.let { nums.find(it)?.value?.toDoubleOrNull() }
            val last = chapters.lastOrNull()?.title?.let { nums.find(it)?.value?.toDoubleOrNull() }
            if (first != null && last != null && first > last) -1 else 1
        }
    }
}
