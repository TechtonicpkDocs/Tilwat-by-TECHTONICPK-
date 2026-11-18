// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import android.content.Context

/**
 * Android-facing reference loader: reads ref-v2.json and phrases-v1.json from the app's assets and builds the pure
 * [ReferenceIndex]. Constructor takes a Context (UI contract: `QuranReference(context)`).
 *
 * NOTE: this is the ONE file in engine/ that imports android.* — it is the asset loader, not engine logic, and it is
 * excluded from the standalone kotlinc compile (which covers the pure engine). CI compiles it with android.jar.
 * All engine logic works against [ReferenceIndex], which this class extends.
 */
class QuranReference : ReferenceIndex {
    constructor(context: Context) : this(loadFromAssets(context))

    private constructor(loaded: LoadedReference) : super(loaded.asset, loaded.phrases)

    private class LoadedReference(val asset: ReferenceAsset, val phrases: List<Phrase>)

    companion object {
        private const val REF_ASSET = "ref-v2.json"
        private const val PHRASES_ASSET = "phrases-v1.json"

        private fun loadFromAssets(context: Context): LoadedReference {
            val refJson = context.assets.open(REF_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val phrasesJson = context.assets.open(PHRASES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return LoadedReference(parseReferenceAsset(refJson), parsePhraseAsset(phrasesJson).phrases)
        }
    }
}
