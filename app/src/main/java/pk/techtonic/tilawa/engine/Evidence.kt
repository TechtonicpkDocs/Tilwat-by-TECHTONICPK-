// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/** Auxiliary listener judgment over a complete seam, tied to one proposed spelling. */
data class SeamInsertionEvidence(
    val word: WordRef,
    val observed: String,
    val fromFrame: Int,
    val toFrame: Int,
    val marginal: Double,
)

data class DecodedPhoneme(
    /** One token from the evidence inventory. */
    val p: Char,
    /** First and last frame (inclusive) of the token's CTC spike run. Mutable: the listener extends a run in place. */
    var start: Int,
    var end: Int,
    var conf: Double,
    /** Retained by token-preserving evidence serialization and rechunking. */
    val seamInsertions: List<SeamInsertionEvidence>? = null,
)

/** Per-frame top-k phoneme posteriors. See docs/evidence-contract.md. */
data class TopKPosteriors(
    val k: Int,
    /** Absolute frame index of the first row. */
    val startFrame: Int,
    val nFrames: Int,
    /** Row-major nFrames x k token ids; 0 is the CTC blank, 1..N index the inventory tokens. */
    val ids: IntArray,
    /** Row-major nFrames x k probabilities, descending within a row. */
    val probs: DoubleArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TopKPosteriors) return false
        return k == other.k && startFrame == other.startFrame && nFrames == other.nFrames &&
            ids.contentEquals(other.ids) && probs.contentEquals(other.probs)
    }

    override fun hashCode(): Int {
        var result = k
        result = 31 * result + startFrame
        result = 31 * result + nFrames
        result = 31 * result + ids.contentHashCode()
        result = 31 * result + probs.contentHashCode()
        return result
    }
}

data class PhonemeEvidence(
    /** Must equal the reference index inventory id. */
    val inventory: String,
    val frameMs: Int,
    val phonemes: List<DecodedPhoneme>,
    val posteriors: TopKPosteriors? = null,
    /** Frames before this one are final: no later evidence has a phoneme starting earlier. Optional; streams set it. */
    val decodedTo: Int? = null,
)

class EvidenceError(message: String) : Exception(message)
