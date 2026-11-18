// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/** Integer cost units; one plain edit costs `UNIT`. */
const val UNIT = 10
private const val LENGTH_CHANGE = 2
private const val CONFUSABLE = 5
private const val SHORT_VOWEL = 5

private val MADD_LETTERS = setOf('ا', 'ۦ', 'ۥ')
private val SHORT_VOWELS = setOf('َ', 'ُ', 'ِ')
private val CONFUSABLE_PAIRS = listOf("هح", "عء", "صس", "ضد", "طت", "قك", "ذز", "ثس")
private val confusable = CONFUSABLE_PAIRS.flatMap { listOf(it, "${it[1]}${it[0]}") }.toSet()

fun subCost(ref: Char, obs: Char): Double {
    if (ref == obs) return 0.0
    if (confusable.contains("$ref$obs")) return CONFUSABLE.toDouble()
    if (SHORT_VOWELS.contains(ref) && SHORT_VOWELS.contains(obs)) return SHORT_VOWEL.toDouble()
    return UNIT.toDouble()
}

/** Ghunna is written as a run: `ں` for ikhfaa, `۾` for ikhfaa shafawi, `نننن`/`مممم` for a mushaddad noon or meem. */
private val GHUNNA_RUNS = setOf('ں', '۾')
private val GHUNNA_DOUBLED = setOf('ن', 'م')

/**
 * Whether a run of `p` that is `run` long before an edit and `run ± 1` after it is still the same sound, held
 * longer or shorter. A doubled noon or meem shorter than 3 is a shadda, so shortening it is a real error.
 */
private fun isLengthening(p: Char, run: Int): Boolean {
    if (MADD_LETTERS.contains(p) || GHUNNA_RUNS.contains(p)) return run >= 2
    if (GHUNNA_DOUBLED.contains(p)) return run >= 3
    return false
}

/** Sounds whose held length varies between reciters; runs of them collapse before posterior scoring. */
private val HELD = MADD_LETTERS + GHUNNA_RUNS
fun isHeld(p: Char): Boolean = HELD.contains(p)

/**
 * Collapse held-sound runs so CTC scoring doesn't charge length: a madd or ghunna run becomes one token, and a noon or
 * meem run of 3 or more (ghunna on a shadda) becomes two. Length stays the decoded alignment's job.
 */
fun collapseHeld(tokens: List<Char>): List<Char> {
    val out = ArrayList<Char>()
    var i = 0
    while (i < tokens.size) {
        var j = i
        while (j < tokens.size && tokens[j] == tokens[i]) j++
        val run = j - i
        val keep = if (HELD.contains(tokens[i])) 1 else if (GHUNNA_DOUBLED.contains(tokens[i]) && run >= 3) 2 else run
        repeat(keep) { out.add(tokens[i]) }
        i = j
    }
    return out
}

private fun indelCost(p: Char, lengthening: Boolean): Double {
    if (lengthening) return LENGTH_CHANGE.toDouble()
    if (SHORT_VOWELS.contains(p)) return SHORT_VOWEL.toDouble()
    return UNIT.toDouble()
}

/** Length of the run of `s[k]` that contains `k`. */
private fun runAt(s: String, k: Int): Int {
    var a = k
    var b = k
    while (a > 0 && s[a - 1] == s[k]) a--
    while (b + 1 < s.length && s[b + 1] == s[k]) b++
    return b - a + 1
}

/**
 * Inserting `p` before `ref[at]`: lengthening when it sits beside a reference run of `p`. Only `ref` before `end` counts
 * as a neighbour.
 */
fun insertionCost(ref: String, at: Int, p: Char, end: Int = ref.length): Double {
    val before = if (at > 0 && ref[at - 1] == p) runAt(ref, at - 1) else 0
    val after = if (at < end && ref[at] == p) runAt(ref, at) else 0
    return indelCost(p, isLengthening(p, maxOf(before, after) + 1))
}

/** Dropping `ref[at]`: shortening when it's part of a held run. */
fun deletionCost(ref: String, at: Int): Double {
    return indelCost(ref[at], isLengthening(ref[at], runAt(ref, at)))
}

data class SpanAlignment(
    /** Half-open phoneme range in the searched reference. */
    val start: Int,
    val end: Int,
    /** Weighted cost in `UNIT`s, for ranking only. */
    val cost: Double,
    /** Unweighted phoneme edits along `path` divided by the matched reference length. */
    val per: Double,
    /** `refAt` is relative to `start`. */
    val path: List<AlignmentOp>,
)

private val INF = Double.POSITIVE_INFINITY

// Token-code tables for the alignSubstring hot loop; identical mechanism to the TS (module-global, growing).
private val codes = LinkedHashMap<Char, Int>()
private fun tokenCode(p: Char): Int {
    val c = codes[p]
    if (c != null) return c
    val n = codes.size
    codes[p] = n
    return n
}

private class SubRow(val row: DoubleArray, val filled: Int)
private val subRows = ArrayList<SubRow?>()

/** `subCost(ref, obs) + EDIT` by observed token code, or 0 on a match; rows grow as new tokens appear. */
private fun subTable(refCode: Int): DoubleArray {
    val have = if (refCode < subRows.size) subRows[refCode] else null
    if (have != null && have.filled == codes.size) return have.row
    val ref = codes.keys.elementAt(refCode)
    val row = DoubleArray(codes.size)
    for ((obs, c) in codes) row[c] = if (ref == obs) 0.0 else subCost(ref, obs) + EDIT
    while (subRows.size <= refCode) subRows.add(null)
    subRows[refCode] = SubRow(row, codes.size)
    return row
}

/**
 * Each plain edit adds this to the DP key, so equal weighted costs resolve to the path with fewer edits and the PER
 * isn't inflated by an arbitrary choice. A power of two keeps the sums exact; it stays below 1 for up to 2^20 edits.
 */
private val EDIT = Math.pow(2.0, -20.0)

/** Starting inside a word: the clip was cut mid-word. */
const val MID_WORD_START = 10

data class AlignOptions(
    /** Per-phoneme decoder confidence. Leading observed phonemes can be dropped at `UNIT * conf` each. */
    val conf: List<Double>? = null,
    /** Only allow starts where this holds. Other starts cost `MID_WORD_START`. */
    val strictStart: Boolean = false,
)

/**
 * Semi-global weighted alignment of all of `obs` against a substring of `ref[lo, hi)`.
 * Word starts (`isStart`) are free; its end is free so an in-progress word can match.
 */
fun alignSubstring(
    ref: String,
    lo: Int,
    hi: Int,
    obs: String,
    isStart: (offset: Int) -> Boolean,
    options: AlignOptions = AlignOptions(),
): SpanAlignment? {
    val n = hi - lo
    val m = obs.length
    val w = m + 1
    val d = DoubleArray((n + 1) * w)
    val at = { i: Int, j: Int -> i * w + j }
    val r = { i: Int -> ref[lo + i - 1] }
    val insCost = { i: Int, j: Int -> insertionCost(ref, lo + i, obs[j - 1], hi) + EDIT }
    val delCost = { i: Int -> deletionCost(ref, lo + i - 1) + EDIT }
    val sub = { i: Int, j: Int -> if (r(i) == obs[j - 1]) 0.0 else subCost(r(i), obs[j - 1]) + EDIT }
    val startCost = { i: Int -> if (isStart(lo + i)) 0.0 else if (options.strictStart) INF else MID_WORD_START.toDouble() }
    val skip = DoubleArray(m + 1) { INF }
    skip[0] = 0.0
    if (options.conf != null) {
        for (j in 1..m) skip[j] = skip[j - 1] + Math.round(UNIT * options.conf[j - 1]) + EDIT
    }

    // The fill below is the hot loop of global search; it reads costs from small tables instead of the closures above.
    val obsCode = IntArray(m + 1)
    val insBase = DoubleArray(m + 1)
    for (j in 1..m) {
        obsCode[j] = tokenCode(obs[j - 1])
        insBase[j] = indelCost(obs[j - 1], false) + EDIT
    }
    for (i in 0..n) {
        val start = startCost(i)
        val row = i * w
        d[row] = start
        // Insertion before ref[lo + i] only differs from its base cost beside a reference run of the same sound.
        val prevCode = if (lo + i > 0) tokenCode(ref[lo + i - 1]) else -1
        val nextCode = if (lo + i < hi) tokenCode(ref[lo + i]) else -1
        val del = if (i > 0) delCost(i) else INF
        val subRow = if (i > 0) subTable(prevCode) else null
        for (j in 1..m) {
            val c = obsCode[j]
            val ins = if (c == prevCode || c == nextCode) insCost(i, j) else insBase[j]
            var best = minOf(d[row + j - 1] + ins, start + skip[j])
            if (subRow != null) best = minOf(best, d[row - w + j - 1] + subRow[c], d[row - w + j] + del)
            d[row + j] = best
        }
    }

    var endI = -1
    for (i in 0..n) if (endI == -1 || d[at(i, m)] < d[at(endI, m)]) endI = i
    if (endI == -1 || d[at(endI, m)] >= INF) return null

    val ops = ArrayList<AlignmentOp>()
    var i = endI
    var j = m
    while (j > 0) {
        val here = d[at(i, j)]
        if (here == startCost(i) + skip[j]) {
            // Dropped leading phonemes stay in the path as insertions so the PER still counts them.
            for (k in j downTo 1) ops.add(AlignmentOp.Ins(obs[k - 1], k - 1))
            break
        }
        if (i > 0 && j > 0 && here == d[at(i - 1, j - 1)] + sub(i, j)) {
            ops.add(
                if (r(i) == obs[j - 1]) AlignmentOp.Match(r(i), i - 1, j - 1)
                else AlignmentOp.Sub(r(i), obs[j - 1], i - 1, j - 1),
            )
            i--
            j--
        } else if (i > 0 && here == d[at(i - 1, j)] + delCost(i)) {
            ops.add(AlignmentOp.Del(r(i), i - 1))
            i--
        } else {
            ops.add(AlignmentOp.Ins(obs[j - 1], j - 1))
            j--
        }
    }
    ops.reverse()
    for (o in ops) {
        when (o) {
            is AlignmentOp.Match -> o.refAt -= i
            is AlignmentOp.Sub -> o.refAt -= i
            is AlignmentOp.Del -> o.refAt -= i
            is AlignmentOp.Ins -> {}
        }
    }

    val length = endI - i
    val edits = ops.count { it.op != "match" }
    return SpanAlignment(
        start = lo + i,
        end = lo + endI,
        cost = Math.floor(d[at(endI, m)]),
        per = if (length != 0) edits.toDouble() / length else if (edits != 0) Double.POSITIVE_INFINITY else 0.0,
        path = ops,
    )
}
