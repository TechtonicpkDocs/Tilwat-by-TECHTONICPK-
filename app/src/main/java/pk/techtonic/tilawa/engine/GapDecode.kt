// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * Context-free gap decode. The streaming model carries a long left context, so speech in a pause between two
 * matched words can come out blank or as the verse word the pause should not contain. A second decode of just
 * that gap has no verse context. Phonemes it recovers, and that are not the anchored neighbours, are the ones
 * the checker's added-word rule can confirm.
 *
 * `GAP_PAD_S` is both the audio kept either side of the gap (so the cropped model still sees the consonants at
 * the edges) and the shortest gap worth re-decoding: anything shorter is neighbour audio, not a pause.
 * `GAP_MIN_CONF` is the checker's default confidence for treating a decoded phone as sure.
 */
const val GAP_PAD_S = 0.3
const val GAP_MIN_CONF = 0.6

/** Same consonant inventory the checker uses to decide an insertion is a word rather than a held vowel. */
private val GAP_CONSONANTS = setOf(
    'ء', 'ب', 'ت', 'ث', 'ج', 'ح', 'خ', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ',
    'ع', 'غ', 'ف', 'ق', 'ك', 'ل', 'م', 'ن', 'ه', 'و', 'ي',
)

/** The checker's `MIN_ADDED`: fewer phones than this never become an added word. */
private const val MIN_ADDED = 3

data class GapPhoneme(
    val p: Char,
    /** Inclusive frame index. */
    val start: Int,
    val end: Int,
    val conf: Double,
)

data class WordGap(
    /** Half-open frame range between the left word's last aligned phone and the right word's first. */
    val fromFrame: Int,
    val toFrame: Int,
    /** Reference phonemes of the anchored words. */
    val left: String,
    val right: String,
)

fun minGapFrames(frameMs: Int): Int = maxOf(1, Math.round((GAP_PAD_S * 1000) / frameMs).toInt())

/** Phones already in the gap are replaced by the cropped decode; everything outside the gap stays. */
fun placeGap(phonemes: List<GapPhoneme>, gap: WordGap, kept: List<GapPhoneme>): List<GapPhoneme> {
    val placed = kept.map { p ->
        p.copy(
            start = maxOf(gap.fromFrame, minOf(gap.toFrame - 1, p.start)),
            end = maxOf(gap.fromFrame, minOf(gap.toFrame - 1, maxOf(p.start, p.end))),
        )
    }
    return (phonemes.filter { p ->
        (p.start + p.end) / 2.0 < gap.fromFrame || (p.start + p.end) / 2.0 >= gap.toFrame
    } + placed).sortedWith(compareBy({ it.start }, { it.end }))
}

/**
 * An insertion the checker can call an added word: at least `MIN_ADDED` phones and two consonants.
 * Shorter recoveries are breath, a held waqf vowel, or one noisy edge, and are not spliced.
 */
fun gapCarriesAddition(phones: List<GapPhoneme>): Boolean {
    if (phones.size < MIN_ADDED) return false
    var consonants = 0
    for (ph in phones) if (GAP_CONSONANTS.contains(ph.p)) consonants++
    return consonants >= 2
}

/**
 * Phones from a context-free decode of the padded gap whose centres lie in `[gapStart, gapEnd)`,
 * dropping a prefix that repeats the left word and a suffix that repeats the right word.
 */
fun recoverGapPhonemes(
    decoded: List<GapPhoneme>,
    gapStart: Int,
    gapEnd: Int,
    left: String,
    right: String,
): List<GapPhoneme> {
    val mid = decoded.filter { p ->
        val centre = (p.start + p.end) / 2.0
        centre >= gapStart && centre < gapEnd
    }
    var a = 0
    val leftChars = left.toList()
    var k = minOf(mid.size, leftChars.size)
    while (k > 0) {
        if (left.endsWith(mid.subList(0, k).joinToString("") { it.p.toString() })) {
            a = k
            break
        }
        k--
    }
    val last = leftChars.lastOrNull()
    while (a < mid.size && last != null && mid[a].p == last) a++
    var b = mid.size
    val rightChars = right.toList()
    k = minOf(b - a, rightChars.size)
    while (k > 0) {
        if (right.startsWith(mid.subList(b - k, b).joinToString("") { it.p.toString() })) {
            b -= k
            break
        }
        k--
    }
    val first = rightChars.firstOrNull()
    while (b > a && first != null && mid[b - 1].p == first) b--
    return mid.subList(a, b)
}

data class InterWordGapsArgs(
    val phonemes: String,
    val from: Int,
    val to: Int,
    val wordAt: (Int) -> Int,
    val wordText: (Int) -> String,
    val obs: List<GapPhoneme>,
    val minFrames: Int,
    val minConf: Double = GAP_MIN_CONF,
)

/**
 * Frame gaps between consecutive reference words in one monotonic recitation of `obs` against `phonemes[from, to)`.
 * A word anchors a side only when some phone aligned to it is at least `minConf`. The boundary itself is that
 * word's first and last aligned phone, so a low-confidence tail of the word stays with the word.
 */
fun interWordGaps(args: InterWordGapsArgs): List<WordGap> {
    val minConf = args.minConf
    if (args.obs.isEmpty() || args.to <= args.from) return emptyList()
    val path = align(args.phonemes.substring(args.from, args.to), args.obs.joinToString("") { it.p.toString() })
    class Seg(val wi: Int, val first: Int, var last: Int, var confident: Boolean)
    val segs = ArrayList<Seg>()
    for (op in path) {
        val (refAt, obsAt) = when (op) {
            is AlignmentOp.Match -> Pair(op.refAt, op.obsAt)
            is AlignmentOp.Sub -> Pair(op.refAt, op.obsAt)
            else -> continue
        }
        val wi = args.wordAt(args.from + refAt)
        val ph = args.obs[obsAt]
        val tail = segs.lastOrNull()
        if (tail == null || tail.wi != wi) {
            segs.add(Seg(wi, ph.start, ph.end, ph.conf >= minConf))
        } else {
            tail.last = ph.end
            if (ph.conf >= minConf) tail.confident = true
        }
    }
    val gaps = ArrayList<WordGap>()
    for (i in 0 until segs.size - 1) {
        val left = segs[i]
        val right = segs[i + 1]
        if (right.wi != left.wi + 1 || !left.confident || !right.confident) continue
        val fromFrame = left.last + 1
        val toFrame = right.first
        if (toFrame - fromFrame < args.minFrames) continue
        val leftText = args.wordText(left.wi)
        val rightText = args.wordText(right.wi)
        if (leftText.isEmpty() || rightText.isEmpty()) continue
        gaps.add(WordGap(fromFrame, toFrame, leftText, rightText))
    }
    return gaps
}
