// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

private val LEX_CONSONANTS = setOf(
    'ء', 'ب', 'ت', 'ث', 'ج', 'ح', 'خ', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ',
    'ع', 'غ', 'ف', 'ق', 'ك', 'ل', 'م', 'ن', 'ه', 'و', 'ي',
)

private fun lexForm(s: String): String = collapseHeld(s.toList()).joinToString("")

/**
 * A grammatical ending (ـما, a case vowel on top of it) stays at the tail. Longer than this, the divergence is the
 * stem and the ordinary consonant rules own it.
 */
private const val END = 4

/** Common stem, then two short tails. The stem has to outlast either tail, so a short shared prefix is not an ending. */
private data class Residual(val stem: String, val sa: String, val sb: String)

private fun residual(a: String, b: String): Residual? {
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    val sa = a.substring(i)
    val sb = b.substring(i)
    if (i == 0 || sa.length > END || sb.length > END || i <= sa.length || i <= sb.length) return null
    return Residual(a.substring(0, i), sa, sb)
}

private fun consonantsOf(s: String): String = s.filter { LEX_CONSONANTS.contains(it) }

/** One side adds or drops consonants and the other side is only vowels, madd or a waqf mark. A substitution is neither. */
private fun endSegment(sa: String, sb: String): String? {
    val ca = consonantsOf(sa)
    val cb = consonantsOf(sb)
    if ((ca.isEmpty()) == (cb.isEmpty())) return null
    return ca.ifEmpty { cb }
}

/** Cost of the tails only. The matched stem stays put, so a following vowel cannot be rewritten into the consonant. */
private fun endingCost(stem: String, sa: String, sb: String): Double {
    val ref = stem + sa
    val base = stem.length
    val n = sa.length
    val m = sb.length
    var row = DoubleArray(m + 1)
    for (j in 1..m) row[j] = row[j - 1] + insertionCost(ref, base, sb[j - 1], ref.length)
    for (i in 1..n) {
        val next = DoubleArray(m + 1)
        next[0] = row[0] + deletionCost(ref, base + i - 1)
        for (j in 1..m) {
            next[j] = minOf(
                row[j - 1] + subCost(sa[i - 1], sb[j - 1]),
                row[j] + deletionCost(ref, base + i - 1),
                next[j - 1] + insertionCost(ref, base + i, sb[j - 1], ref.length),
            )
        }
        row = next
    }
    return row[m]
}

/**
 * Align individual consonants independently of vowels, using the existing costs. Hard edits sharing an original
 * contiguous run on either side count as one independent change. This keeps a doubled identity change together
 * without inventing edits when vowels split a run, or erasing separate changes on both sides of a vowel.
 */
fun hardConsonantEdits(expected: String, observed: String): Double {
    val a = lexForm(expected)
    val b = lexForm(observed)
    data class Cons(val at: Int, val run: Int)
    val consonants = { s: String ->
        val out = ArrayList<Cons>()
        var run = -1
        for (i in s.indices) if (LEX_CONSONANTS.contains(s[i])) {
            if (i == 0 || s[i - 1] != s[i]) run++
            out.add(Cons(i, run))
        }
        out
    }
    val r = consonants(a)
    val o = consonants(b)
    val width = o.size + 1
    // Only the current reference/observed runs can receive future edits. Preserve five frontier states so a later
    // substitution can join their components before they close: none, R, O, R/O separate, R/O joined.
    val cell = { i: Int, j: Int, state: Int -> (i * width + j) * 5 + state }
    val cost = DoubleArray((r.size + 1) * width * 5) { Double.POSITIVE_INFINITY }
    val closed = DoubleArray(cost.size) { Double.POSITIVE_INFINITY }
    cost[0] = 0.0
    closed[0] = 0.0
    for (i in 0..r.size) for (j in 0..o.size) for (state in 0 until 5) {
        val from = cell(i, j, state)
        if (!cost[from].isFinite()) continue
        val step = { di: Int, dj: Int, charge: Double ->
            var activeR = state == 1 || state >= 3
            var activeO = state == 2 || state >= 3
            var joined = state == 4
            if (charge >= UNIT) {
                if (di != 0) activeR = true
                if (dj != 0) activeO = true
                if (di != 0 && dj != 0) joined = true
            }
            val ni = i + di
            val nj = j + dj
            val keepR = activeR && ni < r.size && r[ni].run == r.getOrNull(i)?.run
            val keepO = activeO && nj < o.size && o[nj].run == o.getOrNull(j)?.run
            val finished = if (joined) {
                if (!keepR && !keepO) 1.0 else 0.0
            } else {
                (if (activeR && !keepR) 1.0 else 0.0) + (if (activeO && !keepO) 1.0 else 0.0)
            }
            val next = if (keepR && keepO) (if (joined) 4 else 3) else if (keepR) 1 else if (keepO) 2 else 0
            val to = cell(ni, nj, next)
            val c = cost[from] + charge
            val h = closed[from] + finished
            if (c < cost[to] || (c == cost[to] && h < closed[to])) {
                cost[to] = c
                closed[to] = h
            }
        }
        if (i < r.size && j < o.size) step(1, 1, subCost(a[r[i].at], b[o[j].at]))
        if (i < r.size) step(1, 0, deletionCost(a, r[i].at))
        if (j < o.size) step(0, 1, insertionCost(a, r.getOrNull(i)?.at ?: a.length, b[o[j].at]))
    }
    return closed[cell(r.size, o.size, 0)]
}

data class EndingResult(val observedConsonant: Boolean)
data class LexicalContext(val before: String?, val after: String?)

/** Shared by checker instances; only supported reference pronunciations enter the whole-word vocabulary. */
class LexicalWords(index: ReferenceIndex) {
    private val forms: List<List<String>>
    private val isolated: Set<String>
    private val connected: Map<String, Set<String>>
    private val byStem: Map<String, List<String>>
    private val rawByForm: Map<String, List<String>>
    private val wordNorms: List<List<String>>
    private val bySkel: Map<String, List<String>>
    private val words: List<ReferenceWord> = index.words
    private val phonemes: String = index.phonemes
    private val isolatedForms: List<String> = index.isolatedWordForms

    // Declared before the init block: norm() runs during init and reads it.
    private val mark = setOf('ڇ', 'ۜ', 'ؙ')

    init {
        forms = index.words.mapIndexed { i, w ->
            val raw = ArrayList<String>()
            raw.add(phonemes.substring(w.start, w.end))
            raw.addAll(index.wordForms.getOrNull(i) ?: emptyList())
            raw.filter { it.isNotEmpty() }.map { lexForm(it) }.distinct()
        }
        isolated = index.isolatedWordForms.filter { it.isNotEmpty() }.map { lexForm(it) }.toSet()
        val connectedMut = LinkedHashMap<String, LinkedHashSet<String>>()
        val sameAyah = { a: Int, b: Int ->
            b >= 0 && b < index.words.size &&
                index.words[a].surah == index.words[b].surah && index.words[a].ayah == index.words[b].ayah
        }
        val neighbor = { wi: Int, direction: Int ->
            var at = wi + direction
            while (sameAyah(wi, at) && index.words[at].start == index.words[at].end) at += direction
            if (sameAyah(wi, at)) at else -1
        }
        val word = { wi: Int -> phonemes.substring(index.words[wi].start, index.words[wi].end) }
        val boundary = { text: String, before: Boolean ->
            if (before) lexForm(text).lastOrNull() else lexForm(text).firstOrNull()
        }
        val contextKey = { before: Char?, after: Char? -> "${before ?: '^'}|${after ?: '$'}" }
        index.words.forEachIndexed { wi, w ->
            if (w.start == w.end) return@forEachIndexed
            val before = neighbor(wi, -1)
            val after = neighbor(wi, 1)
            val key = contextKey(
                if (before < 0) null else boundary(word(before), true),
                if (after < 0) null else boundary(word(after), false),
            )
            val text = lexForm(word(wi))
            connectedMut.getOrPut(text) { LinkedHashSet() }.add(key)
        }
        connected = connectedMut

        val attested = LinkedHashSet<String>()
        for (list in forms) for (f in list) if (f.isNotEmpty()) attested.add(f)
        val rawByFormMut = LinkedHashMap<String, MutableList<String>>()
        for (list in index.wordForms) for (raw in list) {
            if (raw.isEmpty()) continue
            val key = lexForm(raw)
            val have = rawByFormMut.getOrPut(key) { ArrayList() }
            if (!have.contains(raw)) have.add(raw)
        }
        rawByForm = rawByFormMut
        // Forms that share a stem and differ by a short tail. Queried from the written word's own tail, never from a verse.
        val byStemMut = LinkedHashMap<String, MutableList<String>>()
        for (f in attested) {
            var n = 0
            while (n <= END && n < f.length) {
                val stem = f.substring(0, f.length - n)
                if (stem.length > n) byStemMut.getOrPut(stem) { ArrayList() }.add(f)
                n++
            }
        }
        byStem = byStemMut

        // Sukun and pause marks. They are not consonants; a spelled word stays the same word without them.
        wordNorms = forms.map { list ->
            list.map { norm(it) }.filter { it.isNotEmpty() }.distinct()
        }
        val bySkelMut = LinkedHashMap<String, MutableList<String>>()
        for (list in wordNorms) for (n in list) {
            val sk = skel(n)
            if (sk.length < 2) continue
            val have = bySkelMut.getOrPut(sk) { ArrayList() }
            if (!have.contains(n)) have.add(n)
        }
        bySkel = bySkelMut
    }

    private fun norm(s: String): String = collapseHeld(s.filter { !mark.contains(it) }.toList()).joinToString("")
    private fun skel(s: String): String = s.filter { LEX_CONSONANTS.contains(it) }

    fun matches(wi: Int, heard: String): Boolean = forms[wi].contains(lexForm(heard))

    /**
     * The heard word is another mushaf form that the written word reaches by adding or dropping an ending, and that
     * ending carries a consonant. Waqf and a held vowel change only vowels, tanween or a pause mark, which is one of
     * this word's own forms or has no consonant to anchor.
     */
    fun ending(wi: Int, heardRaw: String): EndingResult? {
        val heard = lexForm(heardRaw)
        val own = forms[wi]
        if (heard.isEmpty() || own.isEmpty() || own.contains(heard)) return null
        // A vowel-only or pause-mark tail of any own pronunciation is this word, not a different one.
        if (own.any { f ->
                val r = residual(f, heard)
                r != null && endSegment(r.sa, r.sb) == null
            }
        ) return null
        var written: Pair<String, Double>? = null
        for (f in own) {
            val r = residual(f, heard)
            if (r == null || endSegment(r.sa, r.sb) == null) continue
            val cost = endingCost(r.stem, r.sa, r.sb)
            if (written == null || cost < written.second) written = Pair(f, cost)
        }
        if (written == null || written.second < UNIT) return null
        var best = Double.POSITIVE_INFINITY
        val stems = LinkedHashSet<String>()
        var n = 0
        while (n <= END && n < written.first.length) {
            val stem = written.first.substring(0, written.first.length - n)
            if (stem.length > n) stems.add(stem)
            n++
        }
        for (stem in stems) for (alt in byStem[stem] ?: emptyList()) {
            if (own.contains(alt)) continue
            val fromWritten = residual(written.first, alt)
            if (fromWritten == null || endSegment(fromWritten.sa, fromWritten.sb) == null) continue
            val fromHeard = residual(alt, heard) ?: continue
            best = minOf(best, endingCost(fromHeard.stem, fromHeard.sa, fromHeard.sb))
        }
        // The other form has to beat the written word by a consonant, not by a cheaper way to spell the same tail.
        if (written.second - best < UNIT) return null
        val difference = residual(written.first, heard)
        return difference?.let { EndingResult(consonantsOf(it.sb).isNotEmpty()) }
    }

    /**
     * Attested pronunciations reached by dropping a consonant-bearing ending from this word. A pause or a held vowel
     * drops no consonant, so waqf and اذهبا→اذهب are not here. The raw strings keep madd and ghunna runs; the checker
     * aligns them with the next word so one heard consonant cannot pay for both.
     */
    fun droppedSuffixes(wi: Int): List<String> {
        val written = lexForm(phonemes.substring(words[wi].start, words[wi].end))
        val own = forms[wi]
        if (written.isEmpty() || own.isEmpty()) return emptyList()
        val stems = LinkedHashSet<String>()
        var n = 0
        while (n <= END && n < written.length) {
            val stem = written.substring(0, written.length - n)
            if (stem.length > n) stems.add(stem)
            n++
        }
        val out = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        for (stem in stems) for (alt in byStem[stem] ?: emptyList()) {
            if (own.contains(alt) || alt.length >= written.length) continue
            val r = residual(written, alt)
            if (r == null || consonantsOf(r.sa).isEmpty() || consonantsOf(r.sb).isNotEmpty()) continue
            for (raw in rawByForm[alt] ?: emptyList()) {
                if (seen.contains(raw)) continue
                seen.add(raw)
                out.add(raw)
            }
        }
        return out
    }

    private fun nearIds(wi: Int): List<Int> {
        val before = ArrayList<Int>()
        val after = ArrayList<Int>()
        var i = wi - 1
        while (i >= 0 && before.size < 3) {
            if (words[i].start != words[i].end) before.add(i)
            i--
        }
        i = wi + 1
        while (i < words.size && after.size < 3) {
            if (words[i].start != words[i].end) after.add(i)
            i++
        }
        val ids = before.reversed() + wi + after
        return if (words[wi].start == words[wi].end) ids.filter { it != wi } else ids
    }

    // Same consonants, and vowels only where the sounds differ. A shorter string is not that word.
    private fun same(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            if (LEX_CONSONANTS.contains(a[i]) || LEX_CONSONANTS.contains(b[i]) || subCost(a[i], b[i]) >= UNIT) return false
        }
        return true
    }

    /**
     * The span is a word, a prefix, or a contiguous run of words within ±3 of `wi`. Restarts and false starts
     * spell that neighbourhood; an added word does not.
     */
    fun explainsSeam(wi: Int, heard: String, fragments: Boolean = false): Boolean {
        val n = norm(heard)
        if (n.isEmpty()) return true
        val ids = nearIds(wi)
        val lists = ids.map { i ->
            if (fragments) {
                (wordNorms[i] + norm(isolatedForms.getOrNull(i) ?: "")).filter { it.isNotEmpty() }.distinct()
            } else wordNorms[i]
        }
        val matches = { f: String ->
            if (fragments) {
                var hit = false
                var a = 0
                while (a + n.length <= f.length) {
                    if (same(n, f.substring(a, a + n.length))) {
                        hit = true
                        break
                    }
                    a++
                }
                hit
            } else {
                same(n, f) || (n.length < f.length && same(n, f.substring(0, n.length)))
            }
        }
        for (list in lists) for (f in list) if (matches(f)) return true
        for (a in ids.indices) {
            var combos = listOf("")
            var b = a
            while (b < ids.size && b < a + 4) {
                val next = ArrayList<String>()
                for (c in combos) for (f in lists[b]) next.add(c + f)
                val uniq = next.distinct()
                if (b > a) for (acc in uniq) {
                    if (matches(acc)) return true
                }
                combos = uniq.take(8)
                b++
            }
        }
        return false
    }

    /**
     * A whole mushaf word of at least two consonants, equal under soft costs only: no hard consonant edit and no
     * dropped or added phone. Null when the span is a neighbour, a neighbour prefix, or a run within ±3.
     */
    fun surplusWord(wi: Int, heard: String): String? {
        val n = norm(heard)
        val sk = skel(n)
        if (sk.length < 2) return null
        for (f in bySkel[sk] ?: emptyList()) if (same(n, f)) return if (explainsSeam(wi, heard)) null else f
        return null
    }

    /**
     * A substring of the nearby text can leave enough of its word to beat total omission.
     * Excision must not call that fragment surplus, even when it is itself an attested word.
     */
    fun fragmentOfSeam(wi: Int, heard: String): Boolean = explainsSeam(wi, heard, true)

    fun alternative(wi: Int, heard: String, context: LexicalContext? = null): Boolean {
        val normalized = lexForm(heard)
        val boundary = { text: String, before: Boolean ->
            if (before) lexForm(text).lastOrNull() else lexForm(text).firstOrNull()
        }
        val key = if (context == null) null else {
            // contextKey renders an undefined side as ^/$, including a boundary of an empty form.
            val b = context.before?.let { boundary(it, true) } ?: '^'
            val a = context.after?.let { boundary(it, false) } ?: '$'
            "$b|$a"
        }
        val known = isolated.contains(normalized) ||
            (key != null && connected[normalized]?.contains(key) == true)
        return known && !matches(wi, heard) &&
            forms[wi].isNotEmpty() && forms[wi].all { hardConsonantEdits(it, normalized) >= 2 }
    }
}

private val lexicalWordsCache = java.util.WeakHashMap<ReferenceIndex, LexicalWords>()

fun lexicalWords(index: ReferenceIndex): LexicalWords {
    return lexicalWordsCache.getOrPut(index) { LexicalWords(index) }
}
