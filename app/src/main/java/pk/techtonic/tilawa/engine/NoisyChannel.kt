// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.ln

/**
 * P(heard token | written phone) of the shipped listener, counted by [accumulateChannel] on
 * gold-excluded decodes under the shipped augmentation. Additive smoothing is the estimator, not a
 * fitted gate. A deletion is the written phone producing nothing. An insertion is a heard token
 * with no written phone.
 */
data class ChannelCounts(
    val phones: List<String>,
    /** Substitution counts, row = written phone, column = heard token. */
    val sub: Array<IntArray>,
    /** Deletion counts per written phone. */
    val del: IntArray,
    /** Insertion counts per heard token. */
    val ins: IntArray,
    /** Written phones that were aligned (substitutions + deletions). */
    var nWritten: Int,
    /**
     * Highest log-odds by which a non-neighbour word beat the best competitor on a clean
     * gold-excluded seam, under this span score. Unused by omission placement.
     */
    val cut: Double,
)

/** Laplace mass on each unseen edit. Part of the estimator, not a placement margin. */
private const val ALPHA = 0.5

data class Channel(
    val phones: List<Char>,
    val index: Map<Char, Int>,
    /** log P(heard | written), including the match. Rows sum with `del` over the simplex. */
    val logSub: Array<DoubleArray>,
    val logDel: DoubleArray,
    /** log probability of inserting this heard token, scaled per written phone. */
    val logIns: DoubleArray,
    val cut: Double,
)

fun channelFromCounts(c: ChannelCounts): Channel {
    val n = c.phones.size
    val index = c.phones.mapIndexed { i, p -> p[0] to i }.toMap()
    val logSub = ArrayList<DoubleArray>()
    val logDel = DoubleArray(n)
    for (w in 0 until n) {
        var total = c.del.getOrNull(w) ?: 0
        val rowCounts = c.sub.getOrNull(w)
        for (h in 0 until n) total += rowCounts?.getOrNull(h) ?: 0
        val z = total + ALPHA * (n + 1)
        val row = DoubleArray(n) { h -> ln(((rowCounts?.getOrNull(h) ?: 0) + ALPHA) / z) }
        logDel[w] = ln(((c.del.getOrNull(w) ?: 0) + ALPHA) / z)
        logSub.add(row)
    }
    val logIns = DoubleArray(n)
    val zIns = maxOf(1, c.nWritten) + ALPHA * n
    for (h in 0 until n) logIns[h] = ln(((c.ins.getOrNull(h) ?: 0) + ALPHA) / zIns)
    return Channel(c.phones.map { it[0] }, index, logSub.toTypedArray(), logDel, logIns, c.cut)
}

private const val NEG = -1e9

/** Viterbi log P(heard | written) under the channel. */
fun spanLogP(heard: List<Char>, written: List<Char>, ch: Channel): Double {
    val n = heard.size
    val m = written.size
    val hi = heard.map { ch.index[it] ?: -1 }
    val wi = written.map { ch.index[it] ?: -1 }
    val w = m + 1
    val dp = DoubleArray((n + 1) * w) { NEG }
    val at = { i: Int, j: Int -> i * w + j }
    dp[0] = 0.0
    for (i in 0..n) {
        for (j in 0..m) {
            val cur = dp[at(i, j)]
            if (cur <= NEG / 2) continue
            if (i < n) {
                val h = hi[i]
                val add = if (h < 0) ln(ALPHA / (ALPHA * ch.phones.size)) else ch.logIns[h]
                val to = at(i + 1, j)
                if (cur + add > dp[to]) dp[to] = cur + add
            }
            if (j < m) {
                val wr = wi[j]
                val add = if (wr < 0) ln(ALPHA / (ALPHA * (ch.phones.size + 1))) else ch.logDel[wr]
                val to = at(i, j + 1)
                if (cur + add > dp[to]) dp[to] = cur + add
            }
            if (i < n && j < m) {
                val h = hi[i]
                val wr = wi[j]
                val add = if (h < 0 || wr < 0) ln(ALPHA / (ALPHA * (ch.phones.size + 1))) else ch.logSub[wr][h]
                val to = at(i + 1, j + 1)
                if (cur + add > dp[to]) dp[to] = cur + add
            }
        }
    }
    return dp[at(n, m)]
}

/**
 * Count listener confusions along one minimum-cost alignment of a gold-excluded decode to its
 * canonical phones. Costs are the checker's existing edit costs, so the path is not fit here.
 */
fun accumulateChannel(counts: ChannelCounts, writtenRaw: String, heardRaw: String) {
    val index = counts.phones.mapIndexed { i, p -> p[0] to i }.toMap()
    val written = collapseHeld(writtenRaw.toList())
    val heard = collapseHeld(heardRaw.toList())
    val n = written.size
    val m = heard.size
    val w = m + 1
    val dp = DoubleArray((n + 1) * w)
    val bt = IntArray((n + 1) * w)
    val at = { i: Int, j: Int -> i * w + j }
    val writtenStr = written.joinToString("")
    for (j in 1..m) {
        dp[j] = dp[j - 1] + insertionCost("", 0, heard[j - 1])
        bt[j] = 1
    }
    for (i in 1..n) {
        dp[at(i, 0)] = dp[at(i - 1, 0)] + deletionCost(writtenStr, i - 1)
        bt[at(i, 0)] = 2
        for (j in 1..m) {
            val sub = dp[at(i - 1, j - 1)] + subCost(written[i - 1], heard[j - 1])
            val ins = dp[at(i, j - 1)] + insertionCost(writtenStr, i, heard[j - 1])
            val del = dp[at(i - 1, j)] + deletionCost(writtenStr, i - 1)
            var best = sub
            var how = 0
            if (ins < best) {
                best = ins
                how = 1
            }
            if (del < best) {
                best = del
                how = 2
            }
            dp[at(i, j)] = best
            bt[at(i, j)] = how
        }
    }
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        val how = bt[at(i, j)]
        if (how == 0 && i > 0 && j > 0) {
            val wr = index[written[i - 1]]
            val h = index[heard[j - 1]]
            if (wr != null && h != null) counts.sub[wr][h] += 1
            if (wr != null) counts.nWritten += 1
            i--
            j--
        } else if (how == 1 && j > 0) {
            val h = index[heard[j - 1]]
            if (h != null) counts.ins[h] += 1
            j--
        } else if (i > 0) {
            val wr = index[written[i - 1]]
            if (wr != null) {
                counts.del[wr] += 1
                counts.nWritten += 1
            }
            i--
        } else break
    }
}

fun emptyCounts(phones: List<String>, cut: Double = 0.0): ChannelCounts {
    val n = phones.size
    return ChannelCounts(
        phones = phones.toList(),
        sub = Array(n) { IntArray(n) },
        del = IntArray(n),
        ins = IntArray(n),
        nWritten = 0,
        cut = cut,
    )
}
