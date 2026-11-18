// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * Jumping to another word start: back for a repeat or self-correction, ahead for a skip. Both cost the same, so where
 * a landing word's opening also occurs earlier in the window the audio decides, not the direction.
 */
private const val JUMP_COST = 2 * UNIT

/** Per phoneme of jump distance; small enough to only break ties, in favour of the nearer landing. */
private const val JUMP_DISTANCE = 1e-3

/** The window spans this many ayahs before and after the bookmark's ayah. */
private const val BACK_AYAHS = 2
private const val AHEAD_AYAHS = 2

/**
 * Entering the next surah: reciters say a basmala between surahs, which the tracker hands to global search, so what
 * reaches a surah's end here (a takbir, a new passage) shouldn't slide into the next surah's opening.
 */
private const val SURAH_ENTRY = 3 * UNIT

/** Recent cost is averaged over this many observed phonemes. */
private const val RECENT = 16

data class LocalPosition(
    /** Reference phonemes consumed up to here (exclusive end of the last matched phoneme). */
    val offset: Int,
    /** Where the current contiguous run began; resets on a jump. */
    val origin: Int,
    /** Unweighted edits along the run, for its PER. */
    val edits: Int,
    /** Weighted cost per observed phoneme over the last `RECENT` phonemes, in plain edits. */
    val recentCost: Double,
    /** Weighted cost of the last observed phoneme, in plain edits. */
    val stepCost: Double,
)

/** The search state, to rewind to with `restore`. Steps replace the arrays rather than writing into them, so a saved state can share them. */
data class LocalSearchState(
    val lo: Int,
    val cost: DoubleArray,
    val origin: IntArray,
    val edits: IntArray,
    val best: Int,
    val ayah: Int,
    val recent: List<Double>,
)

/**
 * Streaming alignment around a bookmark: one DP column over a window of nearby ayahs, advanced one observed phoneme at
 * a time, with jump arcs from the best state to any word start in the window. Work per phoneme is bounded by the window.
 */
class LocalSearch(index: ReferenceIndex, startOffset: Int, startOrigin: Int, startEdits: Int) {
    private val ref: String = index.phonemes
    private val ayahOfWord: IntArray
    private val ayahStart: IntArray
    private val ayahEnd: IntArray
    private val wordStarts: Set<Int>
    private val surahFirst: Set<Int>
    private val wordAt: (Int) -> Int = { index.wordAt(it) }
    private val words: List<ReferenceWord> = index.words

    private var lo = 0
    private var cost = DoubleArray(0)
    private var origin = IntArray(0)
    private var edits = IntArray(0)
    private var best = startOffset
    private var ayah = -1
    private val recent = ArrayList<Double>()

    init {
        val ayahOfWordMut = ArrayList<Int>()
        val ayahStartMut = ArrayList<Int>()
        val ayahEndMut = ArrayList<Int>()
        words.forEachIndexed { i, w ->
            val prev = words.getOrNull(i - 1)
            if (prev == null || prev.surah != w.surah || prev.ayah != w.ayah) ayahStartMut.add(w.start)
            ayahOfWordMut.add(ayahStartMut.size - 1)
            val idx = ayahStartMut.size - 1
            while (ayahEndMut.size <= idx) ayahEndMut.add(0)
            ayahEndMut[idx] = w.end
        }
        ayahOfWord = ayahOfWordMut.toIntArray()
        ayahStart = ayahStartMut.toIntArray()
        ayahEnd = ayahEndMut.toIntArray()
        wordStarts = words.map { it.start }.toSet()
        // At-Tawba is the one surah entered without a basmala.
        surahFirst = words.filterIndexed { i, w ->
            i > 0 && w.surah != words[i - 1].surah && w.surah != 9
        }.map { it.start }.toSet()

        recentre(startOffset)
        cost[startOffset - lo] = 0.0
        origin[startOffset - lo] = startOrigin
        edits[startOffset - lo] = startEdits
    }

    private fun surahOf(offset: Int): Int = words[wordAt(offset)].surah
    private fun entry(offset: Int): Double = if (surahFirst.contains(offset)) SURAH_ENTRY.toDouble() else 0.0
    private fun crossing(offset: Int): Double =
        if (surahOf(offset) == surahOf(maxOf(0, best - 1))) 0.0 else SURAH_ENTRY.toDouble()

    private fun ayahOf(offset: Int): Int = ayahOfWord[wordAt(maxOf(0, offset - 1))]

    private fun recentre(offset: Int) {
        val a = ayahOf(offset)
        if (a == ayah) return
        ayah = a
        val newLo = ayahStart[maxOf(0, a - BACK_AYAHS)]
        val newHi = ayahEnd[minOf(ayahEnd.size - 1, a + AHEAD_AYAHS)]
        val c = DoubleArray(newHi - newLo + 1) { Double.POSITIVE_INFINITY }
        val o = IntArray(c.size)
        val e = IntArray(c.size)
        for (i in c.indices) {
            val old = newLo + i - lo
            if (old >= 0 && old < cost.size) {
                c[i] = cost[old]
                o[i] = origin[old]
                e[i] = edits[old]
            }
        }
        lo = newLo
        cost = c
        origin = o
        edits = e
    }

    /** Consume one observed phoneme. */
    fun step(p: Char) {
        val n = cost.size
        val base = cost[best - lo]
        val c = DoubleArray(n)
        val o = IntArray(n)
        val e = IntArray(n)
        for (i in 0 until n) {
            val at = lo + i
            var bc = cost[i] + insertionCost(ref, at, p)
            var bo = origin[i]
            var be = edits[i] + 1
            if (i > 0) {
                val s = subCost(ref[at - 1], p) + entry(at - 1)
                val edit = if (ref[at - 1] == p) 0 else 1
                // On a tie, consuming the reference beats an insertion, so a correct recitation keeps advancing.
                if (cost[i - 1] + s <= bc) {
                    bc = cost[i - 1] + s
                    bo = origin[i - 1]
                    be = edits[i - 1] + edit
                }
                val from = at - 1
                if (from != best && wordStarts.contains(from)) {
                    val j = base + JUMP_COST + Math.abs(from - best) * JUMP_DISTANCE + s +
                        (if (entry(at - 1) != 0.0) 0.0 else crossing(from))
                    if (j < bc) {
                        bc = j
                        bo = from
                        be = edit
                    }
                }
                val d = c[i - 1] + deletionCost(ref, at - 1) + entry(at - 1)
                if (d < bc) {
                    bc = d
                    bo = o[i - 1]
                    be = e[i - 1] + 1
                }
            }
            c[i] = bc
            o[i] = bo
            e[i] = be
        }

        // Lowest cost wins; ties go to the state nearest plain forward progress.
        var k = -1
        for (i in 0 until n) {
            if (k == -1 || c[i] < c[k] ||
                (c[i] == c[k] && Math.abs(lo + i - best - 1) < Math.abs(lo + k - best - 1))
            ) k = i
        }
        val min = c[k]
        for (i in 0 until n) c[i] -= min
        recent.add(min - base)
        if (recent.size > RECENT) recent.removeAt(0)
        cost = c
        origin = o
        edits = e
        best = lo + k
        recentre(best)
    }

    fun position(): LocalPosition {
        val i = best - lo
        val recentCost = if (recent.isNotEmpty()) recent.sum() / UNIT / recent.size else 0.0
        return LocalPosition(best, origin[i], edits[i], recentCost, (recent.lastOrNull() ?: 0.0) / UNIT)
    }

    fun save(): LocalSearchState = LocalSearchState(lo, cost, origin, edits, best, ayah, recent.toList())

    fun restore(state: LocalSearchState) {
        lo = state.lo
        cost = state.cost
        origin = state.origin
        edits = state.edits
        best = state.best
        ayah = state.ayah
        recent.clear()
        recent.addAll(state.recent)
    }
}
