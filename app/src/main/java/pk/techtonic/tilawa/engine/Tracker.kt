// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val MAX_BASMALA_PER = 0.25

/** The largest prior bonus, in `UNIT`s; below `minSeparation` so a prior alone never separates. */
private const val MAX_PRIOR_BONUS = UNIT / 2.0

/** Nats of CTC likelihood, relative to the decoded sequence, that cost as much as one plain edit. */
private const val NATS_PER_EDIT = 3.0

/**
 * While acquiring, at most this many earlier phonemes are kept when a new chunk arrives. The chunk itself is always
 * searched whole, so a single-push clip is unaffected; streamed acquisitions longer than this depend on chunking.
 */
private const val MAX_HISTORY = 96

/** Local recent cost, in plain edits per phoneme, above which global search runs alongside local tracking. */
private const val WEAK_COST = 0.3

/** Phonemes before the weakness was noticed that the global search also sees: recent cost lags the deviation. */
private const val LOOKBACK = 6

/** Global search while weak or after `lost` looks at only the latest this many phonemes. */
private const val WINDOW = 48

/** While weak: hold the anchor once the window (lookback included) holds this many phonemes, nothing fits globally and local cost exceeds `maxCost`. `lost` follows only when that search locks off the anchor. */
private const val LOST_AFTER = 24

/** A global lock within this many ayahs of the local bookmark is the local search's to follow, not a jump. */
private const val NEAR_AYAHS = 2

/** Phonemes by which recent cost lags a deviation: half the local search's recent-cost window. */
private const val WEAK_LAG = 8

/** A new local run shows once its last this many phonemes cost at most `CONFIRM_COST` plain edits each on average. */
private const val CONFIRM = 3
private const val CONFIRM_COST = 0.34

/**
 * After a skipped takbir, al-Fatiha's opening gets this bonus: in prayer each rak'ah restarts with it, and its al-hamd
 * opening is shared with five other passages, so the half-edit `prior` bonus couldn't separate it.
 */
private const val FATIHA_BONUS = 2.0 * UNIT

/** A phrase is heard when it aligns to the latest phonemes at most this weighted cost per phrase phoneme. */
private const val PHRASE_MAX_COST = 0.3

/** While locked, a heard phrase is skipped only if local search fit it this badly, per phoneme: else it's Quran text. */
private const val PHRASE_LOCAL_COST = 0.25

/** Phonemes kept for phrase detection; longer than any phrase as recited. */
private const val HEARD = 64

/**
 * A phrase in progress holds off locking once this many of its phonemes, and this share of it, are heard. Shorter
 * prefixes ("Allahu" of the takbir) are everyday Quran text.
 */
private const val PHRASE_PREFIX = 12
private const val PHRASE_PREFIX_SHARE = 0.4

/** A restart in progress holds off locking once this many phonemes restate the window's start. */
private const val RESTART_PREFIX = 6

/** Repeated blocks this long, at most this weighted cost per phoneme apart, collapse to one copy in a stream. */
private const val MIN_REPEAT = 8
private const val MAX_REPEAT = 40
private const val REPEAT_MAX_COST = 0.15

private fun ayahCompare(a: AyahRef, b: AyahRef): Int = if (a.surah != b.surah) a.surah - b.surah else a.ayah - b.ayah

/** Local recent cost above this, in plain edits per phoneme, is the misfit that starts a hold. */
const val MISFIT_MAX_COST = 0.35

/** Commit separation, in plain edits: `max(COMMIT_MIN_SEPARATION, COMMIT_SEPARATION_SCALE / n)`. */
const val COMMIT_MIN_SEPARATION = 1.0
const val COMMIT_SEPARATION_SCALE = 30.0

/** The lock's commit margin for `n` observed phonemes, in plain edits. */
fun commitMargin(
    n: Int,
    minSeparation: Double = COMMIT_MIN_SEPARATION,
    separationScale: Double = COMMIT_SEPARATION_SCALE,
): Double = max(minSeparation, separationScale / max(1, n))

data class IssueDropFit(
    val n: Int,
    /** Weighted cost per observed phoneme, in plain edits. */
    val unedited: Double,
    val edited: Double,
    /** Plain edits by which the edited anchor beats the written one. */
    val gain: Double,
    /** [commitMargin] for this span. */
    val margin: Double,
    val explains: Boolean,
)

/**
 * Cost of aligning every heard phoneme to a substring of `reference`, starting at its first phoneme, in `UNIT`s.
 * The same weighted word costs as local tracking (`subCost`, `insertionCost`, `deletionCost`).
 */
fun anchorFitCost(reference: String, heard: String): Double? {
    if (reference.isEmpty() || heard.isEmpty()) return null
    val fit = alignSubstring(reference, 0, reference.length, heard, { offset -> offset == 0 }, AlignOptions(strictStart = true))
    return fit?.cost
}

/**
 * Whether applying an issue's edit to the anchor text explains a misfit. `written` is the anchor from the issue
 * through the drop; `edited` is that text with the issue's own edit applied; `heard` is the phonemes between them.
 * The edited fit must beat the unedited fit by the commit margin and fall below the misfit cost. No new threshold.
 */
fun issueDropFit(
    written: String,
    edited: String,
    heard: String,
    maxCost: Double = MISFIT_MAX_COST,
    minSeparation: Double = COMMIT_MIN_SEPARATION,
    separationScale: Double = COMMIT_SEPARATION_SCALE,
): IssueDropFit? {
    val uneditedCost = anchorFitCost(written, heard) ?: return null
    val editedCost = anchorFitCost(edited, heard) ?: return null
    val n = heard.length
    val gain = (uneditedCost - editedCost) / UNIT
    val margin = commitMargin(n, minSeparation, separationScale)
    val unedited = uneditedCost / UNIT / n
    val editedPer = editedCost / UNIT / n
    return IssueDropFit(n, unedited, editedPer, gain, margin, gain >= margin && editedPer < maxCost)
}

/**
 * An added word's heard phones deleted, the phones after them kept. `heard` is the issue through the drop;
 * `kept` is `heard` without the inserted span. The margin is the commit margin of the full span, so a short
 * tail is not asked to clear the margin a longer one would. Same word costs as [issueDropFit].
 */
fun insertionDropFit(
    reference: String,
    heard: String,
    kept: String,
    maxCost: Double = MISFIT_MAX_COST,
    minSeparation: Double = COMMIT_MIN_SEPARATION,
    separationScale: Double = COMMIT_SEPARATION_SCALE,
): IssueDropFit? {
    if (reference.isEmpty() || heard.isEmpty() || kept.isEmpty()) return null
    val uneditedCost = anchorFitCost(reference, heard) ?: return null
    val editedCost = anchorFitCost(reference, kept) ?: return null
    val n = heard.length
    val gain = (uneditedCost - editedCost) / UNIT
    val margin = commitMargin(n, minSeparation, separationScale)
    val unedited = uneditedCost / UNIT / n
    val editedPer = editedCost / UNIT / n
    return IssueDropFit(n, unedited, editedPer, gain, margin, gain >= margin && editedPer < maxCost)
}

data class DestinationWord(
    /** Phonemes of the destination word this span aligned to. */
    val destination: String,
    /** Heard phonemes the destination alignment gave that word. */
    val heard: String,
)

data class RelocationFit(val count: Int, val total: Double)

/**
 * Heard words that fit a relocation's destination better than the anchor continuation with exactly one
 * inserted heard word, by the commit margin. A word the insertion hypothesis also fits, such as the anchor's
 * own next word, counts zero. `total` is the winning hypothesis's cost, in `UNIT`s, so callers can prefer the
 * anchor start the hypothesis actually explains.
 */
fun relocationSupport(
    anchorWords: List<String>,
    heard: List<DestinationWord>,
    minSeparation: Double = COMMIT_MIN_SEPARATION,
    separationScale: Double = COMMIT_SEPARATION_SCALE,
): RelocationFit? {
    val words = heard.filter { it.heard.isNotEmpty() && it.destination.isNotEmpty() }
    if (words.isEmpty() || anchorWords.isEmpty()) return null
    var best: RelocationFit? = null
    for (ins in words.indices) {
        var ai = 0
        var total = 0.0
        var count = 0
        var placed = true
        for (i in words.indices) {
            val h = words[i]
            val dest = anchorFitCost(h.destination, h.heard)
            val hyp: Double? = if (i == ins) {
                var c = 0.0
                for (ch in h.heard) c += insertionCost("", 0, ch)
                c
            } else if (ai < anchorWords.size) {
                anchorFitCost(anchorWords[ai], h.heard).also { ai++ }
            } else null
            if (dest == null || hyp == null) {
                placed = false
                break
            }
            // The commit margin widens on a short span. A word the destination reads exactly is already that sure, so it
            // counts once it leads by the margin's floor; a partial or imperfect reading has to clear the wide margin, which
            // is what keeps a shared word heard in part from voting.
            val gain = (hyp - dest) / UNIT
            val margin = commitMargin(h.heard.length, minSeparation, separationScale)
            if (gain >= margin || (dest == 0.0 && gain >= minSeparation)) count++
            total += hyp
        }
        if (placed && (best == null || total < best.total)) best = RelocationFit(count, total)
    }
    return best
}

/** What the tracker is fed from one chunk of listener evidence, in the product and in every bench alike: phonemes only. */
fun trackerInput(evidence: PhonemeEvidence): PhonemeEvidence = evidence.copy(posteriors = null)

data class TrackerPrior(val surah: Int, val ayah: Int, val weight: Double)
data class TrackerPriorRange(val ranges: List<Pair<AyahRef, AyahRef>>, val weight: Double)

data class TrackerOptions(
    val mode: String? = null,
    /** Grams occurring more often than this carry too little location information to vote. */
    val maxGramOccurrences: Int = 5000,
    val maxCandidates: Int = 64,
    /** Fit: a candidate fits only with PER at most this. Default 0.35. */
    val maxPer: Double = 0.35,
    /** Fit: and weighted cost per observed phoneme at most this. Default [MISFIT_MAX_COST]. */
    val maxCost: Double = MISFIT_MAX_COST,
    /** Evidence: `locked` needs at least this many observed phonemes. Default 10. */
    val minPhonemes: Int = 10,
    /** Evidence: and a span of at least this many words. Default 2. */
    val minWords: Int = 2,
    /** Separation: `locked` needs the best distinct candidate to trail by `max(minSeparation, separationScale / n)` plain edits. */
    val minSeparation: Double = COMMIT_MIN_SEPARATION,
    val separationScale: Double = COMMIT_SEPARATION_SCALE,
    /** `locked` also needs the top candidate's confidence at least this; a unique but poor fit stays `acquiring`. Default 0.08. */
    val minLockConfidence: Double = 0.08,
    /** Bonus for the intended or filename ayah (full) and its neighbours (half). Bounded so it can't override fit or separation. */
    val prior: TrackerPrior? = null,
    /** Full prior bonus, uniformly, for any ayah inside one of the inclusive ranges (a surah or a page). Same cap as `prior`. */
    val priorRange: TrackerPriorRange? = null,
    /** Once locked, a distant location must hold a global lock for this many consecutive observed phonemes before the tracker jumps there. Default 8. */
    val jumpPhonemes: Int = 8,
    /** Non-Quran phrases to recognise and skip in a stream. */
    val phrases: List<Phrase>? = null,
    /** Attach shortlist, state transition and alignment path to every position event. */
    val trace: Boolean = false,
    /** Streaming: before the first lock, run global search once per this much audio on a fixed grid of evidence time. */
    val acquireMs: Int? = null,
)

data class Match(
    val offset: Int,
    val length: Int,
    /** Weighted cost in `UNIT`s. */
    val cost: Double,
    val per: Double,
    val votes: Int,
    val path: List<AlignmentOp>,
    val basmala: Boolean = false,
    /** The decoded alignment cost, kept when posteriors replace `cost` for ranking. */
    val decodedCost: Double? = null,
    /** With `basmala`: observed phonemes before this index are the prefix, which costs `prefixCost`. */
    val prefixEnd: Int? = null,
    val prefixCost: Double? = null,
)

/** Global search over the observed phonemes: the event it supports, the lock if any, and its trace. Mutable: reacquire reassigns `event`/`lock`. */
class AssessResult(
    var event: PositionEvent,
    var lock: Match?,
    val trace: (TrackerState?) -> TrackerTrace,
    val top: Match?,
)

private data class FrameRange(val start: Int, val end: Int)
private data class PendingMatch(val m: Match, val held: Int)
private class TwinState(val span: SlotSpan, val memberStart: Int, val endPhone: Int, var buf: String)
private class ExcursionState(
    val home: Int,
    val dest: Int,
    val length: Int,
    val entry: Int,
    var phones: String,
    var trimmed: Boolean,
)

private class AfterBasmalaState(val from: WordRef, var held: Int)

class Tracker(private val index: ReferenceIndex, private val options: TrackerOptions = TrackerOptions()) {
    private val maxGramOccurrences = options.maxGramOccurrences
    private val maxCandidates = options.maxCandidates
    private val maxPer = options.maxPer
    private val maxCost = options.maxCost
    private val minPhonemes = options.minPhonemes
    private val minWords = options.minWords
    private val minSeparation = options.minSeparation
    private val separationScale = options.separationScale
    private val minLockConfidence = options.minLockConfidence
    private val prior = options.prior
    private val priorRange = options.priorRange
    private val jumpPhonemes = options.jumpPhonemes
    private val memorization = options.mode == "memorization"
    private val wordStarts = index.words.map { it.start }.toSet()
    private val isWordStart = { offset: Int -> wordStarts.contains(offset) }
    private val basmala: String
    private val fatihaAyah2: Int
    private val fatihaRabbi: Int

    private var observed = ""
    private var observedConf: MutableList<Double> = ArrayList()
    private val observedFrames = ArrayList<FrameRange>()
    private val tokenIds: Map<Char, Int> = index.tokens.mapIndexed { i, t -> t to (i + 1) }.toMap()

    /** Only the first acquisition reranks with posteriors; dropped at lock, so later global searches are decoded-only. */
    private var posteriors: PosteriorFrames? = null
    private var decodedOnly = false
    private var withPosteriors = false
    private var frameMs: Int? = null
    private var lastFrame = 0
    private var last: PositionEvent? = null

    /** Set once locked: from then on the tracker follows the reciter locally, phoneme by phoneme. */
    private var local: LocalSearch? = null
    private var lockedOn: Match? = null

    /** The event that reported the lock, kept with its trace until a local event replaces it. */
    private var lockEvent: PositionEvent? = null
    private var emittedSinceLock = false

    /** Local fit has degraded: global search over the latest phonemes runs alongside the local search. */
    private var weak = false

    /** A distant global lock seen while weak, and for how many consecutive phonemes it has held. */
    private var pending: PendingMatch? = null

    /** After a misfit: global search over a rolling window, one phoneme at a time, until it locks again. */
    private var reacquiring = false

    /**
     * Lock kept while that search runs. A misfit used to emit `lost` before any destination had committed, and the
     * checker then dropped the anchor and the tokens heard in the gap. `lost` is emitted only when a lock commits off
     * this anchor, or the session ends still uncommitted. The held event carries `hold: "start"` once. The checker
     * keeps an open issue only when that issue's edit explains the drop, and downgrades the rest; tokens after the
     * signal still enter the window.
     */
    private var holding: PositionEvent? = null

    /** Set at the first lock. The onset veto applies only to that lock. */
    private var everLocked = false

    /** Slot-differing span the lock is inside, until its continuation picks a member. */
    private var twin: TwinState? = null

    /**
     * A lock onto the other member of a parallel span (or an exact copy of the next ayah). The phones heard until
     * the in-order continuation commits are what a retro rebind is scored on.
     */
    private var excursion: ExcursionState? = null

    /**
     * Holding and continuation resolution run only on the first lock, and only until that lock's position leaves
     * every slot-differing span. A word outside those spans is unique to its location. Any later `startLocal`
     * (a jump, a reacquisition, the re-anchor after a copy change) closes it.
     */
    private var arm = false

    /** Spans already resolved or given up at the lost timeout. Not watched again. */
    private val twinSettled = LinkedHashSet<String>()
    private val allSpans = slotSpans(index)

    /** Every phoneme until the first lock, untrimmed, so a first-lock veto can see back to the speech onset. */
    private var raw = ""
    private var rawConf: MutableList<Double> = ArrayList()

    /** Phones of `raw` consumed by a leading non-Quran phrase the streaming matcher already cut. */
    private var onsetCut = 0

    /** Memorization mode: the expected bookmark held in place after the reciter jumped away from it. */
    private var frozen: WordRef? = null

    /** The expected bookmark from before local fit last turned weak. */
    private var steady: WordRef? = null

    /**
     * The expected bookmark after each recent phoneme. Recent cost lags a deviation by several phonemes, during which
     * local skips can already have moved the bookmark; the one from before that lag is what weakness should freeze.
     */
    private val expectedHistory = ArrayList<WordRef?>()

    /** The local search's cost for each of the last `CONFIRM` phonemes. */
    private val stepCosts = ArrayList<Double>()

    private val phraseList: List<Phrase> = options.phrases?.let { it + Phrase("basmala", "", basmalaText(index)) } ?: emptyList()

    /** The latest phonemes, with the local search's cost for each and its state before each, for phrase detection. */
    private var heard = ""
    private var heardCost: MutableList<Double> = ArrayList()
    private var heardState: MutableList<LocalSearchState?> = ArrayList()

    /** While acquiring: observed phonemes up to here end with a phrase, to drop at the next push. */
    private var phraseCut = 0

    /** A basmala was skipped in an earlier push (`surahStart`) or this one (`basmalaHeard`): favour surah openings. */
    private var surahStart = false
    private var basmalaHeard = false

    /** A takbir was skipped: in prayer al-Fatiha comes next (`fatihaNext`), promoted from `takbirHeard` like the basmala. */
    private var fatihaNext = false

    /** Re-acquiring after a basmala ended a locked passage: where it ended, and phonemes since. */
    private var afterBasmala: AfterBasmalaState? = null
    private var takbirHeard = false

    /** A phrase was just skipped while locked: a boundary, so the next distant lock needn't wait for persistence. */
    private var afterPhrase = false
    private var pushes = 0

    /** Phonemes of the current acquisition cell, held until a phoneme from a later cell shows the cell is complete. */
    private var cell: MutableList<DecodedPhoneme> = ArrayList()
    private var cellAt = 0

    init {
        val fatiha = index.words.filter { it.surah == 1 && it.ayah == 1 }
        basmala = index.phonemes.substring(fatiha[0].start, fatiha.last().end)
        fatihaAyah2 = index.words.first { it.surah == 1 && it.ayah == 2 }.start
        fatihaRabbi = index.words.indexOfFirst { it.surah == 1 && it.ayah == 2 && it.word == 3 }
    }

    companion object {
        private fun basmalaText(index: ReferenceIndex): String {
            val fatiha = index.words.filter { it.surah == 1 && it.ayah == 1 }
            return index.phonemes.substring(fatiha[0].start, fatiha.last().end)
        }
    }

    private fun validate(e: PhonemeEvidence) {
        if (e.inventory != index.inventoryId) {
            throw EvidenceError("Evidence inventory \"${e.inventory}\" does not match index inventory \"${index.inventoryId}\"")
        }
        if (frameMs != null && e.frameMs != frameMs) {
            throw EvidenceError("Frame stride changed from $frameMs ms to ${e.frameMs} ms within a session")
        }
        for (ph in e.phonemes) {
            if (!index.tokens.contains(ph.p)) {
                throw EvidenceError("Phoneme \"${ph.p}\" is not in inventory \"${index.inventoryId}\"")
            }
        }
        if (if (e.posteriors != null) decodedOnly else withPosteriors) {
            throw EvidenceError("Every chunk in a session must carry posteriors, or none")
        }
        val p = e.posteriors ?: return
        if (p.ids.size != p.nFrames * p.k || p.probs.size != p.nFrames * p.k) {
            throw EvidenceError(
                "Posterior rows hold ${p.ids.size} ids and ${p.probs.size} probs, expected nFrames x k = ${p.nFrames * p.k}",
            )
        }
        var listed = 0
        for (ph in e.phonemes) {
            if (ph.start < p.startFrame || ph.end >= p.startFrame + p.nFrames) {
                throw EvidenceError(
                    "Phoneme frames ${ph.start}..${ph.end} fall outside posterior frames ${p.startFrame}..${p.startFrame + p.nFrames - 1}; check the frame stride",
                )
            }
            val id = tokenIds[ph.p]!!
            var found = false
            for (f in ph.start..ph.end) {
                if (found) break
                for (c in 0 until p.k) {
                    if (p.ids[(f - p.startFrame) * p.k + c] == id) found = true
                }
            }
            if (found) listed++
        }
        if (e.phonemes.isNotEmpty() && listed < MIN_LISTED * e.phonemes.size) {
            throw EvidenceError(
                "Only $listed of ${e.phonemes.size} decoded phonemes appear in their frames' top-k; posterior ids don't follow the inventory order",
            )
        }
    }

    private fun exactScan(query: String): List<Match> {
        val found = ArrayList<Match>()
        var at = index.phonemes.indexOf(query)
        while (at != -1 && found.size < maxCandidates) {
            val path = query.mapIndexed { k, p -> AlignmentOp.Match(p, k, k) }
            found.add(Match(at, query.length, 0.0, 0.0, 0, path))
            at = index.phonemes.indexOf(query, at + 1)
        }
        return found
    }

    private fun fuzzyMatches(query: String, queryConf: List<Double>): List<Match> {
        if (query.length < index.gramSize) return exactScan(query)
        val votes = LinkedHashMap<Int, Int>()
        for (q in 0..query.length - index.gramSize) {
            val hits = index.grams[query.substring(q, q + index.gramSize)]
            if (hits == null || hits.size > maxGramOccurrences) continue
            for (pos in hits) {
                val diagonal = maxOf(0, pos - q)
                votes[diagonal] = (votes[diagonal] ?: 0) + 1
            }
        }
        // Every gram was too common to vote; fall back to a direct scan.
        if (votes.isEmpty()) return exactScan(query)

        // Learner insertions and deletions shift the diagonal, so nearby diagonals vote for one location.
        val slack = maxOf(4, ceil(query.length * 0.25).toInt())
        // Bins are centred on vote peaks with a fixed width: chaining nearby diagonals lets common grams join one bin
        // across the whole Quran on long clips.
        val diagonals = votes.keys.sorted()
        val prefix = ArrayList<Int>()
        prefix.add(0)
        for (d in diagonals) prefix.add(prefix.last() + (votes[d] ?: 0))
        val firstAtLeast = { x: Int ->
            var a = 0
            var b = diagonals.size
            while (a < b) {
                val mid = (a + b) shr 1
                if (diagonals[mid] < x) a = mid + 1 else b = mid
            }
            a
        }
        data class Windowed(val k: Int, val lo: Int, val hi: Int, val votes: Int)
        val windowed = diagonals.mapIndexed { k, d ->
            val a = firstAtLeast(d - slack)
            val b = firstAtLeast(d + slack + 1)
            Windowed(k, diagonals[a], diagonals[b - 1], prefix[b] - prefix[a])
        }.sortedWith(compareByDescending<Windowed> { it.votes }.thenBy { diagonals[it.k] })
        data class Bin(val lo: Int, val hi: Int, val votes: Int, val centre: Int)
        val bins = ArrayList<Bin>()
        for (c in windowed) {
            if (bins.size >= maxCandidates) break
            val centre = diagonals[c.k]
            if (bins.any { kotlin.math.abs(it.centre - centre) <= slack }) continue
            bins.add(Bin(c.lo, c.hi, c.votes, centre))
        }

        val byStart = LinkedHashMap<Int, Match>()
        for (bin in bins) {
            val lo = maxOf(0, bin.lo - slack)
            val hi = minOf(index.phonemes.length, bin.hi + query.length + slack)
            val a = alignSubstring(index.phonemes, lo, hi, query, isWordStart, AlignOptions(conf = queryConf))
            if (a == null || a.per > maxPer) continue
            val prev = byStart[a.start]
            if (prev != null && prev.cost <= a.cost) continue
            byStart[a.start] = Match(a.start, a.end - a.start, a.cost, a.per, bin.votes, a.path)
        }
        return byStart.values.sortedWith(compareBy<Match> { it.cost }.thenBy { it.offset })
    }

    /** Matches for the evidence, plus matches for what follows a leading basmala, which the index only holds as 1:1. */
    private fun locate(): List<Match> {
        val plain = fuzzyMatches(observed, observedConf)
        val prefix = alignSubstring(observed, 0, observed.length, basmala, { it == 0 }, AlignOptions(strictStart = true))
        if (prefix == null || prefix.per > MAX_BASMALA_PER || observed.length - prefix.end < index.gramSize) return plain
        val k = prefix.end
        val after = fuzzyMatches(observed.substring(k), observedConf.subList(k, observedConf.size))
            // Basmala then 1:2 is just 1:1–1:2, which the plain search already found.
            .filter { it.offset != fatihaAyah2 }
            .map { m ->
                m.copy(
                    cost = m.cost + prefix.cost,
                    basmala = true,
                    prefixEnd = k,
                    prefixCost = prefix.cost,
                    path = m.path.map { o -> if (o is AlignmentOp.Del) o else o.withObsAtShift(k) },
                )
            }
        val byStart = LinkedHashMap<Int, Match>()
        for (m in plain + after) {
            if (!byStart.containsKey(m.offset) || byStart[m.offset]!!.cost > m.cost) byStart[m.offset] = m
        }
        return byStart.values.sortedWith(compareBy<Match> { it.cost }.thenBy { it.offset })
    }

    private fun toIds(tokens: List<Char>): IntArray = collapseHeld(tokens).map { tokenIds[it]!! }.toIntArray()

    /**
     * Reranking cost: the CTC likelihood, over every frame of the evidence, of the candidate's alignment read as labels
     * (reference tokens where it matches, substitutes or deletes; observed tokens where it inserts), relative to the
     * decoded sequence. Insertions, a mid-word start and a basmala prefix add their decoded costs on top.
     */
    private fun posteriorCost(frames: PosteriorFrames, m: Match, self: Double): Double {
        val from = observedFrames[0].start
        val to = observedFrames.last().end + 1
        val labels = ArrayList<Char>()
        val prefixEnd = if (m.basmala) m.prefixEnd!! else 0
        for (j in 0 until prefixEnd) labels.add(observed[j])
        var edits = (if (m.basmala) m.prefixCost!! else 0.0) + (if (isWordStart(m.offset)) 0.0 else MID_WORD_START.toDouble())
        val token = { o: AlignmentOp? ->
            when (o) {
                null -> null
                is AlignmentOp.Ins -> o.obs
                is AlignmentOp.Match -> o.ref
                is AlignmentOp.Sub -> o.ref
                is AlignmentOp.Del -> o.ref
            }
        }
        m.path.forEachIndexed { i, o ->
            if (o is AlignmentOp.Ins) {
                // An inserted held sound beside the same sound only lengthens it; the collapse drops it, so don't charge it.
                val lengthens = isHeld(o.obs) && (labels.lastOrNull() == o.obs || token(m.path.getOrNull(i + 1)) == o.obs)
                if (!lengthens) edits += UNIT * observedConf[o.obsAt]
                labels.add(o.obs)
            } else {
                labels.add(
                    when (o) {
                        is AlignmentOp.Match -> o.ref
                        is AlignmentOp.Sub -> o.ref
                        is AlignmentOp.Del -> o.ref
                        else -> throw IllegalStateException("unreachable")
                    },
                )
            }
        }
        val nll = frames.nll(toIds(labels), from, to)
        if (!nll.isFinite() || !self.isFinite()) return m.cost
        return maxOf(0.0, edits + ((nll - self) * UNIT) / NATS_PER_EDIT)
    }

    private fun ref(i: Int): WordRef {
        val w = index.words[i]
        return WordRef(w.surah, w.ayah, w.word)
    }

    private fun normCost(m: Match): Double = m.cost / UNIT / observed.length

    /** Fit takes the better of the two costs: posteriors can rescue a mangled decode, but CTC charges held-sound length in full. */
    private fun fitCost(m: Match): Double = minOf(m.cost, m.decodedCost ?: m.cost) / UNIT / observed.length

    private fun firstWord(m: Match): Int = index.wordAt(m.offset)
    private fun lastWord(m: Match): Int = index.wordAt(m.offset + maxOf(0, m.length - 1))

    private fun toCandidate(m: Match, confidence: Double): PositionCandidate {
        val end = ref(lastWord(m))
        return PositionCandidate(
            word = end,
            span = WordSpan(ref(firstWord(m)), end),
            confidence = confidence,
            cost = normCost(m),
            per = m.per,
            prefix = if (m.basmala) "basmala" else null,
        )
    }

    private fun priorBonus(m: Match): Double {
        val first = index.words[firstWord(m)]
        val opening = first.word == 1 && (first.ayah == 1 || (first.surah == 1 && first.ayah == 2))
        val ab = afterBasmala
        val nextSurah = if (ab != null && opening && first.ayah == 1 && first.surah == ab.from.surah + 1) MAX_PRIOR_BONUS else 0.0
        val openingBonus = (if (surahStart && opening) MAX_PRIOR_BONUS else 0.0) + nextSurah
        // Only once the span reaches "rabbi": "al-hamdu lillahi" alone is also how 6:1, 18:1, 34:1 and 35:1 open.
        if (fatihaNext && first.surah == 1 && first.word == 1 && first.ayah <= 2 && lastWord(m) >= fatihaRabbi) return FATIHA_BONUS
        var bonus = openingBonus
        val pr = priorRange
        if (pr != null) {
            val inside = pr.ranges.any { (from, to) ->
                var hit = false
                for (i in firstWord(m)..lastWord(m)) {
                    val w = index.words[i]
                    if (ayahCompare(AyahRef(w.surah, w.ayah), from) >= 0 && ayahCompare(AyahRef(w.surah, w.ayah), to) <= 0) {
                        hit = true
                        break
                    }
                }
                hit
            }
            if (inside) bonus = maxOf(bonus, MAX_PRIOR_BONUS * minOf(1.0, maxOf(0.0, pr.weight)))
        }
        val pr2 = prior ?: return bonus
        var best = 0.0
        for (i in firstWord(m)..lastWord(m)) {
            val w = index.words[i]
            if (w.surah != pr2.surah) continue
            val d = kotlin.math.abs(w.ayah - pr2.ayah)
            best = maxOf(best, if (d == 0) 1.0 else if (d == 1) 0.5 else 0.0)
        }
        return maxOf(bonus, best * MAX_PRIOR_BONUS * minOf(1.0, maxOf(0.0, pr2.weight)))
    }

    /** Overlapping or touching ranges describe one location, e.g. shifted spans of the same ayah. */
    private fun sameLocation(a: Match, b: Match): Boolean =
        a.offset <= b.offset + b.length && b.offset <= a.offset + a.length

    /** Phoneme offset where the ayah containing `offset` starts. */
    private fun ayahOrigin(offset: Int): Int {
        var i = index.wordAt(maxOf(0, minOf(offset, index.phonemes.length - 1)))
        val w = index.words[i]
        while (i > 0) {
            val prev = index.words[i - 1]
            if (prev.surah != w.surah || prev.ayah != w.ayah) break
            i--
        }
        return index.words[i].start
    }

    /** Ayah start immediately before an ayah start, or -1 at the beginning of the mushaf. */
    private fun previousAyahOrigin(origin: Int): Int {
        val i = index.wordAt(origin)
        if (i <= 0) return -1
        return ayahOrigin(index.words[i - 1].start)
    }

    /**
     * Heard phonemes from the first Quran onset. Phrases the streaming matcher already cut are dropped via
     * `onsetCut`; anything still leading is stripped with the same phrase alignment.
     */
    private fun onsetSpeech(): Pair<String, List<Double>> {
        var text = raw.substring(onsetCut)
        var conf = rawConf.subList(onsetCut, rawConf.size)
        if (phraseList.isEmpty()) return Pair(text, conf)
        var guard = 0
        while (guard < 6 && text.isNotEmpty()) {
            var cut = 0
            for (phrase in phraseList) {
                val n = phrase.p.length
                if (n < 2 || text.length < 2) continue
                val hi = minOf(text.length, ceil(n * 1.6).toInt() + 4)
                // Same alignment as `hear`: the phrase is the query, any reference offset may start it, and the cost cap is
                // PHRASE_MAX_COST. Only a match that begins at the onset is a leading phrase.
                val a = alignSubstring(text, 0, hi, phrase.p, { true })
                if (a == null || a.start != 0 || a.end <= cut || a.cost / UNIT / n > PHRASE_MAX_COST) continue
                val heardEnd = a.path.count { it is AlignmentOp.Match && it.obsAt >= n - 2 }
                if (heardEnd < 2) continue
                cut = a.end
            }
            if (cut == 0) break
            text = text.substring(cut)
            conf = conf.subList(cut, conf.size)
            guard++
        }
        return Pair(text, conf)
    }

    /**
     * Alignment of every onset phone against the contiguous ayahs ending at this candidate. The run walks back an ayah
     * at a time until it covers that speech, so a look-alike tail has to match the heard opening with its own
     * preceding ayah.
     */
    private fun contiguousRunCost(m: Match, speech: String, conf: List<Double>): Double {
        val refEnd = minOf(index.phonemes.length, m.offset + maxOf(m.length, 0))
        if (refEnd <= 0 || speech.isEmpty()) return Double.POSITIVE_INFINITY
        var lo = ayahOrigin(minOf(m.offset, refEnd - 1))
        var guard = 0
        while (refEnd - lo < speech.length && guard < 30) {
            val prev = previousAyahOrigin(lo)
            if (prev < 0 || prev >= lo) break
            lo = prev
            guard++
        }
        val a = alignSubstring(
            index.phonemes, lo, refEnd, speech, isWordStart,
            AlignOptions(strictStart = true, conf = if (conf.size == speech.length) conf else null),
        )
        return a?.cost ?: Double.POSITIVE_INFINITY
    }

    private data class Scored(val m: Match, val score: Double)

    /** Global search over the observed phonemes: the event it supports, the lock if any, and its trace. */
    private fun assess(): AssessResult {
        val frames = posteriors
        val self = if (frames != null) {
            frames.nll(toIds(observed.toList()), observedFrames[0].start, observedFrames.last().end + 1)
        } else 0.0
        val matches = if (frames != null) {
            locate().map { m -> m.copy(cost = posteriorCost(frames, m, self), decodedCost = m.cost) }
        } else locate()
        val scored = matches.map { m -> Scored(m, m.cost - priorBonus(m)) }
            .sortedWith(compareBy<Scored> { it.score }.thenBy { it.m.offset })
        val distinct = ArrayList<Scored>()
        for (c in scored) if (distinct.none { sameLocation(it.m, c.m) }) distinct.add(c)
        val fitting = distinct.filter { it.m.per <= maxPer && fitCost(it.m) <= maxCost }

        val n = observed.length
        val required = UNIT * maxOf(minSeparation, separationScale / n)

        data class Decision(
            val event: PositionEvent,
            val lock: Match?,
            val trace: (TrackerState?) -> TrackerTrace,
            val top: Match?,
            val state: TrackerState,
        )

        val decide = { pool: List<Scored> ->
            // How far each candidate leads the best other fitting candidate; negative when it trails.
            val lead = { c: Scored ->
                pool.filter { it !== c }.minOfOrNull { it.score - c.score } ?: Double.POSITIVE_INFINITY
            }
            val top = pool.firstOrNull()
            val separated = top != null && lead(top) >= required
            val terms = { c: Scored ->
                TraceDecision(
                    fit = maxOf(0.0, 1 - c.m.per / maxPer) * maxOf(0.0, 1 - fitCost(c.m) / maxCost),
                    separation = minOf(1.0, maxOf(0.0, 0.5 + lead(c) / (2 * required))),
                    leadEdits = lead(c) / UNIT,
                    requiredEdits = required / UNIT,
                )
            }
            val confidence = { c: Scored ->
                val t = terms(c)
                t.fit * t.separation
            }
            val enough = n >= minPhonemes && top != null &&
                lastWord(top.m) - firstWord(top.m) + 1 >= minWords && confidence(top) >= minLockConfidence
            val state: TrackerState = when {
                top == null -> "lost"
                !separated -> "ambiguous"
                !enough -> "acquiring"
                else -> "locked"
            }
            // Ambiguous lists every candidate within the margin of the best; otherwise only the best.
            val shown = when {
                top == null -> emptyList()
                separated -> listOf(top)
                else -> pool.filter { it.score - top.score < required }
            }
            val candidates = shown.map { toCandidate(it.m, confidence(it)) }
            val event = PositionEvent(
                timeMs = (lastFrame * (frameMs ?: 0)).toDouble(),
                referenceVersion = index.version,
                expected = if (state == "locked") candidates[0].word else null,
                observed = candidates,
                state = state,
            )
            val trace = { from: TrackerState? ->
                TrackerTrace(
                    shortlist = scored.map { s ->
                        TraceCandidate(
                            span = toCandidate(s.m, 0.0).span,
                            offset = s.m.offset,
                            length = s.m.length,
                            votes = s.m.votes,
                            cost = normCost(s.m),
                            per = s.m.per,
                        )
                    },
                    transition = Pair(from, state),
                    path = scored.firstOrNull()?.m?.path,
                    decision = if (top != null) terms(top) else null,
                )
            }
            Decision(
                event,
                if (state == "locked") top!!.m else null,
                trace,
                if (top != null && n >= minPhonemes && confidence(top) > 0) top.m else null,
                state,
            )
        }

        var decision = decide(fitting)
        // First lock only. A suffix window can prefer a look-alike whose own ayah matches the tail. Score each
        // candidate on the contiguous ayahs back to the speech onset, and drop the commit when that winner is beaten
        // by the same margin that would commit a lock. A winner that is not beaten is left exactly as ranked.
        // An opening that already starts on an ayah's first word is the clean case: the heard onset is that ayah, and
        // the candidate stays. The veto is for a tail match, whose own preceding ayah has to cover the onset.
        val ayahStart = decision.state == "locked" && index.words[firstWord(fitting[0].m)].word == 1
        if (decision.state == "locked" && !everLocked && fitting.size > 1 && !ayahStart) {
            val speech = onsetSpeech()
            if (speech.first.length >= minPhonemes) {
                val costs = fitting.map { contiguousRunCost(it.m, speech.first, speech.second) }
                val best = costs.minOrNull() ?: Double.POSITIVE_INFINITY
                if (costs[0] - best >= required) {
                    val kept = fitting.filterIndexed { i, _ -> costs[i] - best < required }
                    if (kept.isNotEmpty()) decision = decide(kept)
                }
            }
        }
        return AssessResult(decision.event, decision.lock, decision.trace, decision.top)
    }

    private fun marked(event: PositionEvent): PositionEvent = if (arm) event.copy(spanHold = true) else event

    private fun emit(event: PositionEvent, trace: (TrackerState?) -> TrackerTrace): PositionEvent {
        val from = last?.state
        val out = marked(event)
        last = out
        return if (options.trace) out.copy(trace = trace(from)) else out
    }

    private fun bare(event: PositionEvent): String = event.copy(spanHold = false, timeMs = 0.0, trace = null).toString()
    private fun unchanged(event: PositionEvent): Boolean = last != null && bare(last!!) == bare(event)

    private fun evaluate(final: Boolean): List<PositionEvent> {
        if (observed.isEmpty()) return emptyList()
        val a = assessOutsidePhrases()
        val lock = a.lock
        if (lock != null && !everLocked && longestSpanAt(allSpans, lastWord(lock)) != null) arm = true
        if (unchanged(a.event) && !final) return emptyList()
        if (lock != null && !final) lockedOn = lock
        return listOf(emit(a.event, a.trace))
    }

    private fun quietTrace(from: TrackerState?, to: TrackerState, shortlist: List<TraceCandidate> = emptyList()): TrackerTrace =
        TrackerTrace(shortlist, Pair(from, to), null, null)

    private fun observe(ph: DecodedPhoneme) {
        observedFrames.add(FrameRange(ph.start, ph.end))
        observed += ph.p
        observedConf.add(ph.conf)
        if (!everLocked) {
            raw += ph.p
            rawConf.add(ph.conf)
        }
        lastFrame = maxOf(lastFrame, ph.end + 1)
        val ex = excursion
        if (ex != null) {
            ex.phones += ph.p
            if (ex.phones.length > 480) {
                ex.phones = ex.phones.substring(ex.phones.length - 480)
                ex.trimmed = true
            }
        }
    }

    /** Keep only the latest `n` observed phonemes. */
    private fun keepLast(n: Int) {
        val drop = observed.length - n
        if (drop <= 0) return
        observed = observed.substring(drop)
        observedConf = ArrayList(observedConf.subList(drop, observedConf.size))
        repeat(drop) { observedFrames.removeAt(0) }
    }

    /**
     * Record one phoneme for phrase detection, with the local search's state before it and its cost; returns how many
     * of the latest phonemes form a whole phrase that ends here, and which.
     */
    private data class HeardPhrase(val name: String, val length: Int)

    private fun hear(p: Char, state: LocalSearchState?, cost: Double): HeardPhrase? {
        if (phraseList.isEmpty()) return null
        heard += p
        heardCost.add(cost)
        heardState.add(state)
        if (heard.length > HEARD) {
            heard = heard.substring(1)
            heardCost = ArrayList(heardCost.subList(1, heardCost.size))
            heardState = ArrayList(heardState.subList(1, heardState.size))
        }
        for (phrase in phraseList) {
            val n = phrase.p.length
            val lo = maxOf(0, heard.length - ceil(n * 1.6).toInt() - 4)
            val a = alignSubstring(heard, lo, heard.length, phrase.p, { true })
            if (a == null || a.end < heard.length - 1 || a.cost / UNIT / n > PHRASE_MAX_COST) continue
            // The phrase must be finished: its last two phonemes heard, not just cheap to leave out.
            val heardEnd = a.path.count { it is AlignmentOp.Match && it.obsAt >= n - 2 }
            if (heardEnd < 2) continue
            return HeardPhrase(phrase.name, heard.length - a.start)
        }
        return null
    }

    /** The latest phonemes are a phrase still being said: hold off locking, a prefix like "a'udhu billahi" is Quran text too. */
    private fun phraseInProgress(): Boolean {
        for (phrase in phraseList) {
            val n = phrase.p.length
            var k = minOf(n - 2, heard.length)
            val floor = maxOf(PHRASE_PREFIX.toDouble(), PHRASE_PREFIX_SHARE * n)
            while (k >= floor) {
                val a = alignSubstring(phrase.p, 0, n - 2, heard.substring(heard.length - k), { it == 0 }, AlignOptions(strictStart = true))
                if (a != null && a.cost / UNIT / k <= PHRASE_MAX_COST / 2) return true
                k -= 2
            }
        }
        return false
    }

    /** The window's latest phonemes restate its start: a restart still being said, not yet a repeat to collapse. */
    private fun repeatInProgress(): Boolean {
        var k = minOf(MAX_REPEAT, observed.length shr 1)
        while (k >= RESTART_PREFIX) {
            val a = alignSubstring(observed, 0, observed.length - k, observed.substring(observed.length - k), { it == 0 }, AlignOptions(strictStart = true))
            if (a != null && a.cost / UNIT / k <= REPEAT_MAX_COST) return true
            k--
        }
        return false
    }

    /** Assess, but never lock while a phrase or a repeat is in progress. */
    private fun assessOutsidePhrases(): AssessResult {
        val a = assess()
        if (a.lock == null || phraseList.isEmpty() || (!phraseInProgress() && !repeatInProgress())) return a
        val event = a.event.copy(state = "acquiring", expected = null)
        return AssessResult(
            event,
            null,
            { from -> a.trace(from).copy(transition = Pair(from, "acquiring")) },
            null,
        )
    }

    /**
     * An immediately repeated block at the end of the window ("Alif Lam Mim" twice) is a repetition, not two ayahs: keep
     * one copy, so global search doesn't prefer passages that happen to continue like the repeat.
     */
    private fun collapseRepeat() {
        var n = minOf(MAX_REPEAT, observed.length shr 1)
        while (n >= MIN_REPEAT) {
            val tail = observed.substring(observed.length - n)
            val before = observed.substring(observed.length - 2 * n, observed.length - n)
            val a = alignSubstring(before, 0, n, tail, { it == 0 }, AlignOptions(strictStart = true))
            if (a != null && a.end >= n - 1 && a.cost / UNIT / n <= REPEAT_MAX_COST) {
                val at = observed.length - 2 * n
                val collapsed = observed.substring(0, at) + tail
                val conf = observedConf.subList(0, at) + observedConf.subList(observed.length - n, observedConf.size)
                // Quran text can itself contain a near-repeat (من أجر إن أجري). Removing it would manufacture evidence
                // for a different passage. Keep the whole phrase whenever it fits the reference at least as well.
                val cost = { text: String, confidence: List<Double> ->
                    fuzzyMatches(text, confidence).minOfOrNull { it.cost } ?: Double.POSITIVE_INFINITY
                }
                if (cost(observed, observedConf) <= cost(collapsed, conf)) {
                    n--
                    continue
                }
                observed = collapsed
                observedConf = ArrayList(conf)
                repeat(n) { observedFrames.removeAt(at) }
                return
            }
            n--
        }
    }

    private fun forget() {
        heard = ""
        heardCost = ArrayList()
        heardState = ArrayList()
    }

    /** Drop the latest `n` observed phonemes. */
    private fun dropLast(n: Int) {
        val keep = maxOf(0, observed.length - n)
        observed = observed.substring(0, keep)
        observedConf = ArrayList(observedConf.subList(0, keep))
        while (observedFrames.size > keep) observedFrames.removeAt(observedFrames.size - 1)
    }

    private fun startLocal(m: Match) {
        local = LocalSearch(index, m.offset + m.length, m.offset, Math.round(m.per * m.length).toInt())
        weak = false
        pending = null
        reacquiring = false
        holding = null
        if (everLocked) arm = false
        everLocked = true
        raw = ""
        rawConf = ArrayList()
        onsetCut = 0
        surahStart = false
        basmalaHeard = false
        fatihaNext = false
        takbirHeard = false
        afterBasmala = null
        afterPhrase = false
        twin = null
        keepLast(0)
    }

    /** Memorization holds the expected bookmark where a jump left it; free mode follows the reciter. */
    private fun expectedFor(word: WordRef): WordRef = frozen ?: word

    /** Neither a jump nor a passage change happened, so the bookmark stays where local tracking last trusted it. */
    private fun expectedWhileLost(): WordRef? = frozen ?: steady

    private fun near(m: Match, offset: Int): Boolean {
        val a = index.words[lastWord(m)]
        val b = index.words[index.wordAt(maxOf(0, offset - 1))]
        return a.surah == b.surah && kotlin.math.abs(a.ayah - b.ayah) <= NEAR_AYAHS
    }

    /** Same passage the local search would keep: not a relocation. */
    private fun nearWord(a: WordRef, b: WordRef): Boolean =
        a.surah == b.surah && kotlin.math.abs(a.ayah - b.ayah) <= NEAR_AYAHS

    private fun spokenStep(wi: Int, dir: Int): Int {
        if (wi < 0) return -1
        var k = wi + dir
        while (k >= 0 && k < index.words.size) {
            if (index.words[k].end > index.words[k].start) return k
            k += dir
        }
        return -1
    }

    /** Heard words of a destination match: reference word, and the phonemes the alignment heard for it. */
    private fun destinationWords(m: Match): List<DestinationWord> {
        class Group(val wi: Int, var heard: String)
        val groups = ArrayList<Group>()
        var cur: Group? = null
        var lead = ""
        for (op in m.path) {
            if (op is AlignmentOp.Ins) {
                val c = cur
                if (c != null) c.heard += op.obs else lead += op.obs
                continue
            }
            val refAt = when (op) {
                is AlignmentOp.Match -> op.refAt
                is AlignmentOp.Sub -> op.refAt
                is AlignmentOp.Del -> op.refAt
                else -> 0
            }
            val wi = index.wordAt(maxOf(0, minOf(index.phonemes.length - 1, m.offset + refAt)))
            if (cur == null || cur.wi != wi) {
                cur = Group(wi, lead)
                lead = ""
                groups.add(cur)
            }
            when (op) {
                is AlignmentOp.Match -> cur.heard += op.ref
                is AlignmentOp.Sub -> cur.heard += op.obs
                else -> {}
            }
        }
        if (lead.isNotEmpty()) {
            if (groups.isNotEmpty()) groups[0].heard = lead + groups[0].heard
            else groups.add(Group(-1, lead))
        }
        return groups.filter { it.heard.isNotEmpty() && it.wi >= 0 }.map { g ->
            DestinationWord(
                index.phonemes.substring(index.words[g.wi].start, index.words[g.wi].end),
                g.heard,
            )
        }
    }

    private fun anchorWordsFrom(wi: Int, n: Int): List<String> {
        val out = ArrayList<String>()
        var k = wi
        while (k < index.words.size && out.size < n) {
            val w = index.words[k]
            if (w.end > w.start) out.add(index.phonemes.substring(w.start, w.end))
            k++
        }
        return out
    }

    /**
     * A distant lock relocates only when enough heard words fit it better than the anchor plus one inserted word.
     * The anchor start is whichever of the cursor and its neighbours that hypothesis explains cheapest: the cursor
     * sits on the word before a shared word as often as on the shared word itself.
     */
    private fun relocationCommits(m: Match, anchorWi: Int): Boolean {
        val heard = destinationWords(m)
        if (heard.size < minWords) return false
        val seeds = listOf(
            anchorWi,
            spokenStep(anchorWi, -1),
            spokenStep(anchorWi, 1),
            spokenStep(spokenStep(anchorWi, 1), 1),
        ).filter { it >= 0 }
        var best: RelocationFit? = null
        for (seed in seeds) {
            val fit = relocationSupport(anchorWordsFrom(seed, heard.size + 1), heard, minSeparation, separationScale)
            if (fit != null && (best == null || fit.total < best.total)) best = fit
        }
        return best != null && best.count >= minWords
    }

    /**
     * The anchor stays locked and relocation search continues. One event tells the checker to downgrade issues open
     * now; `last` stays the plain lock, so later re-emits of the anchor compare unchanged.
     */
    private fun beginHold(): PositionEvent {
        val t = twin
        if (t != null) {
            twinSettled.add(spanId(t.span))
            twin = null
        }
        if (memorization) frozen = frozen ?: steady
        val anchor = last!!
        holding = anchor
        local = null
        weak = false
        pending = null
        reacquiring = true
        val event = anchor.copy(timeMs = (lastFrame * (frameMs ?: 0)).toDouble(), hold = "start")
        return if (options.trace) event.copy(trace = quietTrace(anchor.state, "locked")) else event
    }

    private fun lostEvent(timeMs: Double, trace: (TrackerState?) -> TrackerTrace): PositionEvent =
        emit(
            PositionEvent(
                timeMs = timeMs,
                referenceVersion = index.version,
                expected = expectedWhileLost(),
                observed = emptyList(),
                state = "lost",
            ),
            trace,
        )

    /** Next word that has its own phonemes. Assimilated words are skipped. */
    private fun spoken(wi: Int): Int {
        var k = wi
        while (k < index.words.size && index.words[k].end == index.words[k].start) k++
        return k
    }

    private fun ayahStartOf(wi: Int): Int {
        var k = wi
        val w = index.words[wi]
        while (k > 0 && index.words[k - 1].surah == w.surah && index.words[k - 1].ayah == w.ayah) k--
        return k
    }

    private fun ayahEndOf(wi: Int): Int {
        var k = wi + 1
        val w = index.words[wi]
        while (k < index.words.size && index.words[k].surah == w.surah && index.words[k].ayah == w.ayah) k++
        return k
    }

    private fun phonesBetween(from: Int, to: Int): String {
        if (from < 0 || to <= from || to > index.words.size) return ""
        return index.phonemes.substring(index.words[from].start, index.words[to - 1].end)
    }

    private fun skeleton(from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to) sb.append(letterSkeleton(index.wordText.getOrNull(i) ?: "")).append('\u0000')
        return sb.toString()
    }

    /**
     * A jump off the in-order ayah onto a parallel copy. Closed when a later lock is that copy's in-order
     * continuation and the skipped-over text fits the excursion's phones no worse than the copy does.
     * Exact copies tie. A one-word variant pays that word's edits, so the heard copy is the cheaper one.
     */
    private fun beginExcursion(fromWi: Int, m: Match) {
        val destWi = firstWord(m)
        if (fromWi < 0 || destWi < 0) return
        val ex = excursion
        if (ex != null && destWi >= ex.dest && destWi < ex.dest + ex.length) return
        val at = lastWord(m)
        val span = longestSpanAt(allSpans, destWi)
        if (span != null) {
            val dest = span.starts.firstOrNull { destWi >= it && destWi < it + span.length }
            val home = span.starts.firstOrNull { fromWi >= it && fromWi < it + span.length }
            if (dest != null && home != null && dest != home) {
                excursion = ExcursionState(
                    home, dest, span.length,
                    maxOf(0, minOf(span.length - 1, at - dest)), "", false,
                )
                return
            }
        }
        val homeLo = if (fromWi < index.words.size) ayahEndOf(fromWi) else -1
        val destLo = if (destWi < index.words.size) ayahStartOf(destWi) else -1
        if (homeLo >= 0 && homeLo < index.words.size && destLo >= 0 && destLo != homeLo) {
            val homeHi = ayahEndOf(homeLo)
            val destHi = ayahEndOf(destLo)
            val length = homeHi - homeLo
            if (length > 0 && destHi - destLo == length && skeleton(homeLo, homeHi) == skeleton(destLo, destHi)) {
                excursion = ExcursionState(
                    homeLo, destLo, length,
                    maxOf(0, minOf(length - 1, at - destLo)), "", false,
                )
                return
            }
        }
        excursion = null
    }

    /** Retro locked event, or null when this lock does not close the excursion. */
    private fun finishExcursion(destWi: Int): PositionEvent? {
        val ex = excursion
        if (ex == null || ex.trimmed || destWi < 0) return null
        if (destWi >= ex.dest && destWi < ex.dest + ex.length) return null
        val cont = spoken(ex.home + ex.length)
        // Landing here is the commit: the search already preferred this continuation by the commit margin.
        if (destWi < cont || destWi >= cont + 4) return null
        val from = ex.entry
        val homeText = phonesBetween(ex.home + from, ex.home + ex.length)
        val destText = phonesBetween(ex.dest + from, ex.dest + ex.length)
        val homeCost = anchorFitCost(homeText, ex.phones)
        val destCost = anchorFitCost(destText, ex.phones)
        // Exact copies tie. A one-word variant pays that word, so the copy that was heard stays cheaper.
        if (homeCost == null || destCost == null || homeCost > destCost) return null
        val end = ex.home + ex.length - 1
        val event = PositionEvent(
            timeMs = (lastFrame * (frameMs ?: 0)).toDouble(),
            referenceVersion = index.version,
            expected = expectedFor(ref(ex.home)),
            observed = listOf(
                PositionCandidate(
                    word = ref(end),
                    span = WordSpan(ref(ex.home), ref(end)),
                    confidence = 1.0,
                    cost = 0.0,
                    per = 0.0,
                ),
            ),
            state = "locked",
            relocated = Pair(ref(ex.dest), ref(ex.home)),
        )
        return emit(event) { fromState -> quietTrace(fromState, "locked") }
    }

    private fun takeJump(fromWi: Int, m: Match): PositionEvent? {
        val closing = finishExcursion(firstWord(m))
        if (closing != null) {
            excursion = null
            return closing
        }
        beginExcursion(fromWi, m)
        return null
    }

    /**
     * While the lock sits in a slot-differing span, score heard phones against each member's next word. The current
     * copy wins when its own continuation fits. Another copy wins only when the heard word fits that continuation and
     * beats every other member by the commit margin. Held slot accusations then follow that copy.
     */
    private fun considerTwin(p: Char, pos: LocalPosition): PositionEvent? {
        val wi = index.wordAt(maxOf(0, pos.offset - 1))
        val outside = longestSpanAt(allSpans, wi) == null
        if (twin == null && !arm) return null
        if (twin == null) {
            if (outside) {
                arm = false
                return null
            }
            val span = longestSpanAt(allSpans, wi)
            if (span == null || twinSettled.contains(spanId(span))) return null
            val memberStart = span.starts.firstOrNull { wi >= it && wi < it + span.length } ?: return null
            val endWi = memberStart + span.length
            if (endWi >= index.words.size) return null
            twin = TwinState(span, memberStart, index.words[endWi].start, "")
        }
        val tw = twin!!
        // A clean phone still inside the span belongs to the shared text, not to the continuation.
        if (pos.offset <= tw.endPhone && pos.stepCost < 1) tw.buf = "" else {
            tw.buf += p
            if (tw.buf.length > 96) tw.buf = tw.buf.substring(tw.buf.length - 96)
        }
        val span = tw.span
        val candidates = span.starts.map { st ->
            val w = spoken(st + span.length)
            val word = index.words.getOrNull(w)
            Pair(st, if (word == null) "" else index.phonemes.substring(word.start, word.end))
        }
        val pick = continuationDecision(
            tw.buf, candidates, tw.memberStart,
            { n -> commitMargin(n, minSeparation, separationScale) }, maxCost,
        )
        // The continuation settled the copy. A later global return must not rebind it again.
        if (pick != null) excursion = null
        // Past the first continuation word with no unique other copy: keep the locked one and let the window move on.
        // The continuation word itself sits outside the span; the open span still gets to judge it.
        if (pick == null && wi < tw.memberStart + tw.span.length + 4) return null
        if (pick == null) {
            val fromWi = tw.memberStart
            twinSettled.add(spanId(tw.span))
            twin = null
            if (outside) arm = false
            val word = ref(wi)
            emittedSinceLock = true
            return emit(
                PositionEvent(
                    timeMs = (lastFrame * (frameMs ?: 0)).toDouble(),
                    referenceVersion = index.version,
                    expected = expectedFor(word),
                    observed = listOf(
                        PositionCandidate(
                            word,
                            WordSpan(ref(index.wordAt(pos.origin)), word),
                            1.0, pos.recentCost, 0.0,
                        ),
                    ),
                    state = "locked",
                    relocated = Pair(ref(fromWi), ref(fromWi)),
                ),
            ) { from -> quietTrace(from, "locked") }
        }
        val fromWi = tw.memberStart
        val toWi = pick.id
        twinSettled.add(spanId(span))
        twin = null
        if (outside) arm = false
        val at = if (toWi == fromWi) wi else spoken(toWi + span.length)
        val origin = if (toWi == fromWi) index.wordAt(pos.origin) else toWi
        val word = ref(at)
        val event = PositionEvent(
            timeMs = (lastFrame * (frameMs ?: 0)).toDouble(),
            referenceVersion = index.version,
            expected = expectedFor(word),
            observed = listOf(
                PositionCandidate(
                    word,
                    WordSpan(ref(origin), word),
                    1.0, pos.recentCost, 0.0,
                ),
            ),
            state = "locked",
            relocated = Pair(ref(fromWi), ref(toWi)),
        )
        emittedSinceLock = true
        if (toWi != fromWi) {
            val dest = index.words.getOrNull(at) ?: return null
            stepCosts.clear()
            startLocal(Match(dest.end, 0, 0.0, 0.0, 0, emptyList()))
        }
        return emit(event) { from -> quietTrace(from, "locked") }
    }

    /** The local search's position as an event, or null when its word and run start haven't changed. */
    private fun localEvent(shortlist: List<TraceCandidate>): PositionEvent? {
        val pos = local!!.position()
        val word = ref(index.wordAt(pos.offset - 1))
        val length = pos.offset - pos.origin
        val per = if (length != 0) pos.edits.toDouble() / length else 0.0
        val confidence = maxOf(0.0, 1 - per / maxPer) * maxOf(0.0, 1 - pos.recentCost / maxCost)
        val candidate = PositionCandidate(
            word,
            WordSpan(ref(index.wordAt(pos.origin)), word),
            confidence, pos.recentCost, per,
        )
        val prev = last!!.observed[0]
        if (last!!.state == "locked" && prev.word == word && prev.span.start == candidate.span.start) return null
        val newRun = prev.span.start != candidate.span.start
        // A weak local search jumps around chasing evidence that may belong elsewhere; only its current run may advance.
        if (weak && newRun) return null
        // A skip or restart shows once its landing fits: at a jump's onset, local skips chase audio that belongs elsewhere.
        if (newRun && stepCosts.size >= CONFIRM && stepCosts.takeLast(CONFIRM).sum() / CONFIRM > CONFIRM_COST) return null
        val event = PositionEvent(
            timeMs = (lastFrame * (frameMs ?: 0)).toDouble(),
            referenceVersion = index.version,
            expected = expectedFor(word),
            observed = listOf(candidate),
            state = "locked",
        )
        emittedSinceLock = true
        return emit(event) { from -> quietTrace(from, "locked", shortlist) }
    }

    /**
     * Global search over the latest phonemes, until it locks somewhere. While `holding`, the anchor stays locked:
     * non-locked assessments are not emitted. A lock off the anchor emits `lost` and then the new place; a lock on
     * the same passage emits only that lock, so tokens heard in the gap stay in the checker's window.
     */
    private fun reacquire(): List<PositionEvent> {
        keepLast(WINDOW)
        if (phraseList.isNotEmpty()) collapseRepeat()
        val a = assessOutsidePhrases()
        // After a basmala ends a surah, the next surah is the expected continuation: its opening on top is enough.
        // Al-Fatiha excepted: in prayer any surah follows it.
        val ab = afterBasmala
        if (a.lock == null && ab != null && ab.from.surah != 1 && a.top != null) {
            val first = index.words[firstWord(a.top)]
            if (first.surah == ab.from.surah + 1 && first.ayah == 1) {
                a.lock = a.top
                val c = toCandidate(a.top, a.event.observed.firstOrNull()?.confidence ?: 0.0)
                a.event = a.event.copy(state = "locked", observed = listOf(c), expected = c.word)
            }
        }
        val lock = a.lock
        if (lock != null) {
            val anchorWord = holding?.observed?.firstOrNull()?.word
            val dest = a.event.observed.firstOrNull()?.word
            val release = holding != null && anchorWord != null && dest != null && !nearWord(anchorWord, dest)
            val anchorWi = if (anchorWord != null) {
                index.words.indexOfFirst { it.surah == anchorWord.surah && it.ayah == anchorWord.ayah && it.word == anchorWord.word }
            } else -1
            // A lookalike of the anchor plus one inserted word has not earned the move. Keep searching.
            if (!(release && anchorWi >= 0 && !relocationCommits(lock, anchorWi))) {
                if (memorization && afterBasmala != null) {
                    val first = index.words[firstWord(lock)]
                    if (!(first.surah == afterBasmala!!.from.surah + 1 && first.ayah == 1)) frozen = frozen ?: steady
                }
                afterBasmala = null
                val released = if (release) listOf(lostEvent(a.event.timeMs) { from -> quietTrace(from, "lost") }) else emptyList()
                val fromWi = if (anchorWi >= 0) anchorWi else -1
                val rebind = if (fromWi >= 0) takeJump(fromWi, lock) else null
                startLocal(lock)
                val emitted = emit(a.event.copy(expected = expectedFor(a.event.observed[0].word)), a.trace)
                return released + (if (rebind != null) listOf(rebind) else emptyList()) + listOf(emitted)
            }
        }
        // Between surahs the last position stays on screen, unless nothing locks for a while.
        val ab2 = afterBasmala
        if (ab2 != null) {
            ab2.held++
            if (ab2.held < WINDOW) return emptyList()
        }
        // Junk that fits nowhere would otherwise favour passages with text before the recited phrase to absorb it.
        if (a.event.state == "lost") keepLast(LOOKBACK)
        val h = holding
        if (h != null) {
            val event = h.copy(timeMs = (lastFrame * (frameMs ?: 0)).toDouble())
            return if (unchanged(event)) emptyList() else listOf(emit(event) { from -> quietTrace(from, "locked") })
        }
        val event = a.event.copy(expected = expectedWhileLost())
        return if (unchanged(event)) emptyList() else listOf(emit(event, a.trace))
    }

    /**
     * Phoneme by phoneme after the first lock. Local tracking emits one event per change of word or run start. When its
     * fit degrades, global search over the latest phonemes runs alongside: a distant global lock that holds for
     * `jumpPhonemes` commits a jump, and nothing fitting anywhere for long enough holds the anchor while the search
     * continues. `lost` is emitted when that search locks off the anchor, or when the session ends still there.
     */
    private fun follow(evidence: PhonemeEvidence): List<EngineEvent> {
        val events = ArrayList<EngineEvent>()
        for (ph in evidence.phonemes) {
            observe(ph)
            if (local == null) {
                val heardPhrase = hear(ph.p, null, Double.NaN)
                if (heardPhrase != null) {
                    forget()
                    dropLast(heardPhrase.length)
                    if (heardPhrase.name == "basmala") surahStart = true
                    if (heardPhrase.name == "takbir") fatihaNext = true
                    continue
                }
                events.addAll(reacquire())
                continue
            }
            val before = local!!.save()
            local!!.step(ph.p)
            stepCosts.add(local!!.position().stepCost)
            if (stepCosts.size > CONFIRM) stepCosts.removeAt(0)
            val heardPhrase = hear(ph.p, before, local!!.position().stepCost)
            if (heardPhrase != null) {
                val from = heardCost.size - heardPhrase.length
                val cost = heardCost.subList(from, heardCost.size).sum() / heardPhrase.length
                if (cost >= PHRASE_LOCAL_COST) {
                    // Not Quran text where we are: rewind the local search to before the phrase, as if it was never heard.
                    if (heardPhrase.name == "basmala") {
                        // A surah starts, not necessarily the next one: hand over to global search, favouring openings.
                        afterBasmala = AfterBasmalaState(last!!.observed[0].word, 0)
                        if (memorization) steady = last!!.expected
                        local = null
                        reacquiring = true
                        surahStart = true
                        forget()
                        keepLast(0)
                        continue
                    }
                    local!!.restore(heardState[from]!!)
                    if (heardPhrase.name == "takbir") fatihaNext = true
                    afterPhrase = true
                    forget()
                    // The phrase is a boundary: global search restarts after it.
                    keepLast(0)
                    val e = localEvent(emptyList())
                    if (e != null) events.add(e)
                    continue
                }
            }
            val pos = local!!.position()
            val relocated = considerTwin(ph.p, pos)
            if (relocated != null) {
                events.add(relocated)
                continue
            }
            if (!weak && pos.recentCost > WEAK_COST) {
                weak = true
                steady = expectedHistory.firstOrNull() ?: last!!.expected
            } else if (weak && pos.recentCost <= WEAK_COST) {
                weak = false
                pending = null
                afterPhrase = false
            }
            var shortlist: List<TraceCandidate> = emptyList()
            if (!weak) keepLast(LOOKBACK) else {
                keepLast(WINDOW)
                val a = assessOutsidePhrases()
                if (options.trace) shortlist = a.trace(null).shortlist
                val lock = a.lock
                if (lock != null && !near(lock, pos.offset)) {
                    val p = pending
                    pending = PendingMatch(
                        lock,
                        if (p != null && sameLocation(p.m, lock)) p.held + 1 else 1,
                    )
                } else pending = null
                val pd = pending
                if (pd != null && pd.held >= (if (afterPhrase) 1 else jumpPhonemes) &&
                    relocationCommits(pd.m, index.wordAt(maxOf(0, pos.offset - 1)))
                ) {
                    if (memorization) frozen = frozen ?: steady
                    val fromWi = index.wordAt(maxOf(0, pos.offset - 1))
                    val rebind = takeJump(fromWi, pd.m)
                    if (rebind != null) events.add(rebind)
                    startLocal(pd.m)
                } else if (pd == null && a.event.state == "lost" && pos.recentCost > maxCost && observed.length >= LOST_AFTER) {
                    // Nothing has committed elsewhere. Keep this anchor until the search locks off it, or the session ends.
                    events.add(beginHold())
                    continue
                }
            }
            val e = localEvent(shortlist)
            if (e != null) events.add(e)
            expectedHistory.add(last!!.expected)
            if (expectedHistory.size > WEAK_LAG) expectedHistory.removeAt(0)
        }
        return events
    }

    /**
     * Before a new chunk: drop all but `MAX_HISTORY` phonemes of earlier chunks, or all but `LOOKBACK` after `lost`, and
     * the posterior rows before what's kept. Done here rather than after evaluating, so `end` sees what the last push saw.
     */
    private fun trimHistory() {
        if (phraseCut != 0) {
            if (!everLocked) onsetCut += phraseCut
            keepLast(observed.length - phraseCut)
            phraseCut = 0
        }
        if (basmalaHeard) surahStart = true
        if (takbirHeard) fatihaNext = true
        val keep = if (last?.state == "lost") LOOKBACK else MAX_HISTORY
        if (observed.length <= keep) return
        keepLast(keep)
        if (observedFrames.isNotEmpty()) posteriors?.dropBefore(observedFrames[0].start)
    }

    /**
     * Leading junk (a takbir, a cough) costs every candidate the same insertions, which can hold a stream in
     * `acquiring` long after the recitation itself is clear. So when the history doesn't lock, try its latest phonemes.
     */
    private fun evaluateSuffix(fresh: Int): List<PositionEvent> {
        val keep = maxOf(WINDOW, fresh)
        if (observed.length <= keep) return emptyList()
        val savedObserved = observed
        val savedConf = observedConf
        val savedFrames = observedFrames.toList()
        keepLast(keep)
        val a = assessOutsidePhrases()
        val lock = a.lock
        if (lock != null) {
            lockedOn = lock
            if (!everLocked && longestSpanAt(allSpans, lastWord(lock)) != null) arm = true
            return listOf(emit(a.event, a.trace))
        }
        observed = savedObserved
        observedConf = ArrayList(savedConf)
        observedFrames.clear()
        observedFrames.addAll(savedFrames)
        return emptyList()
    }

    /** One acquisition step: a push's phonemes, or one `acquireMs` cell's, searched globally. */
    private fun acquire(phonemes: List<DecodedPhoneme>): List<EngineEvent> {
        trimHistory()
        for (ph in phonemes) {
            observe(ph)
            val heardPhrase = hear(ph.p, null, Double.NaN)
            if (heardPhrase != null) {
                forget()
                phraseCut = observed.length
                basmalaHeard = basmalaHeard || heardPhrase.name == "basmala"
                takbirHeard = takbirHeard || heardPhrase.name == "takbir"
            }
        }
        val first = pushes++ == 0
        if (!first && phraseList.isNotEmpty()) collapseRepeat()
        val events = ArrayList<EngineEvent>()
        events.addAll(evaluate(false))
        if (lockedOn == null) events.addAll(evaluateSuffix(phonemes.size))
        if (lockedOn != null) {
            lockEvent = events.lastOrNull() as? PositionEvent
            startLocal(lockedOn!!)
            posteriors = null
        }
        return events
    }

    private fun endLocal(): List<PositionEvent> {
        val timeMs = (lastFrame * (frameMs ?: 0)).toDouble()
        // The search never committed. The anchor was held; give it up now.
        if (holding != null) {
            holding = null
            return listOf(lostEvent(timeMs) { from -> quietTrace(from, "lost") })
        }
        // Nothing followed the lock: its event, with the global search's trace, is still the answer.
        if (local != null && !emittedSinceLock) {
            val e = lockEvent!!.copy(timeMs = timeMs)
            return listOf(
                if (e.trace != null) e.copy(trace = e.trace.copy(transition = Pair("locked", "locked"))) else e,
            )
        }
        val final = last!!.copy(timeMs = timeMs)
        return listOf(
            if (options.trace) final.copy(trace = quietTrace(final.state, final.state)) else final,
        )
    }

    fun push(evidence: PhonemeEvidence): List<EngineEvent> {
        validate(evidence)
        frameMs = evidence.frameMs
        if (evidence.posteriors != null) withPosteriors = true else decodedOnly = true
        if (local != null || reacquiring) return follow(evidence)
        if (evidence.posteriors != null) {
            if (posteriors == null) posteriors = PosteriorFrames(index.tokens.size)
            posteriors!!.append(evidence.posteriors)
        }
        val acq = options.acquireMs
        if (acq == null) return acquire(evidence.phonemes)
        val events = ArrayList<EngineEvent>()
        val phs = evidence.phonemes
        val fm = frameMs!!
        for (i in phs.indices) {
            val at = (phs[i].start * fm) / acq
            while (at > cellAt && local == null) {
                events.addAll(acquire(cell))
                cell = ArrayList()
                cellAt++
            }
            if (local != null) {
                return events + follow(evidence.copy(phonemes = phs.subList(i, phs.size), posteriors = null))
            }
            cell.add(phs[i])
        }
        val complete = if (evidence.decodedTo == null) 0 else (evidence.decodedTo * fm) / acq
        while (complete > cellAt && local == null) {
            events.addAll(acquire(cell))
            cell = ArrayList()
            cellAt++
        }
        return events
    }

    fun end(): List<EngineEvent> {
        if (local == null && !reacquiring) {
            val events = if (cell.isNotEmpty()) acquire(cell) else emptyList()
            cell = ArrayList()
            return events + (if (local != null) endLocal() else evaluate(true))
        }
        return endLocal()
    }
}
