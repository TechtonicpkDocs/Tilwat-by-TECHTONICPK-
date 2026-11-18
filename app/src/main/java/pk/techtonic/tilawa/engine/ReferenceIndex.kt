// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/** One ayah as stored in ref-v2.json. `w` is Uthmani word text, `p` connected phonemes, `o` word offsets, `iso` pausal forms. */
data class RefAyah(
    val s: Int,
    val a: Int,
    val w: List<String>,
    val p: String,
    val o: List<Int>,
    val iso: List<String>,
)

data class ReferenceAsset(
    val version: String,
    val inventoryId: String,
    val tokens: String,
    val ayahs: List<RefAyah>,
)

/** Non-Quran phrases said around a recitation (isti'adha, takbir, amin...), phonemized with the reference profile. */
data class Phrase(val name: String, val text: String, val p: String)

data class PhraseAsset(val version: String, val phrases: List<Phrase>)

data class AyahRef(val surah: Int, val ayah: Int)

/** Madani (Hafs, 604-page) mushaf page boundaries: `pages[n - 1]` is `[fromSurah, fromAyah, toSurah, toAyah]` of page n. */
data class PageAsset(
    val version: String,
    val source: String,
    val sourceSha256: String,
    val pages: List<List<Int>>,
)

fun pageRange(asset: PageAsset, page: Int): Pair<AyahRef, AyahRef> {
    val p = asset.pages.getOrNull(page - 1) ?: throw IllegalArgumentException("No Madani page $page")
    return Pair(AyahRef(p[0], p[1]), AyahRef(p[2], p[3]))
}

data class ReferenceWord(
    val surah: Int,
    val ayah: Int,
    /** 1-BASED word number within the ayah (upstream convention; the goldens use it). */
    val word: Int,
    /** Half-open range in `ReferenceIndex.phonemes`. Empty when fully assimilated into a neighbour. */
    val start: Int,
    val end: Int,
) {
    fun ref(): WordRef = WordRef(surah, ayah, word)
}

fun parseReferenceAsset(json: String): ReferenceAsset {
    val root = Json.parse(json).asObject()
    val inv = root.obj("inventory")
    val ayahs = root.arr("ayahs").objects().map { a ->
        RefAyah(
            s = a.int("s"),
            a = a.int("a"),
            w = a.arr("w").strings(),
            p = a.str("p"),
            o = a.arr("o").ints(),
            iso = a.arr("iso").strings(),
        )
    }
    return ReferenceAsset(root.str("version"), inv.str("id"), inv.str("tokens"), ayahs)
}

fun parsePhraseAsset(json: String): PhraseAsset {
    val root = Json.parse(json).asObject()
    return PhraseAsset(
        root.str("version"),
        root.arr("phrases").objects().map { p -> Phrase(p.str("name"), p.str("text"), p.str("p")) },
    )
}

/**
 * The connected form opened the way the word is said after a pause. The previous word's assimilated ending doubles
 * this word's first consonant in connected speech (وجوهٌ يومئذ: يييَومَءِذِن); a reader who stops between the two
 * starts it plainly, with the rest still joined to what follows (يَومَءِذِن). The isolated form keeps the plain onset.
 */
private fun plainOnset(connected: String, iso: String?): String? {
    if (connected.isEmpty() || iso.isNullOrEmpty() || connected[0] != iso[0]) return null
    val run = { s: String ->
        var n = 1
        while (n < s.length && s[n] == s[0]) n++
        n
    }
    val extra = run(connected) - run(iso)
    return if (extra > 0) connected.substring(extra) else null
}

const val GRAM_SIZE = 4

/**
 * Pure-Kotlin reference index (no Android imports): every ayah's continuous phonemes concatenated in mushaf order,
 * word offsets, deduplicated word forms, and the 4-gram index the tracker votes with.
 * `QuranReference` (Android) subclasses this and loads the asset JSON from the app's assets.
 */
open class ReferenceIndex(asset: ReferenceAsset, phrases: List<Phrase> = emptyList()) {
    val version: String = asset.version
    val inventoryId: String = asset.inventoryId
    val tokens: Set<Char> = asset.tokens.toSet()
    /** Every ayah's continuous phonemes, concatenated in mushaf order. */
    val phonemes: String
    val words: List<ReferenceWord>
    /** Supported connected and isolated pronunciations, in word order. */
    val wordForms: List<List<String>>
    /** Context-free pause pronunciations, kept separate from connected forms. */
    val isolatedWordForms: List<String>
    /** Mushaf word text, in word order. Letter identity for parallel phrases. */
    val wordText: List<String>
    val gramSize: Int = GRAM_SIZE
    val grams: Map<String, List<Int>>
    val phraseList: List<Phrase> = phrases

    private val starts: IntArray
    private val ayahByKey: Map<Pair<Int, Int>, RefAyah>

    init {
        val wordsMut = ArrayList<ReferenceWord>(77433)
        val wordFormsMut = ArrayList<List<String>>(77433)
        val isolatedMut = ArrayList<String>(77433)
        val wordTextMut = ArrayList<String>(77433)
        val phonemesBuilder = StringBuilder(643312)
        val ayahMap = LinkedHashMap<Pair<Int, Int>, RefAyah>()
        for (ayah in asset.ayahs) {
            ayahMap[Pair(ayah.s, ayah.a)] = ayah
            val base = phonemesBuilder.length
            ayah.w.forEachIndexed { i, text ->
                val end = if (i + 1 < ayah.o.size) ayah.o[i + 1] else ayah.p.length
                wordsMut.add(ReferenceWord(ayah.s, ayah.a, i + 1, base + ayah.o[i], base + end))
                val connected = ayah.p.substring(ayah.o[i], end)
                val forms = linkedSetOf<String>()
                if (connected.isNotEmpty()) forms.add(connected)
                val iso = ayah.iso[i]
                if (iso.isNotEmpty()) forms.add(iso)
                val plain = plainOnset(connected, iso)
                if (!plain.isNullOrEmpty()) forms.add(plain)
                wordFormsMut.add(forms.toList())
                isolatedMut.add(iso)
                wordTextMut.add(text)
            }
            phonemesBuilder.append(ayah.p)
        }
        phonemes = phonemesBuilder.toString()
        words = wordsMut
        wordForms = wordFormsMut
        isolatedWordForms = isolatedMut
        wordText = wordTextMut
        ayahByKey = ayahMap

        val gramsMut = LinkedHashMap<String, MutableList<Int>>()
        var i = 0
        while (i + GRAM_SIZE <= phonemes.length) {
            val g = phonemes.substring(i, i + GRAM_SIZE)
            gramsMut.getOrPut(g) { ArrayList() }.add(i)
            i++
        }
        grams = gramsMut

        starts = IntArray(words.size) { words[it].start }
    }

    /** Index into `words` of the word covering a phoneme offset. */
    fun wordAt(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Uthmani word texts of one ayah, in order (empty when the ayah does not exist). Used by the UI. */
    fun ayahWords(surah: Int, ayah: Int): List<String> = ayahByKey[Pair(surah, ayah)]?.w ?: emptyList()

    /** Number of words in one ayah (0 when the ayah does not exist). */
    fun ayahWordCount(surah: Int, ayah: Int): Int = ayahByKey[Pair(surah, ayah)]?.w?.size ?: 0
}
