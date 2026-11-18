// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

private val formulaSlotsCache = java.util.WeakHashMap<ReferenceIndex, Map<Int, List<String>>>()

/**
 * Pausal fillers of an ayah's final word after the identical two-word lead. The ayah boundary closes
 * the choice. Phones come from final occurrences, where the reference already has the pausal ending.
 * Mid-ayah words and indexes without mushaf text have no slots.
 */
fun formulaSlots(index: ReferenceIndex): Map<Int, List<String>> {
    formulaSlotsCache[index]?.let { return it }
    val slots = LinkedHashMap<Int, List<String>>()
    val text = index.wordText
    if (text.size != index.words.size) {
        formulaSlotsCache[index] = slots
        return slots
    }
    val skel = text.map { letterSkeleton(it) }
    val groups = LinkedHashMap<String, MutableList<Int>>()
    for (i in 2 until index.words.size) {
        val word = index.words[i]
        val next = index.words.getOrNull(i + 1)
        if (next != null && next.surah == word.surah && next.ayah == word.ayah) continue
        val lead = index.words[i - 2]
        if (lead.surah != word.surah || lead.ayah != word.ayah) continue
        if (index.words.subList(i - 2, i + 1).any { it.start == it.end }) continue
        val key = skel.subList(i - 2, i).joinToString("\u0000")
        groups.getOrPut(key) { ArrayList() }.add(i)
    }
    for (group in groups.values) for (i in group) {
        val forms = LinkedHashSet<String>()
        for (j in group) {
            if (skel[i] == skel[j]) continue
            val w = index.words[j]
            forms.add(index.phonemes.substring(w.start, w.end))
        }
        if (forms.isNotEmpty()) slots[i] = forms.toList()
    }
    formulaSlotsCache[index] = slots
    return slots
}
