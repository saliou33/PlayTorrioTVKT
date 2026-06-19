import sys

with open('c:/Users/Ayman/Desktop/PlayTorrioTV/app/src/main/java/com/playtorrio/tv/ui/screens/anime/AnimeViewModel.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

start_idx = -1
end_idx = -1
for i, line in enumerate(lines):
    if 'AUTO-EXTRACT: tap episode' in line:
        start_idx = i
    if 'fun switchSource' in line:
        end_idx = i

if start_idx != -1 and end_idx != -1:
    new_code = '''    // -- AUTO-EXTRACT: tap episode ? race all sources ? first win plays --------
    fun clearExtractResult() {
        autoExtractResult.value = null
    }

    fun autoExtractFirst(episode: Int, category: String = selectedCategory.value) {
        val anime = selectedAnime.value ?: run {
            android.util.Log.e("AnimeVM", "autoExtractFirst: selectedAnime is NULL")
            return
        }
        android.util.Log.d("AnimeVM", "autoExtractFirst: anime= ep= cat=")
        autoExtractLoading.value = true
        autoExtractResult.value  = null
        autoExtractWinningEmbed.value = null
        autoExtractError.value   = null

        viewModelScope.launch {
            try {
                // 1. Build embeds list
                val titles = listOf(anime.titleEnglish, anime.titleRomaji, anime.titleNative)
                    .filter { it.isNotBlank() }.distinct()
                android.util.Log.d("AnimeVM", "autoExtractFirst: titles=, series=")
                val allEmbeds = AnimeService.buildAllEmbeds(
                    anilistId   = anime.id,
                    episode     = episode,
                    series      = resolvedSeries.value,
                    category    = null,
                    animeTitles = titles,
                    isAdult     = anime.isAdult,
                )
                autoExtractEmbeds.value = allEmbeds
                android.util.Log.d("AnimeVM", "autoExtractFirst:  embeds built")
                allEmbeds.forEachIndexed { idx, e -> android.util.Log.d("AnimeVM", "  embed[]: / cat=") }

                val targetEmbeds = allEmbeds.filter { it.category == category }
                if (targetEmbeds.isEmpty()) {
                    android.util.Log.e("AnimeVM", "autoExtractFirst: NO EMBEDS found for category ")
                    autoExtractError.value  = "No sources found for episode  ()"
                    autoExtractLoading.value = false
                    return@launch
                }

                // 2. Race all sources in parallel - first non-null result wins
                var winner: AnimeStreamResult? = null
                var winningEmbed: AnimeEmbed? = null
                val jobs = targetEmbeds.map { embed ->
                    async {
                        android.util.Log.d("AnimeVM", "Extracting: /...")
                        val r = runCatching { AnimeService.extractDirect(embed) }
                        if (r.isFailure) android.util.Log.e("AnimeVM", "Extract FAILED : ")
                        else android.util.Log.d("AnimeVM", "Extract OK : url=")
                        embed to r.getOrNull()
                    }
                }
                for (deferred in jobs) {
                    val (emb, result) = deferred.await()
                    if (result != null && winner == null) {
                        winner = result
                        winningEmbed = emb
                        android.util.Log.d("AnimeVM", "WINNER: ")
                        jobs.forEach { it.cancel() }
                        break
                    }
                }

                autoExtractWinningEmbed.value = winningEmbed
                autoExtractResult.value  = winner
                autoExtractError.value   = if (winner == null) "All sources failed for episode " else null
                autoExtractLoading.value = false
                android.util.Log.d("AnimeVM", "autoExtractFirst DONE: winner=")
            } catch (e: Exception) {
                android.util.Log.e("AnimeVM", "autoExtractFirst EXCEPTION", e)
                autoExtractError.value   = e.message ?: "Extraction crashed"
                autoExtractLoading.value = false
            }
        }
    }

    /** Switch to a different embed from inside the player. */
'''
    new_lines = lines[:start_idx] + [new_code] + lines[end_idx + 1:]
    with open('c:/Users/Ayman/Desktop/PlayTorrioTV/app/src/main/java/com/playtorrio/tv/ui/screens/anime/AnimeViewModel.kt', 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    print("Done")
else:
    print(f"Indices not found {start_idx} {end_idx}")
