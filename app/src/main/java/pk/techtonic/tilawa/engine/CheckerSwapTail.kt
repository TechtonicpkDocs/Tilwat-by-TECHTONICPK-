// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

data class ObsSpan(val obsFrom: Int, val obsTo: Int)

data class SwapAccusation(
    val obsFrom: Int,
    val obsTo: Int,
    val cls: MemorizationErrorClass,
    val word: Int,
    val last: Int,
    val lexicon: Boolean = false,
)

interface SwapTailContext {
    fun nextWord(wi: Int): Int
    val visits: List<ObsSpan>
    fun labelledPause(prev: Int): Boolean
    fun heard(span: ObsSpan): String
    fun spellsWord(wi: Int, heard: String): String?
}

/**
 * A split substitution can leave an added-word tail across an ordinary inter-phone blank. Adjacency is
 * structural: no labelled pause or counting word visit between the accusations, and together they spell
 * one whole non-neighbour word. Spell-rule additions keep their independent accusation.
 */
fun swapTailUnion(
    wrong: SwapAccusation?,
    added: SwapAccusation,
    context: SwapTailContext,
): ObsSpanWithObserved? {
    if (wrong == null || wrong.cls != "wrong_word" || added.cls != "added_word" || added.lexicon ||
        context.nextWord(wrong.last) != added.word || wrong.obsFrom < 0 || wrong.obsTo <= wrong.obsFrom ||
        added.obsTo <= added.obsFrom || wrong.obsTo > added.obsFrom
    ) return null
    if (context.visits.any { v -> v.obsFrom < added.obsFrom && v.obsTo > wrong.obsTo }) return null
    for (k in (wrong.obsTo - 1) until added.obsFrom) if (context.labelledPause(k)) return null
    val span = ObsSpan(wrong.obsFrom, added.obsTo)
    val observed = context.heard(span)
    return if (context.spellsWord(wrong.word, observed) == null) null else ObsSpanWithObserved(span, observed)
}

data class ObsSpanWithObserved(val span: ObsSpan, val observed: String)
