// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.floor

/**
 * Streaming phoneme listener: fbank, then the streaming Zipformer one window at a time, then a greedy CTC decode.
 * A chunk covers every frame decoded so far. A token run cut by a window's end is emitted with that window, its end
 * frame where the window ended.
 *
 * Synchronous Kotlin (the TS is async only because the ONNX call is a promise; [OrtRunner.runStep] is blocking).
 */
class Listener(
    private val runner: OrtRunner,
    private val manifest: ModelManifest,
    /** Attach per-frame top-k posteriors to each chunk: the checker's local-window check reads them. */
    private val posteriors: Boolean = false,
    private val topK: Int = 5,
) {
    private val fbank = Fbank()
    private val vocab: Int = manifest.tokens.length + 1
    private var resampler: Resampler? = null
    private var states: MutableMap<String, FloatArray> = manifest.states
        .filter { it.dtype != "int64" }
        .associate { it.name to FloatArray(it.numel()) }
        .toMutableMap()
    private var processedLens: Long = 0L
    private var feats: MutableList<FloatArray> = ArrayList()
    private var consumed = 0
    private var fbankFrames = 0
    /** Output frames decoded so far, and the first frame not yet emitted. */
    private var outFrames = 0
    private var emitted = 0
    private var prev = 0
    private var open: DecodedPhoneme? = null
    /** The run at the end of the previous window was emitted; its continuation is the same phoneme. */
    private var carried = false
    private var done: MutableList<DecodedPhoneme> = ArrayList()
    private var ids: MutableList<Int> = ArrayList()
    private var probs: MutableList<Double> = ArrayList()

    /** Fbank frames consumed so far; audio time is `frames * 10 ms`. */
    val frames: Int get() = fbankFrames

    /** Output frames before this one are final: every phoneme starting earlier has been returned. */
    val decoded: Int get() = emitted

    private fun step(window: List<FloatArray>, keep: Int) {
        val x = FloatArray(manifest.window * manifest.fbankMels)
        window.forEachIndexed { i, f -> f.copyInto(x, i * manifest.fbankMels) }
        val out = runner.runStep(x, states, processedLens)
        states = out.newStates.toMutableMap()
        processedLens = out.newProcessedLens
        val lp = out.logProbs
        val n = minOf(keep, lp.size / vocab)
        for (t in 0 until n) {
            val row = lp.copyOfRange(t * vocab, (t + 1) * vocab)
            val top = topK(row, topK)
            if (posteriors) for ((id, p) in top) {
                ids.add(id)
                probs.add(round4(p))
            }
            val (id, p) = top[0]
            val frame = outFrames + t
            val cur = open
            if (id != 0 && id == prev && cur != null) {
                cur.end = frame
                cur.conf = maxOf(cur.conf, round4(p))
            } else if (!(id != 0 && id == prev && carried)) {
                if (cur != null) done.add(cur)
                open = if (id != 0) DecodedPhoneme(manifest.tokens[id - 1], frame, frame, round4(p)) else null
                carried = false
            }
            if (id != prev) carried = false
            prev = id
        }
        outFrames += n
        // A run still open at the window's end goes out with it: continuing it in the next window can't change the token.
        val cur = open
        if (cur != null) {
            done.add(cur)
            open = null
            carried = true
        }
    }

    private fun flush(final: Boolean): PhonemeEvidence? {
        if (final) {
            val cur = open
            if (cur != null) {
                done.add(cur)
                open = null
            }
        }
        val until = open?.start ?: outFrames
        if (until <= emitted && done.isEmpty()) return null
        val phonemes = done
        var post: TopKPosteriors? = null
        if (posteriors) {
            val n = until - emitted
            post = TopKPosteriors(
                k = topK,
                startFrame = emitted,
                nFrames = n,
                ids = ids.subList(0, n * topK).toIntArray(),
                probs = probs.subList(0, n * topK).toDoubleArray(),
            )
            ids = ArrayList(ids.subList(n * topK, ids.size))
            probs = ArrayList(probs.subList(n * topK, probs.size))
        }
        done = ArrayList()
        emitted = until
        return PhonemeEvidence(manifest.inventory, manifest.frameMs, phonemes, post)
    }

    private fun outputsFor(frames: Int): Int =
        floor((floor((frames - 7) / 2.0) + 1) / 2.0).toInt()

    private fun drain() {
        while (feats.size - consumed >= manifest.window) {
            step(feats.subList(consumed, consumed + manifest.window), Int.MAX_VALUE)
            consumed += manifest.shift
        }
        if (consumed > 4 * manifest.window) {
            feats = ArrayList(feats.subList(consumed, feats.size))
            consumed = 0
        }
    }

    /** Audio at any sample rate; returns the evidence that became final, or null when none did. */
    fun push(samples: FloatArray, sampleRate: Int): PhonemeEvidence? {
        var s = samples
        if (sampleRate != manifest.sampleRate) {
            val r = resampler
            if (r == null || r.from != sampleRate) resampler = Resampler(sampleRate, manifest.sampleRate)
            s = resampler!!.push(s)
        }
        val f = fbank.push(s)
        fbankFrames += f.size
        feats.addAll(f)
        drain()
        return flush(false)
    }

    /** Pads and drains the stream: the rest of the evidence. */
    fun end(): PhonemeEvidence? {
        val r = resampler
        if (r != null) {
            val f = fbank.push(r.flush())
            fbankFrames += f.size
            feats.addAll(f)
        }
        val total = outputsFor(fbankFrames)
        val pad = FloatArray(manifest.fbankMels) { manifest.fbankPadValue.toFloat() }
        while (outFrames < total) {
            while (feats.size - consumed < manifest.window) feats.add(pad)
            step(feats.subList(consumed, consumed + manifest.window), total - outFrames)
            consumed += manifest.shift
        }
        return flush(true)
    }
}
