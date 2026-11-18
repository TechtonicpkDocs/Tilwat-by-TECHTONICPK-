// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

val MEMORIZATION_CLASSES: List<ErrorClass> = listOf("wrong_word", "omitted_word", "added_word", "skipped_passage")

val ALL_CLASSES: List<ErrorClass> = MEMORIZATION_CLASSES + listOf(
    "short_vowel",
    "consonant_h_ha",
    "consonant_ain_hamza",
    "consonant_sad_sin",
    "consonant_dad_dal",
    "consonant_ta_ta",
    "consonant_qaf_kaf",
    "consonant_dhal_zay",
    "consonant_tha_sin",
    "missing_shadda",
    "madd_natural",
)

data class CapabilityLists(val supported: List<ErrorClass>, val unsupported: List<ErrorClass>)

/** Every class the model's metadata doesn't list as supported is `unsupported`, so v1 can ship memorization-only. */
fun capabilities(supported: List<ErrorClass>): CapabilityLists {
    val s = supported.toSet()
    return CapabilityLists(ALL_CLASSES.filter { s.contains(it) }, ALL_CLASSES.filter { !s.contains(it) })
}
