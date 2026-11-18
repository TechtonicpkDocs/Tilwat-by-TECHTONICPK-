// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/** Floor for any token's probability, so log-scores stay finite. See docs/evidence-contract.md. */
const val MIN_P = 1e-6

/** Minimum listed probability for a token to count as supported by the posteriors. */
const val MIN_LISTED = 0.5

fun round4(x: Double): Double = Math.round(x * 1e4).toDouble() / 1e4

/**
 * Top-k posterior rows accumulated across a session, expanded on demand into per-frame log-probabilities over the
 * blank (id 0) and the inventory tokens (ids 1..size).
 */
class PosteriorFrames(private val size: Int) {
    private val ids = ArrayList<Int>()
    private val probs = ArrayList<Double>()
    private var k = 0
    private var start = -1
    private val cache = LinkedHashMap<Int, DoubleArray>()

    val startFrame: Int get() = start

    val endFrame: Int get() = start + ids.size / maxOf(1, k)

    fun append(p: TopKPosteriors) {
        if (p.ids.size != p.nFrames * p.k || p.probs.size != p.nFrames * p.k) {
            throw EvidenceError(
                "Posterior rows hold ${p.ids.size} ids and ${p.probs.size} probs, expected nFrames x k = ${p.nFrames * p.k}",
            )
        }
        if (k != 0 && p.k != k) throw EvidenceError("Posterior k changed from $k to ${p.k} within a session")
        if (start != -1 && p.startFrame != endFrame) {
            throw EvidenceError("Posterior rows start at frame ${p.startFrame}, expected $endFrame to continue the session")
        }
        for (i in p.ids.indices) {
            if (p.ids[i] < 0 || p.ids[i] > size) {
                throw EvidenceError("Posterior token id ${p.ids[i]} is outside the inventory's 0..$size")
            }
        }
        if (start == -1) start = p.startFrame
        k = p.k
        for (i in p.ids.indices) {
            ids.add(p.ids[i])
            probs.add(p.probs[i])
        }
    }

    /** Forget rows before `frame`. */
    fun dropBefore(frame: Int) {
        val drop = minOf(ids.size / maxOf(1, k), frame - start)
        if (drop <= 0) return
        repeat(drop * k) { ids.removeAt(0); probs.removeAt(0) }
        start += drop
        val it = cache.keys.iterator()
        while (it.hasNext()) if (it.next() < start) it.remove()
    }

    /** Whether token `id` is among the listed top-k at `frame`. */
    fun lists(frame: Int, id: Int): Boolean {
        val r = (frame - start) * k
        if (r < 0 || r + k > ids.size) return false
        for (c in 0 until k) if (ids[r + c] == id) return true
        return false
    }

    /** Log-probabilities of blank and every token at `frame`; omitted mass is spread over the unlisted tokens. */
    fun row(frame: Int): DoubleArray {
        val cached = cache[frame]
        if (cached != null) return cached
        val r = (frame - start) * k
        val p = DoubleArray(size + 1)
        var listed = 0.0
        var unlisted = size + 1
        for (c in 0 until k) {
            val id = ids[r + c]
            if (p[id] == 0.0) unlisted--
            p[id] += probs[r + c]
            listed += probs[r + c]
        }
        val rest = if (unlisted != 0) maxOf(0.0, 1 - listed) / unlisted else 0.0
        var total = 0.0
        for (i in 0..size) {
            if (p[i] == 0.0) p[i] = rest
            total += p[i]
        }
        val row = DoubleArray(size + 1) { i -> ln(maxOf(MIN_P, p[i] / total)) }
        cache[frame] = row
        return row
    }

    /**
     * CTC negative log-likelihood, in nats, of `labels` (token ids, no blanks) over frames `[from, to)`.
     * `repeats` are inclusive label spans that may be said again: from the end of the span, the forward
     * sum may re-enter its first label. The same arcs the alignment already uses to repeat a contiguous
     * word or ayah. No filler loop. Omitted, the sum is the plain chain.
     * `cut`, when present, removes those frames from both the emissions and the
     * recurrence; it does not replace them with blank or modify stored evidence.
     */
    fun nll(
        labels: IntArray,
        from: Int,
        to: Int,
        repeats: List<Pair<Int, Int>>? = null,
        cut: Pair<Int, Int>? = null,
    ): Double {
        val L = labels.size
        // Excise frames, rather than forcing them to blank. The state distribution carries directly
        // across the cut, exactly as it would on the concatenated remaining rows.
        var f0 = from
        if (cut != null && f0 >= cut.first && f0 < cut.second) f0 = cut.second
        if (to <= f0) return if (L != 0) Double.POSITIVE_INFINITY else 0.0
        val S = 2 * L + 1
        val label = { s: Int -> if (s % 2 == 0) 0 else labels[(s - 1) shr 1] }
        val back = repeatBack(repeats, L)
        var prev = DoubleArray(S) { Double.NEGATIVE_INFINITY }
        var next = DoubleArray(S)
        val r0 = row(f0)
        prev[0] = r0[0]
        if (L != 0) prev[1] = r0[label(1)]
        for (f in (f0 + 1) until to) {
            if (cut != null && f >= cut.first && f < cut.second) continue
            val row = row(f)
            for (s in 0 until S) {
                var a = prev[s]
                if (s > 0) a = logAdd(a, prev[s - 1])
                if (s > 1 && s % 2 == 1 && label(s) != label(s - 2)) a = logAdd(a, prev[s - 2])
                if (back != null && s % 2 == 1) {
                    val extra = back[(s - 1) shr 1]
                    for (x in extra) a = logAdd(a, prev[x])
                }
                next[s] = a + row[label(s)]
            }
            val tmp = prev
            prev = next
            next = tmp
        }
        val end = if (L != 0) logAdd(prev[S - 1], prev[S - 2]) else prev[0]
        return -end
    }
}

/** States that may re-enter each label, for contiguous repeat arcs. Null when there are none. */
private fun repeatBack(repeats: List<Pair<Int, Int>>?, L: Int): Array<IntArray>? {
    if (repeats.isNullOrEmpty() || L == 0) return null
    val S = 2 * L + 1
    val lists = Array(L) { ArrayList<Int>() }
    val seen = Array(L) { LinkedHashSet<Int>() }
    var any = false
    for ((start, end) in repeats) {
        if (start < 0 || end < start || end >= L) continue
        fun add(state: Int) {
            if (state < 0 || state >= S || state == 2 * start + 1 || seen[start].contains(state)) return
            seen[start].add(state)
            lists[start].add(state)
            any = true
        }
        add(2 * end + 1)
        add(2 * end + 2)
    }
    return if (any) Array(L) { lists[it].toIntArray() } else null
}

fun logAdd(a: Double, b: Double): Double {
    if (a == Double.NEGATIVE_INFINITY) return b
    if (b == Double.NEGATIVE_INFINITY) return a
    return if (a > b) a + ln1p(exp(b - a)) else b + ln1p(exp(a - b))
}

/**
 * Top-k entries of one log-probability row, best first, as (token id, probability) pairs.
 * Ties keep the lower id first (strict `>` displacement, matching the TS insertion exactly).
 */
fun topK(row: FloatArray, k: Int): List<Pair<Int, Double>> {
    val best = ArrayList<Pair<Int, Double>>()
    for (i in row.indices) {
        val v = row[i].toDouble()
        if (best.size < k || v > best[best.size - 1].second) {
            var j = if (best.size < k) best.size else k - 1
            if (best.size < k) best.add(Pair(i, v))
            while (j > 0 && best[j - 1].second < v) {
                best[j] = best[j - 1]
                j--
            }
            best[j] = Pair(i, v)
        }
    }
    return best.map { Pair(it.first, exp(it.second)) }
}
