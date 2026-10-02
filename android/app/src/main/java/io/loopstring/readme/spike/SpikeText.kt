package io.loopstring.readme.spike

/**
 * Naive sentence split for native spikes only. The product segmenter is TS (R-M08); this
 * just turns the corpus into utterance-sized strings with the same 400-char cap.
 */
object SpikeText {
  private const val CAP = 400
  private val PARAGRAPH = Regex("\\n\\s*\\n")
  private val SPACES = Regex("\\s+")
  private val BOUNDARY = Regex("(?<=[.!?][\"')\\]]?)\\s+")

  fun sentences(text: String): List<String> =
      text.split(PARAGRAPH)
          .map { it.replace(SPACES, " ").trim() }
          .filter { it.isNotEmpty() }
          .flatMap { para -> para.split(BOUNDARY).filter { it.isNotBlank() } }
          .flatMap(::capLength)

  fun capLength(s: String): List<String> {
    if (s.length <= CAP) return listOf(s)
    val cut = s.lastIndexOf(' ', CAP).takeIf { it > 0 } ?: CAP
    return listOf(s.substring(0, cut).trim()) + capLength(s.substring(cut).trim())
  }
}
