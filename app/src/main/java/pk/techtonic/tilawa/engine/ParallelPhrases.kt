// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * Nats by which an attested parallel phrase must beat the written phrase on the CTC forward sum.
 * Correct unspliced surah-37 recitations by four readers outside the lab held-out set, decoded with the
 * shipped listener, never preferred the parallel (175 phrases, worst −8.0 nats). On phone audio the same
 * contrast moves up: fully clean practice spans stay negative (worst −0.7), and the worst correct span
 * away from a planted mistake is +2.9. Four nats sits above that tail and below the planted swaps.
 * Not a per-edit cost. One switch, per phrase.
 */
const val PHRASE_SWITCH = 4.0

/**
 * Mean probability of the best non-blank token, on frames the blank does not own, below which the window is
 * not a heard phrase. A flat inventory sits near 0.02; 0.1 is a few times that.
 */
const val PHRASE_PEAK = 0.1

/** Four words is the shortest phrase that still enters and leaves on a shared word around one interior difference. */
private const val PHRASE = 4

/**
 * Vowels, madd and marks. Everything else in the inventory is a consonant, including the tajweed variants
 * (ڇ ں ۾) the checker's plain consonant set does not list. A parallel that keeps the same consonants is a
 * vowel or waqf ending, which the phrase graph does not switch.
 */
private val MARKS = setOf('َ', 'ُ', 'ِ', '۪', 'ۦ', 'ۥ', 'ا', 'ـ', 'ۜ', 'ؙ')

private fun isDiacritic(c: Char): Boolean =
    c == '\u0640' || c in '\u064B'..'\u065F' || c == '\u0670' || c in '\u06D6'..'\u06ED'

/** Letter skeleton of a mushaf word: diacritics off, alef and alif maqsura folded. */
fun letterSkeleton(word: String): String {
    val sb = StringBuilder(word.length)
    for (c in word) {
        if (isDiacritic(c)) continue
        when (c) {
            'ى' -> sb.append('ي')
            'ٱ', 'أ', 'إ', 'آ' -> sb.append('ا')
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun consonants(phonemes: String): String {
    val sb = StringBuilder()
    for (c in phonemes) if (!MARKS.contains(c)) sb.append(c)
    return sb.toString()
}

/**
 * One attested substitute for the written phrase: the shared words keep the phonemes written here, and the
 * interior word takes the phonemes of a parallel occurrence. No inserted or restarted speech, only that word.
 */
data class ParallelPath(
    /** Word index of the phrase's first word. */
    val start: Int,
    /** Word index of the interior word that differs. */
    val word: Int,
    /** Phonemes of the four written words. */
    val written: String,
    /** The same phrase with `word` replaced by the parallel word's phonemes. */
    val alt: String,
    /** Phonemes of the parallel word alone. */
    val altWord: String,
)

private val parallelPathsCache = java.util.WeakHashMap<ReferenceIndex, Map<Int, List<ParallelPath>>>()

/**
 * Every 4-word mushaf phrase whose interior word has another attested completion, keyed by that word.
 * Longer phrases with one interior difference contain one of these: the three shared words are a subset.
 * Paths do not cross a surah. Built once per index.
 */
fun parallelPaths(index: ReferenceIndex): Map<Int, List<ParallelPath>> {
    parallelPathsCache[index]?.let { return it }
    val text = index.wordText
    val map = LinkedHashMap<Int, MutableList<ParallelPath>>()
    if (text.size != index.words.size) {
        parallelPathsCache[index] = map
        return map
    }
    val skel = text.map { letterSkeleton(it) }
    val phone = index.words.map { w -> index.phonemes.substring(w.start, w.end) }
    val groups = LinkedHashMap<String, MutableList<Int>>()
    for (i in 0..index.words.size - PHRASE) {
        if (index.words[i].surah != index.words[i + PHRASE - 1].surah) continue
        if (phone.subList(i, i + PHRASE).any { it.isEmpty() }) continue
        for (j in 1 until PHRASE - 1) {
            val key = "$j\u0000${skel.subList(i, i + j).joinToString("\u0000")}\u0000|\u0000${skel.subList(i + j + 1, i + PHRASE).joinToString("\u0000")}"
            groups.getOrPut(key) { ArrayList() }.add(i)
        }
    }
    val seen = LinkedHashSet<String>()
    for ((key, starts) in groups) {
        val j = key.substring(0, key.indexOf('\u0000')).toInt()
        val by = LinkedHashMap<String, LinkedHashSet<String>>()
        for (s in starts) {
            val k = skel[s + j]
            by.getOrPut(k) { LinkedHashSet() }.add(phone[s + j])
        }
        if (by.size < 2) continue
        for (s in starts) {
            val mine = consonants(phone[s + j])
            val written = phone.subList(s, s + PHRASE).joinToString("")
            for ((k, forms) in by) {
                if (k == skel[s + j]) continue
                for (altWord in forms) {
                    if (consonants(altWord) == mine || altWord == phone[s + j]) continue
                    val id = "$s\u0000$j\u0000$altWord"
                    if (seen.contains(id)) continue
                    seen.add(id)
                    val alt = phone.subList(s, s + j).joinToString("") + altWord +
                        phone.subList(s + j + 1, s + PHRASE).joinToString("")
                    val path = ParallelPath(s, s + j, written, alt, altWord)
                    map.getOrPut(path.word) { ArrayList() }.add(path)
                }
            }
        }
    }
    parallelPathsCache[index] = map
    return map
}
