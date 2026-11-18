// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * A run of ayahs, four words or longer, copied elsewhere in the mushaf with at most one different word in each
 * ayah and at least one such slot. Exact copies are not spans: nothing differs, so nothing is held. Built from
 * letter skeletons only.
 */
data class SlotSpan(
    /** Word index of each member's first word. */
    val starts: List<Int>,
    /** Words in the run. The same for every member. */
    val length: Int,
    /** Word offsets that differ between members. */
    val slots: List<Int>,
)

private val slotSpanCache = java.util.WeakHashMap<ReferenceIndex, List<SlotSpan>>()

private data class SpanAyah(
    val surah: Int,
    val ayah: Int,
    val lo: Int,
    val n: Int,
    val sk: List<String>,
)

/** Ayahs of equal length whose skeletons differ in at most one word. The differing offsets, or null. */
private fun compat(ayahs: List<SpanAyah>, i: Int, j: Int): List<Int>? {
    val a = ayahs.getOrNull(i) ?: return null
    val b = ayahs.getOrNull(j) ?: return null
    if (a.n != b.n || a.n == 0) return null
    val diffs = ArrayList<Int>()
    for (k in 0 until a.n) if (a.sk[k] != b.sk[k]) diffs.add(k)
    return if (diffs.size <= 1) diffs else null
}

private fun consecutive(ayahs: List<SpanAyah>, prev: Int, next: Int): Boolean {
    val a = ayahs.getOrNull(prev) ?: return false
    val b = ayahs.getOrNull(next) ?: return false
    return a.surah == b.surah && b.ayah == a.ayah + 1
}

fun spanId(span: SlotSpan): String = "${span.starts.joinToString(",")}:${span.length}"

/** The longest span that contains `word`, if several copies of a refrain cover it. */
fun longestSpanAt(spans: List<SlotSpan>, word: Int): SlotSpan? {
    var best: SlotSpan? = null
    for (span in spans) {
        if (!span.starts.any { st -> word >= st && word < st + span.length }) continue
        if (best == null || span.length > best.length) best = span
    }
    return best
}

fun slotSpans(index: ReferenceIndex): List<SlotSpan> {
    slotSpanCache[index]?.let { return it }
    val text = index.wordText
    if (text.size != index.words.size) {
        slotSpanCache[index] = emptyList()
        return emptyList()
    }
    val ayahs = ArrayList<SpanAyah>()
    var lo = 0
    var i = 0
    while (i < index.words.size) {
        val w = index.words[i]
        var j = i + 1
        while (j < index.words.size && index.words[j].surah == w.surah && index.words[j].ayah == w.ayah) j++
        val sk = ArrayList<String>()
        for (k in i until j) sk.add(letterSkeleton(text[k]))
        ayahs.add(SpanAyah(w.surah, w.ayah, lo, j - i, sk))
        lo = j
        i = j
    }

    val groups = LinkedHashMap<String, MutableList<Int>>()
    ayahs.forEachIndexed { idx, a ->
        for (k in 0 until a.n) {
            val key = "${a.n}\u0000$k\u0000${a.sk.subList(0, k).joinToString("\u0000")}\u0000${a.sk.subList(k + 1, a.sk.size).joinToString("\u0000")}"
            groups.getOrPut(key) { ArrayList() }.add(idx)
        }
    }

    val runs = LinkedHashMap<String, SlotSpan>()
    for (idxs in groups.values) {
        val uniq = idxs.toSet().sorted()
        for (a in uniq.indices) {
            for (b in (a + 1) until uniq.size) {
                val diff = compat(ayahs, uniq[a], uniq[b])
                if (diff == null || diff.size != 1) continue
                var ii = uniq[a]
                var jj = uniq[b]
                while (ii > 0 && jj > 0 && consecutive(ayahs, ii - 1, ii) && consecutive(ayahs, jj - 1, jj) &&
                    compat(ayahs, ii - 1, jj - 1) != null
                ) {
                    ii--
                    jj--
                }
                val slots = ArrayList<Int>()
                var words = 0
                var x = ii
                var y = jj
                var ayahsN = 0
                while (x < ayahs.size && y < ayahs.size) {
                    if (ayahsN != 0 && (!consecutive(ayahs, x - 1, x) || !consecutive(ayahs, y - 1, y))) break
                    val d = compat(ayahs, x, y) ?: break
                    for (k in d) slots.add(words + k)
                    words += ayahs[x].n
                    ayahsN++
                    x++
                    y++
                }
                if (words < 4 || slots.isEmpty()) continue
                val starts = listOf(ayahs[ii].lo, ayahs[jj].lo).sorted()
                val span = SlotSpan(starts, words, slots)
                runs[spanId(span)] = span
            }
        }
    }
    val out = runs.values.toList()
    slotSpanCache[index] = out
    return out
}

/**
 * Min plain-edit cost of aligning the whole of `word` to any substring of `heard`, using the tracker's word costs.
 * `phones` is how much of `heard` that alignment consumed. `end` is the exclusive index in `heard` where it finished.
 */
data class WordSubstringFit(val edits: Double, val phones: Int, val end: Int)

fun wordSubstringCost(word: String, heard: String, tail: Boolean = false): WordSubstringFit? {
    if (word.isEmpty() || heard.isEmpty()) return null
    val n = word.length
    val m = heard.length
    var best = Double.POSITIVE_INFINITY
    var phones = 0
    var end = 0
    for (s in 0 until m) {
        val len = m - s
        var prev = DoubleArray(len + 1) { Double.POSITIVE_INFINITY }
        prev[0] = 0.0
        for (i in 1..n) {
            val cur = DoubleArray(len + 1) { Double.POSITIVE_INFINITY }
            cur[0] = prev[0] + deletionCost(word, i - 1)
            for (j in 1..len) {
                val h = heard[s + j - 1]
                val sub = prev[j - 1] + subCost(word[i - 1], h)
                val del = prev[j] + deletionCost(word, i - 1)
                val ins = cur[j - 1] + insertionCost(word, i, h, n)
                cur[j] = minOf(sub, del, ins)
            }
            prev = cur
        }
        for (j in 1..len) {
            if ((!tail || s + j >= m - 2) && prev[j] < best) {
                best = prev[j]
                phones = j
                end = s + j
            }
        }
    }
    if (!best.isFinite()) return null
    return WordSubstringFit(best / UNIT, phones, end)
}

data class ContinuationPick(val id: Int, val edits: Double, val phones: Int)

/**
 * Which member's next word the heard phones are. The current member wins when it fits and nobody beats it by
 * `margin`. Another member wins only when the current word does not fit and that one member does, ahead by the
 * margin. Otherwise wait: the word is not finished, or the copies still tie.
 */
fun continuationDecision(
    heard: String,
    candidates: List<Pair<Int, String>>,
    current: Int,
    margin: (Int) -> Double,
    maxCost: Double,
): ContinuationPick? {
    if (heard.length < 4 || candidates.size < 2) return null
    data class Scored(val id: Int, val edits: Double, val phones: Int, val per: Double)
    val scored = ArrayList<Scored>()
    for ((id, phonemes) in candidates) {
        if (phonemes.isEmpty()) return null
        val fit = wordSubstringCost(phonemes, heard, true) ?: return null
        scored.add(Scored(id, fit.edits, fit.phones, fit.edits / phonemes.length))
    }
    val cur = scored.find { it.id == current } ?: return null
    val beats = { a: Scored, b: Scored -> a.edits + margin(maxOf(1, a.phones)) <= b.edits }
    if (cur.per <= maxCost && scored.all { it.id == current || !beats(it, cur) }) {
        return ContinuationPick(current, cur.edits, cur.phones)
    }
    val winners = scored.filter { s ->
        s.id != current && s.per <= maxCost && beats(s, cur) && scored.all { o -> o.id == s.id || beats(s, o) }
    }
    if (cur.per > maxCost && winners.size == 1) {
        return ContinuationPick(winners[0].id, winners[0].edits, winners[0].phones)
    }
    return null
}
