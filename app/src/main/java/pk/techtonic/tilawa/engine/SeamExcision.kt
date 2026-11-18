// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

private val SURPLUS_NASAL_RE = Regex("([^ۦۥں۾ڇؙۜ])ں+")

/** A held nasal after phones tentatively excised as surplus is a stopped nun, not
 * the previous word's tail. A bare nasal at the span's edge still strips normally.
 * The counterfactual neighbour fit must establish that these phones are surplus. */
fun surplusNasal(raw: String): String = SURPLUS_NASAL_RE.replace(raw, "$1ن")

/** Closed read versus each neighbour omitted, on the same remaining frames.
 * Each word may use its canonical or pausal form. Both competitors get the same
 * choices and contiguous word-repeat arcs; neither has a filler path. The third
 * word is right context, not another neighbour being certified. */
fun excisedNeighbourMargins(
    frames: PosteriorFrames,
    idOf: (Char) -> Int,
    forms: List<List<String>>,
    from: Int,
    to: Int,
    cut: Pair<Int, Int>,
): List<Double>? {
    if (forms.size != 3 || forms.any { it.isEmpty() } || from < frames.startFrame || to > frames.endFrame ||
        cut.first < from || cut.second > to || cut.second <= cut.first
    ) return null
    val best = { drop: Int ->
        var nll = Double.POSITIVE_INFINITY
        val paths = ArrayList<List<String>>()
        paths.add(emptyList())
        for (i in forms.indices) {
            if (i == drop) continue
            val prev = paths.toList()
            paths.clear()
            for (path in prev) for (form in forms[i]) paths.add(path + form)
        }
        for (path in paths) {
            // Singleton groups give exactly the ledger's word-repeat arcs, without
            // inventing a repeat of a partial ayah across this seam.
            val h = hypothesis(path.map { listOf(it) }, idOf)
            nll = minOf(nll, frames.nll(h.ids, from, to, h.repeats, cut))
        }
        nll
    }
    val read = best(-1)
    if (!read.isFinite()) return null
    return listOf(best(0) - read, best(1) - read)
}
