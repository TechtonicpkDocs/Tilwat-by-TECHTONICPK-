// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.exp

private data class SeamArc(val src: Int, val dst: Int, val cost: Double, val insertion: Boolean)
private data class SeamWord(val first: List<Int>, val last: List<Int>, val blank: Int)

/**
 * Closed seam CTC lattice with an optional exact word between the two text sides.
 * Each word may have attested pronunciation variants. Word and contiguous side
 * repeats use today's repeat transitions. No repeat crosses the optional word,
 * so its entrance is traversed at most once. Its marginal is the forward-backward
 * mass through that entrance, summed over all frame timings, divided by total mass.
 * Blank runs are shared at boundaries, including the no-insertion boundary; there
 * is no epsilon path that counts the same blank timing twice. No phone-loop filler.
 */
fun seamInsertionMarginal(
    frames: PosteriorFrames,
    before: List<List<List<Int>>>,
    candidate: List<List<Int>>,
    after: List<List<List<Int>>>,
    from: Int,
    to: Int,
    insertionCost: Double,
    repeats: Boolean = true,
): Double? {
    if (before.isEmpty() || after.isEmpty() || candidate.isEmpty() || !insertionCost.isFinite() ||
        from < frames.startFrame || to > frames.endFrame || to <= from
    ) return null
    val groups = before + listOf(candidate) + after
    if (groups.any { g -> g.isEmpty() || g.any { f -> f.isEmpty() || f.any { p -> p <= 0 } } }) return null
    val labels = ArrayList<Int>()
    labels.add(0)
    val arcs = ArrayList<SeamArc>()
    val seen = LinkedHashSet<String>()
    val state = { label: Int ->
        labels.add(label)
        labels.size - 1
    }
    val arc = { src: Int, dst: Int, cost: Double, insertion: Boolean ->
        val key = "$src:$dst:$cost:$insertion"
        if (!seen.contains(key)) {
            seen.add(key)
            arcs.add(SeamArc(src, dst, cost, insertion))
        }
    }
    val connect = { sources: List<Int>, first: List<Int>, cost: Double, insertion: Boolean, repeat: Boolean ->
        for (src in sources) for (dst in first) {
            // Ordinary CTC needs a blank between equal labels. Existing repeat arcs
            // permit re-entering a different state even when its label is equal.
            if (src != dst && (repeat || labels[src] == 0 || labels[src] != labels[dst])) {
                arc(src, dst, cost, insertion)
            }
        }
    }
    val word = { forms: List<List<Int>>, sources: List<Int>, inserted: Boolean ->
        val blank = state(0)
        val first = ArrayList<Int>()
        val last = ArrayList<Int>()
        val unique = forms.distinctBy { it.joinToString(",") }
        for (form in unique) {
            var prev = -1
            for (i in form.indices) {
                val current = state(form[i])
                if (i == 0) {
                    first.add(current)
                } else {
                    val between = state(0)
                    arc(prev, between, 0.0, false)
                    arc(between, current, 0.0, false)
                    if (labels[prev] != labels[current]) arc(prev, current, 0.0, false)
                }
                prev = current
            }
            last.add(prev)
            arc(prev, blank, 0.0, false)
        }
        connect(sources, first, if (inserted) insertionCost else 0.0, inserted, false)
        if (repeats) connect(last + blank, first, 0.0, false, true)
        SeamWord(first, last, blank)
    }
    // `word` is a lambda; `side` needs to call it recursively-free, so define side inline below via a local function.
    fun side(forms: List<List<List<Int>>>, sources0: List<Int>): SeamWord {
        var first: List<Int> = emptyList()
        var sources = sources0
        var tail: SeamWord? = null
        for (variants in forms) {
            tail = word(variants, sources, false)
            if (first.isEmpty()) first = tail.first
            sources = tail.last + tail.blank
        }
        if (repeats) connect(sources, first, 0.0, false, true)
        return tail!!
    }
    val left = side(before, listOf(0))
    val boundary = left.last + left.blank
    val extra = word(candidate, boundary, true)
    // Direct no-word transitions avoid an epsilon skip and duplicate blank runs.
    val right = side(after, boundary + extra.last + extra.blank)
    val finals = right.last + right.blank
    for (s in labels.indices) arc(s, s, 0.0, false)
    val S = labels.size
    val T = to - from
    val probeRow = frames.row(from)
    if (labels.any { it >= probeRow.size }) return null
    val alpha = ArrayList<DoubleArray>()
    alpha.add(DoubleArray(S) { Double.NEGATIVE_INFINITY })
    alpha[0][0] = 0.0
    for (t in 0 until T) {
        val prev = alpha[t]
        val next = DoubleArray(S) { Double.NEGATIVE_INFINITY }
        val row = frames.row(from + t)
        for (a in arcs) next[a.dst] = logAdd(next[a.dst], prev[a.src] - a.cost + row[labels[a.dst]])
        alpha.add(next)
    }
    var z = Double.NEGATIVE_INFINITY
    for (s in finals) z = logAdd(z, alpha[T][s])
    if (!z.isFinite()) return null
    var beta = DoubleArray(S) { Double.NEGATIVE_INFINITY }
    var used = Double.NEGATIVE_INFINITY
    for (s in finals) beta[s] = 0.0
    for (t in T - 1 downTo 0) {
        val prev = DoubleArray(S) { Double.NEGATIVE_INFINITY }
        val row = frames.row(from + t)
        for (a in arcs) {
            val b = -a.cost + row[labels[a.dst]] + beta[a.dst]
            prev[a.src] = logAdd(prev[a.src], b)
            if (a.insertion) used = logAdd(used, alpha[t][a.src] + b)
        }
        beta = prev
    }
    return maxOf(0.0, minOf(1.0, exp(used - z)))
}
