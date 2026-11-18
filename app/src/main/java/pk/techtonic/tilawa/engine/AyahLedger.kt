// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * One closed hypothesis over stored posteriors: ayahs in mushaf order, each word a contiguous
 * repeat arc and each ayah one too. The same forward sum the parallel phrase graph uses, plus
 * those repeat arcs. There is no phone-loop filler.
 */
data class Hypothesis(val ids: IntArray, val repeats: List<Pair<Int, Int>>)

fun hypothesis(ayahs: List<List<String>>, idOf: (Char) -> Int): Hypothesis {
    val ids = ArrayList<Int>()
    val repeats = ArrayList<Pair<Int, Int>>()
    val span = { start: Int ->
        val end = ids.size - 1
        if (end >= start) repeats.add(Pair(start, end))
    }
    for (words in ayahs) {
        val ayahStart = ids.size
        for (word in words) {
            if (word.isEmpty()) continue
            val start = ids.size
            for (phone in word) ids.add(idOf(phone))
            if (ids.size > start) span(start)
        }
        if (ids.size > ayahStart) span(ayahStart)
    }
    return Hypothesis(ids.toIntArray(), repeats)
}

/**
 * How many nats the in-order text with `ayahs[drop]` removed beats the text that keeps it,
 * on frames `[from, to)`. Positive means the ayah is absent. Null when either sum is impossible.
 */
fun absenceMargin(
    frames: PosteriorFrames,
    idOf: (Char) -> Int,
    ayahs: List<List<String>>,
    drop: Int,
    from: Int,
    to: Int,
): Double? {
    if (drop < 0 || drop >= ayahs.size || to <= from) return null
    val read = hypothesis(ayahs, idOf)
    val skip = hypothesis(ayahs.filterIndexed { i, _ -> i != drop }, idOf)
    if (read.ids.isEmpty() || skip.ids.isEmpty()) return null
    val readNll = frames.nll(read.ids, from, to, read.repeats)
    val skipNll = frames.nll(skip.ids, from, to, skip.repeats)
    // A read path the frames cannot carry at all still loses to a finite skip. Both impossible is no evidence.
    if (!skipNll.isFinite()) return if (readNll.isFinite()) Double.NEGATIVE_INFINITY else null
    if (!readNll.isFinite()) return Double.POSITIVE_INFINITY
    return readNll - skipNll
}
