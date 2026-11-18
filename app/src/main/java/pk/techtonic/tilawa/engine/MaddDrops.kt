// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/** Madd letters in the reference inventory. A run of these at a word end is the long vowel. */
private val MADD = setOf('ا', 'ۦ', 'ۥ')
private val MADD_CONSONANTS = setOf(
    'ء', 'ب', 'ت', 'ث', 'ج', 'ح', 'خ', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ',
    'ع', 'غ', 'ف', 'ق', 'ك', 'ل', 'م', 'ن', 'ه', 'و', 'ي',
)

fun isMaddLetter(p: Char): Boolean = MADD.contains(p)

private fun trail(phonemes: String): Int {
    var n = 0
    while (n < phonemes.length && MADD.contains(phonemes[phonemes.length - 1 - n])) n++
    return n
}

/**
 * Last consonant of the word once its final madd run is set aside. The duration of a connected madd is measured
 * from this consonant's onset to the next word's hamza.
 */
fun stemConsonant(phonemes: String, start: Int, end: Int): Int {
    var i = end - 1
    while (i >= start && MADD.contains(phonemes[i])) i--
    while (i >= start && !MADD_CONSONANTS.contains(phonemes[i])) i--
    return i
}

data class MaddDrop(
    /** The hamza-initial word that follows. */
    val next: Int,
    /** Phonemes of the shorter word at the parallel ayah. */
    val alt: String,
)

data class MaddSites(
    /** Word index to the hamza word after it. Connected madd munfasil, from the written phonemes. */
    val munfasil: Map<Int, Int>,
    /**
     * Munfasil words whose final long vowel, dropped, is another mushaf word, and whose 4-word phrase with that
     * word occurs at another ayah. The parallel word itself does not end in a madd: a second spelling of the same
     * long vowel is not the shorter form.
     */
    val drops: Map<Int, MaddDrop>,
)

private val maddSitesCache = java.util.WeakHashMap<ReferenceIndex, MaddSites>()

fun maddSites(index: ReferenceIndex): MaddSites {
    maddSitesCache[index]?.let { return it }
    val text = index.wordText
    val empty = MaddSites(emptyMap(), emptyMap())
    if (text.size != index.words.size) {
        maddSitesCache[index] = empty
        return empty
    }
    val skel = text.map { letterSkeleton(it) }
    val phone = index.words.map { w -> index.phonemes.substring(w.start, w.end) }
    val attested = skel.toSet()
    val grams = LinkedHashMap<String, MutableList<Int>>()
    for (i in 0..index.words.size - 4) {
        if (index.words[i].surah != index.words[i + 3].surah) continue
        val key = skel.subList(i, i + 4).joinToString("\u0000")
        grams.getOrPut(key) { ArrayList() }.add(i)
    }
    val munfasil = LinkedHashMap<Int, Int>()
    val drops = LinkedHashMap<Int, MaddDrop>()
    for (i in 0 until index.words.size - 1) {
        val ph = phone[i]
        if (trail(ph) == 0) continue
        val w = index.words[i]
        val n = index.words[i + 1]
        if (n.surah != w.surah || n.ayah != w.ayah || !phone[i + 1].startsWith('ء')) continue
        munfasil[i] = i + 1
        val sk = skel[i]
        val last = sk.lastOrNull()
        if (last == null || last !in "اوي") continue
        val short = sk.substring(0, sk.length - 1)
        if (short.isEmpty() || !attested.contains(short)) continue
        if (i + 3 >= index.words.size || index.words[i + 3].surah != w.surah) continue
        val key = listOf(short) + skel.subList(i + 1, i + 4)
        val keyStr = key.joinToString("\u0000")
        var alt = ""
        for (j in grams[keyStr] ?: emptyList()) {
            val there = index.words[j]
            if (there.surah == w.surah && there.ayah == w.ayah) continue
            val altPh = phone[j]
            if (altPh.isEmpty() || trail(altPh) != 0) continue
            alt = altPh
            break
        }
        if (alt.isNotEmpty()) drops[i] = MaddDrop(i + 1, alt)
    }
    val sites = MaddSites(munfasil, drops)
    maddSitesCache[index] = sites
    return sites
}
