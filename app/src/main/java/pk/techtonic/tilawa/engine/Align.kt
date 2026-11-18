// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

sealed class AlignmentOp {
    abstract val op: String

    /** `refAt` is mutable: alignSubstring re-bases it onto the match start after backtracking. */
    data class Match(val ref: Char, var refAt: Int, val obsAt: Int) : AlignmentOp() {
        override val op: String = "match"
    }

    data class Sub(val ref: Char, val obs: Char, var refAt: Int, val obsAt: Int) : AlignmentOp() {
        override val op: String = "sub"
    }

    data class Del(val ref: Char, var refAt: Int) : AlignmentOp() {
        override val op: String = "del"
    }

    data class Ins(val obs: Char, val obsAt: Int) : AlignmentOp() {
        override val op: String = "ins"
    }

    /** Copy with `obsAt` shifted (used by the tracker's basmala prefix merge). */
    fun withObsAtShift(k: Int): AlignmentOp = when (this) {
        is Match -> copy(obsAt = obsAt + k)
        is Sub -> copy(obsAt = obsAt + k)
        is Del -> this
        is Ins -> copy(obsAt = obsAt + k)
    }
}

/** Unit-cost Levenshtein alignment of observed phonemes against a reference span. */
fun align(ref: String, obs: String): List<AlignmentOp> {
    val n = ref.length
    val m = obs.length
    val d = IntArray((n + 1) * (m + 1))
    val at = { i: Int, j: Int -> i * (m + 1) + j }
    for (i in 0..n) d[at(i, 0)] = i
    for (j in 0..m) d[at(0, j)] = j
    for (i in 1..n) {
        for (j in 1..m) {
            val diag = d[at(i - 1, j - 1)] + if (ref[i - 1] == obs[j - 1]) 0 else 1
            d[at(i, j)] = minOf(diag, d[at(i - 1, j)] + 1, d[at(i, j - 1)] + 1)
        }
    }
    val ops = ArrayList<AlignmentOp>()
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && d[at(i, j)] == d[at(i - 1, j - 1)] + if (ref[i - 1] == obs[j - 1]) 0 else 1) {
            ops.add(
                if (ref[i - 1] == obs[j - 1]) AlignmentOp.Match(ref[i - 1], i - 1, j - 1)
                else AlignmentOp.Sub(ref[i - 1], obs[j - 1], i - 1, j - 1),
            )
            i--
            j--
        } else if (i > 0 && d[at(i, j)] == d[at(i - 1, j)] + 1) {
            ops.add(AlignmentOp.Del(ref[i - 1], i - 1))
            i--
        } else {
            ops.add(AlignmentOp.Ins(obs[j - 1], j - 1))
            j--
        }
    }
    return ops.reversed()
}
