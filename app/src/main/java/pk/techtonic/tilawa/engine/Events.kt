// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

const val EVENT_SCHEMA_VERSION = 1

data class WordRef(val surah: Int, val ayah: Int, val word: Int)

data class WordSpan(val start: WordRef, val end: WordRef)

data class PositionCandidate(
    /** The most recently recited word. */
    val word: WordRef,
    /** Words covered by the evidence so far; may cross ayahs. */
    val span: WordSpan,
    val confidence: Double,
    /** Weighted alignment cost per observed phoneme, used only for ranking. 0 is an exact match. */
    val cost: Double,
    /** Unweighted phoneme error rate against the matched span, from the same alignment path. */
    val per: Double,
    /** Set when a leading basmala was recited before the span; it is excluded from `span` and `per`. */
    val prefix: String? = null,
)

typealias TrackerState = String

data class TraceCandidate(
    val span: WordSpan,
    /** Half-open phoneme range in the reference index. */
    val offset: Int,
    val length: Int,
    val votes: Int,
    val cost: Double,
    val per: Double,
)

data class TrackerTrace(
    val shortlist: List<TraceCandidate>,
    val transition: Pair<TrackerState?, TrackerState>,
    /** Alignment of the observed phonemes against the top candidate; null without a candidate. */
    val path: List<AlignmentOp>?,
    /** The top fitting candidate's confidence terms; null when nothing fits. `confidence = fit * separation`. */
    val decision: TraceDecision?,
)

data class TraceDecision(val fit: Double, val separation: Double, val leadEdits: Double, val requiredEdits: Double)

sealed class EngineEvent {
    abstract val type: String
    abstract val v: Int
}

data class PositionEvent(
    val timeMs: Double,
    val referenceVersion: String,
    val expected: WordRef?,
    /** Ranked best first; deduplicated so each entry is a distinct location. */
    val observed: List<PositionCandidate>,
    val state: TrackerState,
    /**
     * Set on the locked event that starts a misfit hold. An open issue stays open only when its own edit makes the
     * heard words after it fit the anchor again; every other open issue ends `uncertain`, as on a lock drop. The
     * checker's window stays so tokens heard after this are still judged.
     */
    val hold: String? = null,
    /**
     * A slot-differing parallel span resolved. `from` and `to` are the member span starts. Equal means the
     * continuation confirmed the locked copy, and held slot accusations are judged against it. Different means the
     * heard word fit only the other member: the span is re-read against that copy.
     */
    val relocated: Pair<WordRef, WordRef>? = null,
    /**
     * The session's first lock has not yet heard a word outside every slot-differing span. Slot accusations wait
     * for the continuation. Absent once that word arrives, and on every later lock.
     */
    val spanHold: Boolean = false,
    /** Present only when the tracker was created with `trace: true`. */
    val trace: TrackerTrace? = null,
) : EngineEvent() {
    override val type: String = "position"
    override val v: Int = EVENT_SCHEMA_VERSION
}

typealias MemorizationErrorClass = String
typealias ErrorClass = String
typealias CheckerState = String

data class CheckerEvent(
    val id: String,
    val timeMs: Double,
    val state: CheckerState,
    val errorClass: ErrorClass,
    val location: WordRef,
    val expected: String? = null,
    var observed: String? = null,
    val confidence: Double,
) : EngineEvent() {
    override val type: String = "checker"
    override val v: Int = EVENT_SCHEMA_VERSION
}

data class CapabilityEvent(
    val modelVersion: String,
    val referenceVersion: String,
    val supported: List<ErrorClass>,
    val unsupported: List<ErrorClass>,
    // V1 wrapper fields (src/session.ts); absent on the research event.
    val sdkVersion: String? = null,
    val modelRelease: String? = null,
    val modelSha256: String? = null,
    val tier: String? = null,
    val backend: String? = null,
) : EngineEvent() {
    override val type: String = "capabilities"
    override val v: Int = EVENT_SCHEMA_VERSION
}

/**
 * One confirmed mistake, as the UI reads it (UI contract shape).
 *
 * `wordIndex` is 0-BASED: it indexes the word list of the ayah in ref-v2.json
 * directly, so the UI shows `wordIndex + 1` as the word number. The internal
 * engine (and the upstream goldens) use 1-based `WordRef.word`; the
 * TilawaEngine facade converts when it builds this list (see TilawaEngine.kt).
 * `expected`/`observed` are never null here: a missing side becomes "".
 */
data class Mistake(
    val surah: Int,
    val ayah: Int,
    val wordIndex: Int,
    val errorClass: ErrorClass,
    val expected: String,
    val observed: String,
)
