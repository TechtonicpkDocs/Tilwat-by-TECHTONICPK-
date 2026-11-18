// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * The session: the research engine from `runtime/session.ts` (evidence grid, tracker/checker fan-out,
 * recorded evidence, context-free gap splice at the end) plus the public lifecycle wrapper from
 * `src/session.ts` (state machine, subscribe with capabilities first, audio tap, `now()`).
 *
 * The TypeScript is async only because the ONNX call is a promise; [OrtRunner.runStep] is blocking, so the
 * Kotlin session is fully synchronous: `pushAudio`/`end` return their events directly and there is no job
 * queue (`idle()` is a no-op). Calls are serial on the calling thread, as the TS serial queue made them.
 *
 * Two deliberate deviations from the TS wrapper, both documented here:
 * - Subscriber errors: TS rethrows them outside the engine loop via `queueMicrotask`. There is no safe
 *   equivalent on Android (an uncaught exception on any thread kills the app), so a throwing subscriber or
 *   audio tap is swallowed here instead of crashing the session.
 * - `declare()` throws [IllegalArgumentException] where TS throws a RangeError (Kotlin has no RangeError).
 *
 * The insertion teacher is not shipped (spec §6.9): `addInsertionEvidence` is a no-op and the auxiliary
 * teacher replay at `end()` is omitted, so `auxiliaryMistakes` stays empty. The context-free gap splice
 * ([spliceContextFreeGaps]) IS ported — it runs at `end()` when evidence is recorded and more than one lock
 * was made, and can change the recorded evidence (never the live events).
 *
 * Word indices are 1-based throughout the engine, exactly as upstream (see the note on [WordRef] in
 * Events.kt). The [TilawaEngine] facade converts to 0-based for the UI.
 */

/** Before the first lock, the tracker runs global search once per this much audio, on a fixed grid. */
const val ACQUIRE_MS = 1000

/** Session policy as the caller states it; every field is optional and resolved by [resolvePolicy]. */
data class SessionPolicy(
    /** Whether resolved errors (self-corrections, recovered skips) count as mistakes. Default false. */
    val countSelfCorrections: Boolean? = null,
    /** When a suspected error is confirmed. Default 2.5 s after the reciter passes the word. */
    val confirm: ConfirmPolicy? = null,
    /** Mean decoder confidence below which an error ends `uncertain`, never `confirmed`. Default 0.6. */
    val minConfidence: Double? = null,
    /** Evidence cell width in ms. Default is the model's frame width. */
    val evidenceMs: Double? = null,
)

/** The passage a memorization session is declared on, as the caller states it. */
data class MemorizationPassage(
    val surah: Int,
    val fromAyah: Int,
    val toAyah: Int,
)

/** A declared passage with its first and last non-empty words resolved. */
data class DeclaredPassage(
    val surah: Int,
    val fromAyah: Int,
    val toAyah: Int,
    val from: WordRef,
    val to: WordRef,
)

/** A session policy with every field filled in. */
data class ResolvedSessionPolicy(
    val countSelfCorrections: Boolean,
    val confirm: ConfirmPolicy,
    val minConfidence: Double,
    val evidenceMs: Double,
)

/** Fills a [SessionPolicy] with the defaults from `src/defaults.ts`. */
fun resolvePolicy(policy: SessionPolicy, frameMs: Int): ResolvedSessionPolicy = ResolvedSessionPolicy(
    countSelfCorrections = policy.countSelfCorrections ?: false,
    confirm = policy.confirm ?: ConfirmPolicy.Delay(2500.0),
    minConfidence = policy.minConfidence ?: 0.6,
    evidenceMs = policy.evidenceMs ?: frameMs.toDouble(),
)

/**
 * Re-chunks streaming evidence onto fixed [ms] cells by phoneme start, slicing the posteriors to match.
 * A cell is ready once a phoneme from a later cell arrives, or decoding has passed its end.
 */
class EvidenceGrid(private val ms: Double) {
    private var held = ArrayList<DecodedPhoneme>()
    private var frameMs = 0
    private var inventory = ""
    private var post: PosteriorsBuffer? = null

    private fun cellOf(frame: Int): Int = floor((frame * frameMs).toDouble() / ms).toInt()

    private fun cut(n: Int, until: Int, decodedTo: Int? = null): PhonemeEvidence {
        val phonemes = held.subList(0, n).toList()
        held = ArrayList(held.subList(n, held.size))
        var posteriors: TopKPosteriors? = null
        val p = post
        if (p != null) {
            val take = max(0, min(until, p.startFrame + p.ids.size / p.k) - p.startFrame)
            posteriors = TopKPosteriors(
                k = p.k,
                startFrame = p.startFrame,
                nFrames = take,
                ids = p.ids.subList(0, take * p.k).toIntArray(),
                probs = p.probs.subList(0, take * p.k).toDoubleArray(),
            )
            p.ids.subList(0, take * p.k).clear()
            p.probs.subList(0, take * p.k).clear()
            p.startFrame += take
        }
        return PhonemeEvidence(inventory, frameMs, phonemes, posteriors, decodedTo)
    }

    fun add(e: PhonemeEvidence) {
        frameMs = e.frameMs
        inventory = e.inventory
        held.addAll(e.phonemes)
        val posteriors = e.posteriors
        if (posteriors != null) {
            var p = post
            if (p == null) {
                p = PosteriorsBuffer(posteriors.k, posteriors.startFrame, ArrayList(), ArrayList())
                post = p
            }
            p.ids.addAll(posteriors.ids.asList())
            p.probs.addAll(posteriors.probs.asList())
        }
    }

    /** Cells complete up to `decoded` (the first frame not yet emitted); without it, only closed cells. */
    fun ready(decoded: Int? = null): List<PhonemeEvidence> {
        val out = ArrayList<PhonemeEvidence>()
        while (held.isNotEmpty()) {
            val c = cellOf(held[0].start)
            val n = held.indexOfFirst { cellOf(it.start) > c }
            if (n < 0 && !(decoded != null && cellOf(decoded) > c)) break
            val end = ceil((c + 1) * ms / frameMs).toInt()
            out.add(cut(if (n < 0) held.size else n, if (n < 0) end else held[n].start, end))
        }
        return out
    }

    /** Every remaining phoneme and posterior frame, as one last chunk. */
    fun rest(): List<PhonemeEvidence> {
        val out = ArrayList(ready())
        if (held.isNotEmpty() || post?.ids?.isNotEmpty() == true) {
            out.add(cut(held.size, Int.MAX_VALUE))
        }
        return out
    }
}

private class PosteriorsBuffer(
    val k: Int,
    var startFrame: Int,
    val ids: MutableList<Int>,
    val probs: MutableList<Double>,
)

/** The research session from `runtime/session.ts`: tracker + checker fan-out over gridded evidence. */
interface ResearchSession {
    val mode: String
    val passage: Pair<WordRef, WordRef>?
    val capabilities: CapabilityEvent
    fun subscribe(fn: (EngineEvent) -> Unit): () -> Unit
    fun pushAudio(samples: FloatArray, sampleRate: Int): List<EngineEvent>
    fun end(): List<EngineEvent>
    fun pushEvidence(evidence: PhonemeEvidence): List<EngineEvent>
    fun endEvidence(): List<EngineEvent>
    fun mistakes(): List<CheckerEvent>
    fun evidence(): List<PhonemeEvidence>
}

/**
 * The research engine from `runtime/session.ts`. One engine serves many sessions; the reference index,
 * phrases, model runner and manifest are fixed at construction. The model's inventory must match the
 * reference index, or construction throws.
 */
class ResearchEngine(
    private val index: ReferenceIndex,
    private val phrases: PhraseAsset?,
    private val runner: OrtRunner,
    private val manifest: ModelManifest,
    private val recordEvidence: Boolean = false,
) {
    init {
        if (manifest.inventory != index.inventoryId) {
            throw IllegalArgumentException(
                "Model inventory ${manifest.inventory} does not match reference ${index.inventoryId}"
            )
        }
    }

    val capabilities: CapabilityEvent = run {
        val caps = capabilities(manifest.supported)
        CapabilityEvent(
            modelVersion = manifest.id,
            referenceVersion = index.version,
            supported = caps.supported,
            unsupported = caps.unsupported,
        )
    }

    /** Flat word index by "surah:ayah:word", for lock bookkeeping. */
    private val wordIndex: Map<String, Int> =
        index.words.withIndex().associate { (wi, w) -> "${w.surah}:${w.ayah}:${w.word}" to wi }

    fun startMemorization(passage: MemorizationPassage, policy: SessionPolicy = SessionPolicy()): ResearchSession =
        start("memorization", declare(passage), policy)

    fun startFreeRecitation(policy: SessionPolicy = SessionPolicy()): ResearchSession =
        start("free", null, policy)

    private fun declare(p: MemorizationPassage): Pair<WordRef, WordRef> {
        val words = { ayah: Int ->
            index.words.filter { it.surah == p.surah && it.ayah == ayah && it.end > it.start }
        }
        val from = words(p.fromAyah).firstOrNull()
        val to = words(p.toAyah).lastOrNull()
        if (from == null || to == null || p.toAyah < p.fromAyah) {
            throw IllegalArgumentException("No passage ${p.surah}:${p.fromAyah}-${p.toAyah}")
        }
        return Pair(from.ref(), to.ref())
    }

    private fun start(mode: String, passage: Pair<WordRef, WordRef>?, policy: SessionPolicy): ResearchSession {
        val tracker = Tracker(
            index,
            TrackerOptions(mode = mode, phrases = phrases?.phrases, acquireMs = ACQUIRE_MS),
        )
        val checker = createChecker(
            index,
            CheckerOptions(
                phrases = phrases,
                policy = CheckerPolicy(
                    countResolved = policy.countSelfCorrections ?: false,
                    confirm = policy.confirm,
                    minConfidence = policy.minConfidence,
                ),
                passage = passage,
            ),
        )
        val listener = Listener(runner, manifest, posteriors = true)
        val cells = EvidenceGrid(policy.evidenceMs ?: manifest.frameMs.toDouble())
        return object : ResearchSession {
            override val mode: String = mode
            override val passage: Pair<WordRef, WordRef>? = passage
            override val capabilities: CapabilityEvent = this@ResearchEngine.capabilities

            private val subscribers = LinkedHashSet<(EngineEvent) -> Unit>()
            private val recorded = ArrayList<PhonemeEvidence>()
            private val auxiliaryMistakes = ArrayList<CheckerEvent>()
            private val locks = ArrayList<LockPoint>()
            private var pcmResampler: Resampler? = null
            private val pcmParts = ArrayList<FloatArray>()

            override fun subscribe(fn: (EngineEvent) -> Unit): () -> Unit {
                subscribers.add(fn)
                fn(capabilities)
                return { subscribers.remove(fn) }
            }

            private fun noteLocks(events: List<EngineEvent>) {
                for (e in events) {
                    if (e !is PositionEvent) continue
                    if (e.state != "locked" || e.expected == null) continue
                    val wi = wordIndex["${e.expected.surah}:${e.expected.ayah}:${e.expected.word}"] ?: continue
                    if (locks.isEmpty() || locks.last().wi != wi) locks.add(LockPoint(wi, e.timeMs))
                }
            }

            private fun emit(events: List<EngineEvent>): List<EngineEvent> {
                for (e in events) {
                    // Snapshot per event: a subscriber that unsubscribes mid-emit misses later events,
                    // as a JS Set does, without a concurrent-modification failure.
                    for (fn in subscribers.toList()) fn(e)
                }
                return events
            }

            private fun step(e: PhonemeEvidence): List<EngineEvent> {
                if (recordEvidence) recorded.add(e)
                val out = tracker.push(trackerInput(e))
                noteLocks(out)
                return emit(out + checker.push(e, out))
            }

            private fun feed(ready: List<PhonemeEvidence>): List<EngineEvent> = ready.flatMap { step(it) }

            private fun finish(): List<EngineEvent> {
                val out = tracker.end()
                noteLocks(out)
                return emit(out + checker.end(out))
            }

            private fun keepAudio(samples: FloatArray, sampleRate: Int) {
                if (!recordEvidence) return
                if (pcmResampler == null || pcmResampler!!.from != sampleRate) {
                    pcmResampler = Resampler(sampleRate, manifest.sampleRate)
                }
                pcmParts.add(pcmResampler!!.push(samples))
            }

            override fun pushAudio(samples: FloatArray, sampleRate: Int): List<EngineEvent> {
                keepAudio(samples, sampleRate)
                val e = listener.push(samples, sampleRate)
                if (e != null) cells.add(e)
                return feed(cells.ready(listener.decoded))
            }

            override fun end(): List<EngineEvent> {
                if (pcmResampler != null) pcmParts.add(pcmResampler!!.flush())
                val e = listener.end()
                if (e != null) cells.add(e)
                val events = feed(cells.rest()) + finish()
                if (recordEvidence && pcmParts.isNotEmpty()) {
                    val total = pcmParts.sumOf { it.size }
                    val audio = FloatArray(total)
                    var off = 0
                    for (part in pcmParts) {
                        part.copyInto(audio, off)
                        off += part.size
                    }
                    val spliced = if (locks.size > 1) {
                        spliceContextFreeGaps(runner, manifest, audio, manifest.sampleRate, recorded, locks, index)
                    } else {
                        recorded
                    }
                    // addInsertionEvidence without a teacher returns its input unchanged; the auxiliary
                    // teacher replay at end() is omitted with it (spec §6.9), so auxiliaryMistakes stays empty.
                    // Replace only when the splice produced a new list: when spliced IS recorded, clearing
                    // first would empty the very list being copied back (TS splice evaluates its spread
                    // before removing, so it is safe there; Kotlin is not).
                    if (spliced !== recorded) {
                        recorded.clear()
                        recorded.addAll(spliced)
                    }
                }
                return events
            }

            override fun pushEvidence(evidence: PhonemeEvidence): List<EngineEvent> {
                cells.add(evidence)
                val decoded = evidence.posteriors?.let { it.startFrame + it.nFrames }
                return feed(cells.ready(decoded))
            }

            override fun endEvidence(): List<EngineEvent> = feed(cells.rest()) + finish()

            override fun mistakes(): List<CheckerEvent> = checker.mistakes() + auxiliaryMistakes

            override fun evidence(): List<PhonemeEvidence> = if (recordEvidence) recorded else emptyList()
        }
    }
}

/** A locked position with its session audio time, for the gap splice. */
data class LockPoint(val wi: Int, val timeMs: Double)

private data class FlattenedEvidence(
    val inventory: String,
    val frameMs: Int,
    val phonemes: List<DecodedPhoneme>,
    val k: Int,
    val nFrames: Int,
    val ids: IntArray,
    val probs: DoubleArray,
)

private fun DecodedPhoneme.toGap(): GapPhoneme = GapPhoneme(p, start, end, conf)

private fun GapPhoneme.toDecoded(): DecodedPhoneme = DecodedPhoneme(p, start, end, conf)

/**
 * One flattened chunk from many, or null when the chunks cannot flatten: empty, any chunk without
 * posteriors, or differing frame widths / top-k counts.
 */
private fun flattenEvidence(chunks: List<PhonemeEvidence>): FlattenedEvidence? {
    if (chunks.isEmpty()) return null
    var frameMs: Int? = null
    var k: Int? = null
    for (chunk in chunks) {
        val post = chunk.posteriors ?: return null
        if (frameMs == null) frameMs = chunk.frameMs else if (frameMs != chunk.frameMs) return null
        if (k == null) k = post.k else if (k != post.k) return null
    }
    val kk = k!!
    val ids = ArrayList<Int>()
    val probs = ArrayList<Double>()
    var nFrames = 0
    for (chunk in chunks) {
        val post = chunk.posteriors!!
        val rows = min(post.nFrames, post.ids.size / kk)
        nFrames += rows
        for (i in 0 until rows * kk) {
            ids.add(post.ids[i])
            probs.add(post.probs[i])
        }
    }
    return FlattenedEvidence(
        inventory = chunks[0].inventory,
        frameMs = frameMs!!,
        phonemes = chunks.flatMap { it.phonemes },
        k = kk,
        nFrames = nFrames,
        ids = ids.toIntArray(),
        probs = probs.toDoubleArray(),
    )
}

/** A fresh listener decode of one audio crop, in 20 ms steps, flattened. */
private fun decodeIsolated(
    runner: OrtRunner,
    manifest: ModelManifest,
    samples: FloatArray,
    sampleRate: Int,
    k: Int,
): FlattenedEvidence? {
    val listener = Listener(runner, manifest, posteriors = true, topK = k)
    val chunks = ArrayList<PhonemeEvidence>()
    val step = max(1, round(sampleRate * 0.02).toInt())
    var i = 0
    while (i < samples.size) {
        val chunk = listener.push(samples.sliceArray(i until min(samples.size, i + step)), sampleRate)
        if (chunk != null) chunks.add(chunk)
        i += step
    }
    val tail = listener.end()
    if (tail != null) chunks.add(tail)
    return flattenEvidence(chunks)
}

/**
 * Inter-word gaps across the locked runs, in windows of 40 locks stepped by 39. A window is skipped when
 * it holds fewer than two locks or its words cover no reference phonemes.
 */
private fun gapsFor(
    index: ReferenceIndex,
    locks: List<LockPoint>,
    obs: List<DecodedPhoneme>,
    frameMs: Int,
): List<WordGap> {
    val runs = ArrayList<MutableList<LockPoint>>()
    for (lock in locks) {
        val run = runs.lastOrNull()
        if (run == null || lock.wi <= run.last().wi) runs.add(mutableListOf(lock)) else run.add(lock)
    }
    val seen = HashSet<String>()
    val out = ArrayList<WordGap>()
    for (run in runs) {
        var w = 0
        while (w < run.size) {
            val window = run.subList(w, min(run.size, w + 40))
            w += 39
            if (window.size < 2) continue
            val first = index.words[window.first().wi]
            val last = index.words[window.last().wi]
            if (last.end <= first.start) continue
            val t0 = window.first().timeMs - 3000
            val t1 = window.last().timeMs + 1000
            val slice = obs.filter { it.end * frameMs >= t0 && it.start * frameMs <= t1 }
            val gaps = interWordGaps(
                InterWordGapsArgs(
                    phonemes = index.phonemes,
                    from = first.start,
                    to = last.end,
                    wordAt = { off -> index.wordAt(off) },
                    wordText = { wi -> index.wordText[wi] },
                    obs = slice.map { it.toGap() },
                    minFrames = minGapFrames(frameMs),
                )
            )
            for (gap in gaps) {
                if (seen.add("${gap.fromFrame}:${gap.toFrame}")) out.add(gap)
            }
        }
    }
    return out
}

/**
 * Re-decodes the pauses between locked words without verse context and splices any recovered insertion
 * into the recorded evidence. Returns the input chunks unchanged when nothing was recovered; when a gap
 * carried an addition, the whole recording is returned as one flattened chunk with the gap's posterior
 * rows replaced by the cropped decode's.
 *
 * Only the recorded evidence changes — the live events were already emitted. Phonemes keep their
 * [DecodedPhoneme.seamInsertions] through the splice only in the TS, where the same objects pass through
 * `placeGap`; the Kotlin conversion drops them, which is unreachable in v1 (only the unshipped teacher
 * attaches seam insertions).
 */
fun spliceContextFreeGaps(
    runner: OrtRunner,
    manifest: ModelManifest,
    audio: FloatArray,
    sampleRate: Int,
    chunks: List<PhonemeEvidence>,
    locks: List<LockPoint>,
    index: ReferenceIndex,
): List<PhonemeEvidence> {
    val flat = flattenEvidence(chunks) ?: return chunks
    if (locks.isEmpty() || audio.isEmpty()) return chunks
    val gaps = gapsFor(index, locks, flat.phonemes, flat.frameMs)
    if (gaps.isEmpty()) return chunks
    val frameS = flat.frameMs / 1000.0
    var phonemes: List<GapPhoneme> = flat.phonemes.map { it.toGap() }
    val ids = flat.ids.toMutableList()
    val probs = flat.probs.toMutableList()
    var changed = false
    for (gap in gaps) {
        val t0 = max(0.0, gap.fromFrame * frameS - GAP_PAD_S)
        val t1 = min(audio.size.toDouble() / sampleRate, gap.toFrame * frameS + GAP_PAD_S)
        val i0 = floor(t0 * sampleRate).toInt()
        val i1 = min(audio.size, ceil(t1 * sampleRate).toInt())
        if (t1 - t0 > 8 || i1 - i0 < sampleRate * 0.2) continue
        val crop = decodeIsolated(runner, manifest, audio.sliceArray(i0 until i1), sampleRate, flat.k) ?: continue
        if (crop.k != flat.k) continue
        val shift = round(t0 / frameS).toInt()
        val shifted = crop.phonemes.map { GapPhoneme(it.p, it.start + shift, it.end + shift, it.conf) }
        val kept = recoverGapPhonemes(shifted, gap.fromFrame, gap.toFrame, gap.left, gap.right)
        if (!gapCarriesAddition(kept)) continue
        phonemes = placeGap(phonemes, gap, kept)
        for (f in gap.fromFrame until gap.toFrame) {
            if (f < 0 || f >= flat.nFrames) continue
            val src = f - shift
            if (src < 0 || (src + 1) * flat.k > crop.ids.size) continue
            for (j in 0 until flat.k) {
                ids[f * flat.k + j] = crop.ids[src * flat.k + j]
                probs[f * flat.k + j] = crop.probs[src * flat.k + j]
            }
        }
        changed = true
    }
    if (!changed) return chunks
    return listOf(
        PhonemeEvidence(
            inventory = flat.inventory,
            frameMs = flat.frameMs,
            phonemes = phonemes.map { it.toDecoded() },
            posteriors = TopKPosteriors(flat.k, 0, flat.nFrames, ids.toIntArray(), probs.toDoubleArray()),
        )
    )
}

/**
 * The public session from `src/session.ts`: lifecycle (`active` | `ending` | `ended` | `disposed`),
 * capabilities delivered first to every subscriber, an audio tap that mirrors the listener's resampling,
 * and the session audio clock (`now()`).
 *
 * Synchronous: unlike the TS wrapper there is no job queue, so `pushAudio`/`end` return their events
 * directly and `idle()` is a no-op. A push after the session ended, or after an inference failure, throws
 * [SessionEndedError]. `end()` is idempotent — a second call returns the same events (or rethrows the
 * same failure, as the TS wrapper's stored promise does).
 */
class TilawaSession(
    research: ResearchSession,
    val mode: String,
    val passage: DeclaredPassage?,
    val policy: ResolvedSessionPolicy,
    val capabilities: CapabilityEvent,
) {
    val id: String = UUID.randomUUID().toString()

    var state: String = "active"
        private set

    /** Wall-clock time of the first non-empty audio push, ISO 8601 UTC. */
    var startedAt: String? = null
        private set

    /** Whether voice-activity gating is on. Set by [setVad]; the push-based path does not gate on it. */
    var vad: Boolean = false
        private set

    private var inner: ResearchSession? = research
    private var failed = false
    private var endingEvents: List<EngineEvent>? = null
    private var endError: Exception? = null
    private val subscribers = LinkedHashSet<(EngineEvent) -> Unit>()
    private val taps = LinkedHashSet<(FloatArray, Double) -> Unit>()
    private val closers = ArrayList<() -> Unit>()
    private var resampler: Resampler? = null
    private var delivered: Long = 0
    private var rate: Int = 16000

    init {
        // The research session calls a new subscriber with its capabilities at once; the wrapper sends
        // its own capabilities instead, so the first call is dropped.
        var attached = false
        research.subscribe { e ->
            if (!attached) return@subscribe
            for (fn in subscribers.toList()) {
                try {
                    fn(e)
                } catch (_: Exception) {
                    // Swallowed: TS rethrows outside the engine loop, which has no safe Android equivalent.
                }
            }
        }
        attached = true
    }

    /** Session audio time in ms: samples delivered to the tap so far, at the model rate. */
    fun now(): Double = (delivered * 1000).toDouble() / rate

    /** Subscribes to session events; the capabilities event is delivered at once. */
    fun subscribe(fn: (EngineEvent) -> Unit): () -> Unit {
        if (state == "disposed") return {}
        subscribers.add(fn)
        fn(capabilities)
        return { subscribers.remove(fn) }
    }

    /** Subscribes to resampled 16 kHz audio with its start time in ms. */
    fun onAudio(fn: (FloatArray, Double) -> Unit): () -> Unit {
        if (state == "disposed") return {}
        taps.add(fn)
        return { taps.remove(fn) }
    }

    private fun deliver(pcm: FloatArray) {
        val startMs = (delivered * 1000).toDouble() / rate
        delivered += pcm.size
        if (taps.isEmpty() || pcm.isEmpty()) return
        for (fn in taps.toList()) {
            try {
                fn(pcm, startMs)
            } catch (_: Exception) {
                // Swallowed, as with subscriber errors (see the file header).
            }
        }
    }

    private fun tapIn(samples: FloatArray, sampleRate: Int) {
        if (sampleRate == rate) {
            deliver(samples)
            return
        }
        if (resampler == null || resampler!!.from != sampleRate) resampler = Resampler(sampleRate, rate)
        deliver(resampler!!.push(samples))
    }

    fun pushAudio(samples: FloatArray, sampleRate: Int): List<EngineEvent> {
        if (state != "active") throw SessionEndedError()
        if (startedAt == null && samples.isNotEmpty()) startedAt = isoNow()
        if (failed || inner == null) {
            throw SessionEndedError("The session ended after an earlier inference failure")
        }
        tapIn(samples, sampleRate)
        return try {
            inner!!.pushAudio(samples, sampleRate)
        } catch (e: Exception) {
            failed = true
            if (state != "disposed") state = "ended"
            close()
            throw e
        }
    }

    fun end(): List<EngineEvent> {
        endingEvents?.let { return it }
        endError?.let { throw it }
        if (state == "disposed") throw SessionEndedError("end() after the session was disposed")
        if (state == "ended") {
            endingEvents = emptyList()
            return emptyList()
        }
        state = "ending"
        try {
            val events: List<EngineEvent>
            if (failed || inner == null) {
                events = emptyList()
            } else {
                if (resampler != null) deliver(resampler!!.flush())
                events = inner!!.end()
            }
            endingEvents = events
            if (state != "disposed") state = "ended"
            close()
            return events
        } catch (e: Exception) {
            endError = e
            if (state != "disposed") state = "ended"
            close()
            throw e
        }
    }

    /** Releases every subscriber and tap and drops the inner session. `mistakes()`/`evidence()` then return []. */
    fun dispose() {
        if (state == "disposed") return
        state = "disposed"
        subscribers.clear()
        taps.clear()
        inner = null
        close()
    }

    /** Synchronous Kotlin: nothing is queued, so the session is always idle. */
    fun idle() {
    }

    /** Runs `fn` when the session ends or is disposed; runs at once if it already ended. */
    fun onEnded(fn: () -> Unit): () -> Unit {
        if (state == "ended" || state == "disposed") {
            fn()
            return {}
        }
        var on = true
        val closer = { if (on) fn() }
        closers.add(closer)
        return {
            on = false
            closers.remove(closer)
        }
    }

    fun pushEvidence(evidence: PhonemeEvidence): List<EngineEvent> {
        if (state != "active" || inner == null) {
            throw SessionEndedError("pushEvidence after the session ended")
        }
        return inner!!.pushEvidence(evidence)
    }

    fun endEvidence(): List<EngineEvent> {
        if (state != "active" || inner == null) {
            throw SessionEndedError("endEvidence after the session ended")
        }
        val events = inner!!.endEvidence()
        state = "ended"
        endingEvents = events
        close()
        return events
    }

    fun mistakes(): List<CheckerEvent> = inner?.mistakes() ?: emptyList()

    fun evidence(): List<PhonemeEvidence> = inner?.evidence() ?: emptyList()

    /** Turns voice-activity gating on or off. The push-based Kotlin path does not gate audio on it. */
    internal fun setVad(on: Boolean) {
        vad = on
    }

    private fun close() {
        val run = closers.toList()
        closers.clear()
        for (fn in run) fn()
    }
}

/** ISO 8601 UTC timestamp. `SimpleDateFormat` (not `java.time`) so this runs on minSdk 24. */
private fun isoNow(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(System.currentTimeMillis()))
}

/**
 * The public engine from `src/session.ts`: creates [TilawaSession]s. The capabilities are the research
 * shape (model and reference versions, supported/unsupported classes); the V1-only fields
 * (`sdkVersion`, `modelSha256`, `modelRelease`, `tier`, `backend`) are not emitted.
 */
class TilawaEngineCore(
    index: ReferenceIndex,
    phrases: PhraseAsset?,
    runner: OrtRunner,
    private val manifest: ModelManifest = LITE_MANIFEST,
    recordEvidence: Boolean = false,
) {
    private val research = ResearchEngine(index, phrases, runner, manifest, recordEvidence)

    val capabilities: CapabilityEvent = research.capabilities

    fun startMemorization(passage: MemorizationPassage, policy: SessionPolicy = SessionPolicy()): TilawaSession {
        val session = research.startMemorization(passage, policy)
        val (from, to) = session.passage!!
        val declared = DeclaredPassage(passage.surah, passage.fromAyah, passage.toAyah, from, to)
        return TilawaSession(session, "memorization", declared, resolvePolicy(policy, manifest.frameMs), capabilities)
    }

    fun startFreeRecitation(policy: SessionPolicy = SessionPolicy()): TilawaSession {
        val session = research.startFreeRecitation(policy)
        return TilawaSession(session, "free", null, resolvePolicy(policy, manifest.frameMs), capabilities)
    }
}
