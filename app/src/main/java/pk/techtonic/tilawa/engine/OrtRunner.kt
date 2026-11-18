// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * One streaming model step: the pure engine talks ONLY to this interface; `data.OnnxSession` implements it with
 * ONNX Runtime. `features` is one window of fbank frames, flat row-major `[window x mels]` (45x80 for the shipped
 * model). `states` holds the float32 cached tensors by manifest name; `processedLens` is the int64 counter, kept
 * separate because it is not a float array. The result carries the new states plus the new counter, fed back each step.
 *
 * NOTE: `newProcessedLens` is an addition to the brief's `OrtStepResult(logProbs, newStates)` shape — the model
 * returns `new_processed_lens` and the listener must feed it back every step; dropping it would break the stream.
 */
interface OrtRunner {
    fun runStep(
        features: FloatArray,
        states: Map<String, FloatArray>,
        processedLens: Long,
    ): OrtStepResult
}

data class OrtStepResult(
    /** Flat log-probabilities, row-major `[frames x vocab]` (8x43 for the shipped model, blank=0). */
    val logProbs: FloatArray,
    /** New float32 states, by manifest name. */
    val newStates: Map<String, FloatArray>,
    /** New `processed_lens` counter, fed back on the next step. */
    val newProcessedLens: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrtStepResult) return false
        return logProbs.contentEquals(other.logProbs) && newStates == other.newStates &&
            newProcessedLens == other.newProcessedLens
    }

    override fun hashCode(): Int {
        var result = logProbs.contentHashCode()
        result = 31 * result + newStates.hashCode()
        result = 31 * result + newProcessedLens.hashCode()
        return result
    }
}
