// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * When a suspected wrong or added word is confirmed: after `ms` (2-3 s), or once the next word has begun.
 * Default 2500 ms.
 */
sealed class ConfirmPolicy {
    data class Delay(val ms: Double) : ConfirmPolicy()
    object NextWord : ConfirmPolicy()
}

/**
 * Before confirming a wrong, omitted or added word, compare how well the chunk posteriors around it (the word and
 * one either side) support the written words versus what was decoded, over all CTC timings. If the written words
 * fit nearly as well, the error ends `uncertain`. Default on; it needs posteriors on every chunk, and without
 * them nothing changes. `Nats` overrides the margin the decode must win by (`SUPPORT`, doubled for a wrong word
 * heard `UNSURE`).
 */
sealed class LocalWindowPolicy {
    object Disabled : LocalWindowPolicy()
    object Enabled : LocalWindowPolicy()
    data class Nats(val nats: Double) : LocalWindowPolicy()
}

data class CheckerPolicy(
    val confirm: ConfirmPolicy? = null,
    /** Whether resolved errors (self-corrections, recovered skips) count as mistakes. Default false. */
    val countResolved: Boolean? = null,
    /** Mean decoder confidence over an error's phonemes below which it ends `uncertain`, never `confirmed`. Default 0.6. */
    val minConfidence: Double? = null,
    val localWindow: LocalWindowPolicy? = null,
    /**
     * Free recitation, while latched: at session end, confirm a suspected wrong or added word once a later word
     * was recited right, as a declared session does. Default true.
     */
    val finaliseFree: Boolean? = null,
)

data class SeamInsertionCandidate(
    val word: Int,
    val said: String,
    val fromFrame: Int,
    val toFrame: Int,
    val seamFromFrame: Int,
    val seamToFrame: Int,
    val prefixFrame: Int? = null,
)

data class CheckerOptions(
    /** Auxiliary listener evidence may veto only posterior-certified seam spellings. */
    val seamInsertion: ((SeamInsertionCandidate) -> Boolean)? = null,
    val policy: CheckerPolicy? = null,
    /** Non-Quran phrases: an inserted block that is one of these (or a basmala) is not an added word. */
    val phrases: PhraseAsset? = null,
    /**
     * The passage the session declared (memorization): the checker aligns against it rather than the tracker's
     * lock, so a wrong word that forms another ayah is still wrong. A tracker lock off the passage spanning
     * `JUMP_WORDS` words is a deliberate jump, followed until the tracker locks back inside the passage.
     */
    val passage: Pair<WordRef, WordRef>? = null,
    /**
     * Listener confusion counts for closed omission placement and ayah-final formula choices. The scored build
     * uses [LISTENER_CHANNEL]. An empty phone list leaves the channel decisions off.
     */
    val channel: ChannelCounts? = null,
)

interface Checker {
    /** One evidence chunk and the events the tracker returned for it. */
    fun push(evidence: PhonemeEvidence, events: List<EngineEvent>): List<CheckerEvent>

    /** Decides every open error: the session is over, so pending skips are not coming back. */
    fun end(events: List<EngineEvent> = emptyList()): List<CheckerEvent>

    /** Confirmed errors, plus resolved ones when the policy counts them. */
    fun mistakes(): List<CheckerEvent>
}

fun createChecker(index: ReferenceIndex, options: CheckerOptions = CheckerOptions()): Checker =
    CheckerImpl(index, options)

/** Leaving the current place for any word start in the window: a restart or repeat (back) or a skip (ahead). */
private const val JUMP = 2.0 * UNIT

/** Per phoneme of evidence before the lock span that the alignment may drop as leading junk. */
private const val LEAD = 1.0

/**
 * Per phoneme of a non-Quran phrase, far below any edit: where the written words fit the same phonemes as well,
 * they are the words. A waqf ending like ٱلْعَـٰلَمِينَ passes for آمين, and a free phrase there lets a skip take the word.
 */
private const val PHRASE = 1.0 / 64

/** A word is wrong above this weighted cost per reference phoneme it covers, with at least `MIN_WRONG_EDITS` plain edits. */
private const val WRONG = 0.3

/**
 * Memorization costs: a different word changes consonants. Vowels, madd and the marks for qalqalah and ghunna cost
 * `SOFT` to change; a sound held longer or shorter (beside the same sound) costs `HELD`. Consonant edits are hard.
 */
private val CONSONANTS = setOf(
    'ء', 'ب', 'ت', 'ث', 'ج', 'ح', 'خ', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ',
    'ع', 'غ', 'ف', 'ق', 'ك', 'ل', 'م', 'ن', 'ه', 'و', 'ي',
)
private const val SOFT = 3.0
private const val HELD = 2.0

/** A wrong word needs this many hard edits, or one in a word with at most one consonant. */
private const val MIN_HARD = 2

/** Inserted phonemes between two words that make an added word. */
private const val MIN_ADDED = 3

/** A visit counts as reciting the word once it covers this share of it; less is a cut-off attempt. */
private const val MIN_COVERAGE = 0.5

/** Matched phonemes past an error's word that make the next word's onset. */
private const val ONSET = 3

/** Phonemes kept while not anchored, to align from the lock span's start. */
private const val HISTORY = 160

/** Phonemes kept when the tracker leaves the window: about what a jump holds before it commits. */
private const val AFTER_DROP = 24

/** Above this many phonemes the window commits its older part, keeping the latest `KEEP`. */
private const val MAX_WINDOW = 240
private const val KEEP = 120

/** The window spans one ayah before the anchor and this many after the furthest recited word. */
private const val AHEAD_AYAHS = 2
private const val PHRASE_MAX_COST = 0.3

/** Consecutive words a lock off the declared passage spans before it counts as a deliberate jump. */
private const val JUMP_WORDS = 4

/**
 * Below this cost per phoneme the lock's words also fit the declared passage: a wrong word that forms another ayah
 * shares most of its words with the passage, a deliberate jump doesn't.
 */
private const val FITS_PASSAGE = 0.35

/** Nats by which the decoded words must beat the written ones over the local window for the error to stand. */
private const val SUPPORT = 10.0

/**
 * A wrong word whose changed sounds the decoder heard below this mean confidence must win by twice the margin: on
 * degraded audio a confusable sound (ن for م, ف for ك) is heard at even odds with blank, yet a few of them sum past it.
 */
private const val UNSURE = 0.8

/**
 * Manner that a held pausal final still separates on a phone band. Nasal hum, liquid (trill or lateral),
 * obstruent (stop or fricative) and glide. ن/م are one nasal; ف/ك are one obstruent, so those channel
 * pairs are not a contradiction. Ghunna marks take the nasal of ن/م; qalqala is not a manner.
 */
private val MANNER = mapOf(
    'ن' to "nasal", 'م' to "nasal", 'ں' to "nasal", '۾' to "nasal",
    'ر' to "liquid", 'ل' to "liquid",
    'و' to "glide", 'ي' to "glide",
    'ء' to "obstruent", 'ب' to "obstruent", 'ت' to "obstruent", 'ث' to "obstruent", 'ج' to "obstruent",
    'ح' to "obstruent", 'خ' to "obstruent", 'د' to "obstruent", 'ذ' to "obstruent", 'ز' to "obstruent",
    'س' to "obstruent", 'ش' to "obstruent", 'ص' to "obstruent", 'ض' to "obstruent", 'ط' to "obstruent",
    'ظ' to "obstruent", 'ع' to "obstruent", 'غ' to "obstruent", 'ف' to "obstruent", 'ق' to "obstruent",
    'ك' to "obstruent", 'ه' to "obstruent",
)

/** Saktah and waqf tokens the listener can emit. A frame gap the pause test labels is the other pause. */
private val PAUSE_TOKEN = setOf('ۜ', 'ؙ')

/**
 * A dropped consonant-bearing ending takes a consonant plus a long vowel. A brisk murattal phoneme is two frames of
 * the 40 ms stride, and a long vowel is a madd of two counts, so both need 240 ms. A shorter hole between the
 * remaining word and the next onset was not spoken; a longer one can be a phone channel that blanked speech.
 */
private const val SUFFIX_HOLD_MS = (1 + 2) * 2 * 40

private const val OP_START = 0
private const val OP_INS = 1
private const val OP_DIAG = 2
private const val OP_DEL = 3
private const val OP_JUMP = 4
private const val OP_LEAD = 5

private sealed class CheckerStep {
    data class Diag(val ref: Int, val obs: Int) : CheckerStep()
    data class Del(val ref: Int) : CheckerStep()
    data class Ins(val at: Int, val obs: Int) : CheckerStep()
    data class Lead(val obs: Int) : CheckerStep()
    object Jump : CheckerStep()

    /** The observed index, for steps that consume one (-1 otherwise). */
    val obsIndex: Int
        get() = when (this) {
            is Diag -> obs
            is Ins -> obs
            is Lead -> obs
            else -> -1
        }

    /** The reference position: `ref` for diag/del, `at` for ins (-1 otherwise). */
    val refIndex: Int
        get() = when (this) {
            is Diag -> ref
            is Del -> ref
            is Ins -> at
            else -> -1
        }

    val hasObs: Boolean get() = this is Diag || this is Ins || this is Lead
    val hasRef: Boolean get() = this is Diag || this is Del
}

/** An evidence phoneme; `junk` once it is known to be part of a non-Quran phrase. */
private class Heard(val p: Char, var start: Int, var end: Int, var conf: Double, var junk: Boolean = false)

private class Visit(
    val word: Int,
    var covered: Int = 0,
    var consumed: Int = 0,
    var cost: Double = 0.0,
    var hard: Double = 0.0,
    /** Consonants heard as themselves. */
    var kept: Int = 0,
    var obsFrom: Int = -1,
    var obsTo: Int = -1,
    var stepFrom: Int? = null,
    var stepTo: Int? = null,
)

private data class HeardUnion(var obsFrom: Int, var obsTo: Int, var observed: String)
private data class PhraseIssue(val written: String, val alt: String, val need: Double)

private class Issue(
    var cls: MemorizationErrorClass,
    /** Word index of the error; for omissions the first omitted word, with `last` the last one. */
    var word: Int,
    var last: Int,
    var obsFrom: Int,
    var obsTo: Int,
    var observed: String? = null,
    /** Absorbed speech reported as one word. The original span still owns this accusation's confirmation gates. */
    var heardUnion: HeardUnion? = null,
    /** A lexical candidate's exact neighboring visits; other occurrences must not widen its acoustic window. */
    var supportRange: Pair<Int, Int>? = null,
    /** Score this word's own frames. A short ending disappears when the next word's labels slide onto its tail. */
    var wordLocal: Boolean = false,
    /**
     * Decoded hypothesis over `supportRange`: a shorter attested word plus the next written word. The next word's
     * consonant has to be inside this comparison, or it is free to stand in for the missing ending.
     */
    var pair: String? = null,
    /**
     * The joint fit matched the next word, but the main alignment lent its onset to the suffix, so that word has no
     * visit and `onset` cannot see it. Confirmation may use this instead of a later diagonal.
     */
    var pairOnset: Boolean = false,
    /**
     * Attested parallel phrase scored by CTC over `supportRange`. The written path and this one share the
     * entry and exit words; only the interior word is substituted. No insertion or restart arc. `need` is the
     * margin it must win by.
     */
    var phrase: PhraseIssue? = null,
    /**
     * Surplus speech before an ayah's word 1 already spells a whole mushaf word. That identity replaces the
     * posterior support margin: the span is not needed to read the text.
     */
    var lexicon: Boolean = false,
    /**
     * A word-final long vowel before a hamza, judged by its duration against this reciter's earlier madd
     * munfasil. The forward sum prices one vowel as nothing, so the length is the whole decision.
     */
    var madd: Boolean = false,
    /**
     * Substitution taken from the per-word alignment, not a parallel phrase or a lexical candidate. A span that
     * spells another mushaf word, or that carries more consonants than the written word, keeps today's margin.
     * Anything shorter or the same length that spells no such word needs twice the support margin.
     */
    var stringPath: Boolean = false,
    /**
     * Ayah-final slot before a labelled pause whose last consonant manner contradicts the written pausal
     * final. Stands in for the lexical gate and the 0.6 confidence cut. `SUPPORT` and the weak-evidence
     * gate are unchanged.
     */
    var manner: Boolean = false,
) {
    fun copy(
        cls: MemorizationErrorClass = this.cls,
        word: Int = this.word,
        last: Int = this.last,
        obsFrom: Int = this.obsFrom,
        obsTo: Int = this.obsTo,
        observed: String? = this.observed,
        heardUnion: HeardUnion? = this.heardUnion,
        supportRange: Pair<Int, Int>? = this.supportRange,
        wordLocal: Boolean = this.wordLocal,
        pair: String? = this.pair,
        pairOnset: Boolean = this.pairOnset,
        phrase: PhraseIssue? = this.phrase,
        lexicon: Boolean = this.lexicon,
        madd: Boolean = this.madd,
        stringPath: Boolean = this.stringPath,
        manner: Boolean = this.manner,
    ): Issue = Issue(
        cls, word, last, obsFrom, obsTo, observed, heardUnion, supportRange, wordLocal, pair,
        pairOnset, phrase, lexicon, madd, stringPath, manner,
    )
}

private class Open(val id: String, var issue: Issue, val sinceMs: Double)

private class AyahSpan(var wordFrom: Int, var wordTo: Int, var frameFrom: Int, var frameTo: Int)

private data class MaddHit(
    val word: Int,
    val frame: Int,
    val span: Int,
    val consObs: Int,
    val hamzaObs: Int,
    val pause: Boolean,
    /** A madd letter of the written word was heard between the consonant and the hamza. */
    val matched: Boolean,
)

private data class MaddBaseline(val frame: Int, val span: Int)
private data class MaddNowResult(val flags: List<MaddHit>, val baseline: List<MaddBaseline>)

private data class FrozenEntry(val o: Open, val sure: Boolean)
private data class SeamSpan(val from: Int, val to: Int, val said: String)
private data class SeamCacheEntry(val until: Int, val span: SeamSpan?)
private data class StepEdit(val lo: Int, val hi: Int, val path: List<CheckerStep>)
private class PassageRange(var from: Int, var to: Int)
private class RowCosts(val sub: DoubleArray, val ins: DoubleArray)

private class JudgeResult(
    val issues: MutableMap<Int, Issue>,
    val lexical: List<Issue>,
    val head: Int,
    val furthest: Int,
    val steps: List<CheckerStep>,
    val right: MutableSet<Int>,
    val bound: Set<Int>,
    val visits: List<Visit>,
)

private class CheckerImpl(private val index: ReferenceIndex, private val options: CheckerOptions) : Checker {
    private val policy: CheckerPolicy = options.policy ?: CheckerPolicy()
    private val confirm: ConfirmPolicy = policy.confirm ?: ConfirmPolicy.Delay(2500.0)
    private val minConfidence: Double = policy.minConfidence ?: 0.6
    private val frames: PosteriorFrames? =
        if ((policy.localWindow ?: LocalWindowPolicy.Enabled) == LocalWindowPolicy.Disabled) null
        else PosteriorFrames(index.tokens.size)
    private val support: Double =
        (policy.localWindow as? LocalWindowPolicy.Nats)?.nats ?: SUPPORT
    private val tokenId: Map<Char, Int> =
        index.tokens.withIndex().associate { (i, t) -> t to i + 1 }
    private val omissionChannel: Channel = channelFromCounts(options.channel ?: LISTENER_CHANNEL)
    private val rankOmission: Boolean = omissionChannel.phones.isNotEmpty()
    private val ref: String = index.phonemes
    private val words: List<ReferenceWord> = index.words
    private val lexical: LexicalWords = lexicalWords(index)
    private val insertionEvidence = LinkedHashMap<String, Double>()
    private val insertionKey = { word: WordRef, observed: String, from: Int, to: Int ->
        "[${word.surah},${word.ayah},${word.word},${jsonString(observed)},$from,$to]"
    }
    // A completed seam remains in the streaming window over many pushes. Its immutable rows and tokens need only
    // be certified once. Coordinates in a cached result are relative to that window, so commits may trim obs safely.
    private val seamExcisionCache = LinkedHashMap<String, SeamCacheEntry>()
    private val parallels = parallelPaths(index)
    private val madd = maddSites(index)
    private val formulas = formulaSlots(index)
    private val allSpans = slotSpans(index)
    private val spanById = allSpans.associateBy { spanId(it) }

    /** Spans whose continuation already picked a copy. Their slots are no longer held. */
    private val resolvedSpans = LinkedHashSet<String>()

    /** Spans the lock has sat in and not yet resolved. */
    private val watching = LinkedHashSet<String>()

    /** Latest locked word. A span that the cursor has left falls back to the copy it is on. */
    private var cursor = -1

    /**
     * Ayahs the alignment has actually read, and ayahs the tracker has locked, so a never-read ayah between
     * two committed locks can be judged by the closed read-versus-skip forward sum. Cleared when the lock drops.
     */
    private val readAyah = LinkedHashMap<Int, AyahSpan>()
    private val lockedAyah = LinkedHashMap<Int, AyahSpan>()

    /** Last ayah the lock finished, or the alignment read through. A flash of the next ayah does not move it. */
    private var settled = -1
    private var lockSurah = -1
    private var pendingGap: Pair<Int, Int>? = null
    private val ledgerDone = LinkedHashSet<Int>()

    /** Skips the fit already accepted. Re-asserted each window so a later alignment does not resolve them. */
    private val ledgerIssues = LinkedHashMap<Int, Issue>()

    /**
     * Ayah span of each accepted ledger skip. The seam can open in a later window, after this one has confirmed
     * and the anchor has moved, so the span outlives `ledgerIssues`.
     */
    private val ledgerSpans = LinkedHashMap<Int, Int>()

    /** Obs index where the current push's phonemes start, so a new lock can own that chunk's frames. */
    private var chunkFrom = 0

    /**
     * Held slot accusations whose phones commit already trimmed. A locked step would have decided them; they wait
     * for the copy. The same-copy release confirms them, a move to the other copy drops them.
     */
    private val frozen = LinkedHashMap<Int, FrozenEntry>()
    private val wordStarts: Set<Int> = words.map { it.start }.toSet()
    private val ayahOf: List<Int>
    private val ayahFirst: List<Int>
    private val ayahLast: List<Int>
    private val phrases: List<String>

    /**
     * Free recitation latches onto the tracker's first lock as if it were declared, growing it as the reciter goes
     * on, so a wrong word that forms another ayah is still wrong; only a deliberate jump moves it.
     */
    private val latched: Boolean = options.passage == null
    private var passage: PassageRange? = null

    private var frameMs = 0
    private var nowMs = 0.0
    private var trackerLocked = false

    /** Aligning against the declared passage; false while following a deliberate jump off it. */
    private var declared: Boolean

    /** Phonemes since the last anchor (or the latest `HISTORY` while not anchored). */
    private var obs = ArrayList<Heard>()

    /**
     * Matched madd-munfasil lengths already scrolled out of the window, in frames from the stem consonant to the
     * next hamza. The threshold for a vowel drop is the shortest of these, plus any still in the window.
     */
    private val pastMadd = ArrayList<MaddBaseline>()

    /** Word the window's alignment starts from; null while not anchored. */
    private var anchor: Int? = null
    private var lead = 0

    /** The anchor came from a tracker lock rather than a commit, so its first word is an edge. */
    private var fromLock = false
    private var lo = 0
    private var hi = 0
    private var furthest = -1
    private val open = LinkedHashMap<Int, Open>()

    /** Words whose error was confirmed or ended uncertain; never reopened. */
    private val decided = LinkedHashSet<Int>()
    private val emitted = ArrayList<CheckerEvent>()
    private var seq = 0

    /** The steps of the window's latest alignment. */
    private var steps: List<CheckerStep> = emptyList()

    /** Per-window cost tables: every cell cost depends only on the window position and the heard phoneme. */
    private var costLo = -1
    private var costHi = -1
    private var delCost = DoubleArray(0)
    private val rowCosts = LinkedHashMap<Char, RowCosts>()

    /**
     * Refrain words (an ayah whose letter text occurs again) and the shared words of a near-duplicate span.
     * The word a span's members disagree on is the slot, not duplicate text.
     */
    private val copyText = LinkedHashSet<Int>()

    init {
        val ayahOfMut = ArrayList<Int>()
        val ayahFirstMut = ArrayList<Int>()
        val ayahLastMut = ArrayList<Int>()
        words.forEachIndexed { i, w ->
            val prev = words.getOrNull(i - 1)
            if (prev == null || prev.surah != w.surah || prev.ayah != w.ayah) ayahFirstMut.add(i)
            ayahOfMut.add(ayahFirstMut.size - 1)
            val idx = ayahFirstMut.size - 1
            if (ayahLastMut.size <= idx) ayahLastMut.add(i) else ayahLastMut[idx] = i
        }
        ayahOf = ayahOfMut
        ayahFirst = ayahFirstMut
        ayahLast = ayahLastMut

        val fatiha = words.filter { it.surah == 1 && it.ayah == 1 }
        phrases = (options.phrases?.phrases?.map { it.p } ?: emptyList()) +
            ref.substring(fatiha.first().start, fatiha.last().end)

        options.passage?.let { (from, to) ->
            passage = PassageRange(wordIndex(from), wordIndex(to))
        }
        declared = passage != null

        run {
            val skel = ArrayList<String>()
            val copies = LinkedHashMap<String, Int>()
            for (a in ayahFirst.indices) {
                val t = ayahText(a)
                skel.add(t)
                copies[t] = (copies[t] ?: 0) + 1
            }
            for (a in skel.indices) if ((copies[skel[a]] ?: 0) > 1) {
                for (w in ayahFirst[a]..ayahLast[a]) copyText.add(w)
            }
            for (s in allSpans) {
                val slot = s.slots.toSet()
                for (st in s.starts) for (off in 0 until s.length) if (!slot.contains(off)) copyText.add(st + off)
            }
        }
    }

    private fun nextWord(wi: Int): Int {
        var k = wi + 1
        while (k < words.size && words[k].end == words[k].start) k++
        return k
    }

    private fun prevWord(wi: Int): Int {
        var k = wi - 1
        while (k > 0 && words[k].end == words[k].start) k--
        return k
    }

    private fun wordIndex(r: WordRef): Int =
        words.indexOfFirst { it.surah == r.surah && it.ayah == r.ayah && it.word == r.word }

    private fun toRef(i: Int): WordRef = WordRef(words[i].surah, words[i].ayah, words[i].word)

    /** Off the passage by more than an ayah either side. */
    private fun offPassage(wi: Int): Boolean {
        val p = passage ?: return false
        if (wi < 0 || wi >= words.size) return false
        return ayahOf[wi] < ayahOf[p.from] - 1 || ayahOf[wi] > ayahOf[p.to] + 1
    }

    private fun fitsPassage(from: Int, to: Int): Boolean {
        val p = passage ?: return false
        val text = ref.substring(words[from].start, words[to].end)
        val a = alignSubstring(ref, words[p.from].start, words[p.to].end, text, isStart = { o -> wordStarts.contains(o) })
        return a != null && a.cost / UNIT / text.length < FITS_PASSAGE
    }

    /** `obs[from, to)` with JS `slice` clamping: out-of-range ends clamp, an empty range is empty. */
    private fun obsSlice(from: Int, to: Int): List<Heard> {
        val a = from.coerceIn(0, obs.size)
        val b = to.coerceIn(0, obs.size)
        return if (a >= b) emptyList() else obs.subList(a, b)
    }

    private fun heardString(from: Int, to: Int): String =
        obsSlice(from, to).joinToString("") { it.p.toString() }

    /** The same silence the inner-waqf path labels: a blank of `GAP_PAD_S` between two phones. */
    private fun labelledPause(prev: Int): Boolean {
        if (frameMs <= 0 || prev < 0 || prev + 1 >= obs.size) return false
        var a = obs[prev].end + 1
        val b = obs[prev + 1].start
        val f = frames
        if (f != null && f.startFrame >= 0 && a >= f.startFrame && b <= f.endFrame) {
            val id = tokenId[obs[prev].p]
            if (id != null) while (a < b) {
                val row = f.row(a)
                var best = 0
                for (i in 1 until row.size) if (row[i] > row[best]) best = i
                if (best != id) break
                a++
            }
        }
        if (b - a < minGapFrames(frameMs)) return false
        if (f == null || f.startFrame < 0 || a < f.startFrame || b > f.endFrame) return true
        val owns = Math.log(0.5)
        for (fr in a until b) if (f.row(fr)[0] < owns) return false
        return true
    }

    /** Connected madds the alignment has both edges for. A pause between the words is not one of them. */
    private fun measureMadd(aligned: List<CheckerStep>): List<MaddHit> {
        val exact = LinkedHashMap<Int, Int>()
        val seen = LinkedHashSet<Int>()
        for (s in aligned) if (s is CheckerStep.Diag && ref[s.ref] == obs[s.obs].p) {
            exact[s.ref] = s.obs
            seen.add(index.wordAt(s.ref))
        }
        val out = ArrayList<MaddHit>()
        for (wi in seen) {
            val next = madd.munfasil[wi] ?: continue
            val cons = stemConsonant(ref, words[wi].start, words[wi].end)
            val hamza = words[next].start
            val consObs = exact[cons]
            val hamzaObs = exact[hamza]
            if (cons < words[wi].start || consObs == null || hamzaObs == null || hamzaObs <= consObs) continue
            val span = obs[hamzaObs].start - obs[consObs].start
            if (span <= 0) continue
            var pause = false
            for (k in consObs until hamzaObs) {
                if (labelledPause(k)) {
                    pause = true
                    break
                }
            }
            var matched = false
            if (!pause) for (s in aligned) {
                if (s !is CheckerStep.Diag || s.obs <= consObs || s.obs >= hamzaObs) continue
                if (s.ref < words[wi].start || s.ref >= words[wi].end) continue
                if (ref[s.ref] == obs[s.obs].p && isMaddLetter(ref[s.ref])) matched = true
            }
            out.add(MaddHit(wi, obs[consObs].start, span, consObs, hamzaObs, pause, matched))
        }
        return out
    }

    /**
     * Vowel-drop candidates shorter than every earlier matched madd in this session. A matched madd that is not
     * that short joins the baseline; a flagged drop does not, so one miss cannot lower the bar for the next.
     */
    private fun maddNow(aligned: List<CheckerStep>): MaddNowResult {
        val hits = measureMadd(aligned).sortedWith(compareBy { it.frame })
        val baseline = pastMadd.map { it.copy() }.toMutableList()
        val flags = ArrayList<MaddHit>()
        for (m in hits) {
            val earlier = baseline.filter { it.frame < m.frame }
            val min = earlier.fold(Double.POSITIVE_INFINITY) { n, b -> minOf(n, b.span.toDouble()) }
            if (madd.drops.containsKey(m.word) && !m.pause && earlier.isNotEmpty() && m.span < min) flags.add(m)
            else if (m.matched && !m.pause && baseline.none { it.frame == m.frame }) {
                baseline.add(MaddBaseline(m.frame, m.span))
            }
        }
        return MaddNowResult(flags, baseline)
    }

    private fun event(o: Open, state: CheckerState): CheckerEvent {
        val issue = o.issue
        val conf = clarity(issue)
        val e = CheckerEvent(
            id = o.id,
            timeMs = nowMs,
            state = state,
            errorClass = issue.cls,
            location = toRef(issue.word),
            expected = ref.substring(words[issue.word].start, words[issue.last].end),
            observed = issue.heardUnion?.observed ?: issue.observed,
            confidence = conf,
        )
        emitted.add(e)
        return e
    }

    /** Mean decoder confidence over an error's phonemes; for an omission, the phonemes either side of the gap. */
    private fun clarity(issue: Issue): Double {
        val from = maxOf(0, minOf(issue.obsFrom, obs.size - 1))
        val to = minOf(obs.size, maxOf(issue.obsTo, from + 1))
        val span = if (issue.cls == "omitted_word" || issue.cls == "skipped_passage") {
            obsSlice(maxOf(0, from - 3), to + 3)
        } else {
            obsSlice(from, to)
        }
        return if (span.isNotEmpty()) span.sumOf { it.conf } / span.size else 0.0
    }

    private fun decide(o: Open, out: MutableList<CheckerEvent>, state: CheckerState? = null) {
        open.remove(o.issue.word)
        decided.add(o.issue.word)
        val sure = ((declared && !latched) || trackerLocked) && heardSure(o.issue) && supported(o.issue) != false
        out.add(event(o, state ?: if (sure) "confirmed" else "uncertain"))
    }

    /**
     * Whether the posteriors over the error's word and a recited word either side favour what was decoded over the
     * written words by `SUPPORT`; null when there's no telling (no posteriors there, or a skipped passage).
     */
    private fun supported(issue: Issue, requireFinite: Boolean = false): Boolean? {
        // Spelled surplus is structural. A likelihood contest over the neighbours is what loses the word.
        if (issue.lexicon) return null
        // The madd's length was already compared with this reciter's own. One vowel does not move the forward sum.
        if (issue.madd) return null
        val f = frames ?: return null
        if (f.startFrame < 0 || issue.cls == "skipped_passage") return null
        val heardAt = { wi: Int ->
            steps.filter { s -> s is CheckerStep.Diag && index.wordAt(s.ref) == wi }
                .map { (it as CheckerStep.Diag).obs }
        }
        val before = prevWord(issue.word)
        val after = if (issue.cls == "added_word") issue.word else nextWord(issue.last)
        val left = if (before < issue.word) heardAt(before) else emptyList()
        val right = if (after < words.size) heardAt(after) else emptyList()
        val from = if (left.isNotEmpty()) before else issue.word
        val to = if (right.isNotEmpty()) after else if (issue.cls == "added_word") issue.word else issue.last
        val at = (issue.supportRange?.let { listOf(it.first, it.second - 1) }
            ?: (left + right + if (issue.obsTo > issue.obsFrom) listOf(issue.obsFrom, issue.obsTo - 1) else emptyList()))
            .filter { it >= 0 && it < obs.size }
        if (at.isEmpty() && !issue.wordLocal) return null
        val ids = { s: String -> s.map { tokenId[it] ?: 0 }.toIntArray() }
        // The ending is a few phones. Over the word plus its neighbours the next word's labels take those frames and
        // the written word looks almost as likely; on the word's own frames the consonant has to be accounted for.
        if (issue.wordLocal && issue.obsTo > issue.obsFrom) {
            val a0 = obs[issue.obsFrom].start
            val b0 = obs[issue.obsTo - 1].end + 1
            if (a0 < f.startFrame || b0 > f.endFrame) return null
            val word = ref.substring(words[issue.word].start, words[issue.last].end)
            val need = supportNeed(issue)
            val expectedNll = f.nll(ids(word), a0, b0)
            val observedNll = f.nll(ids(issue.observed ?: ""), a0, b0)
            if (requireFinite && (!expectedNll.isFinite() || !observedNll.isFinite())) return null
            return expectedNll - observedNll >= need
        }
        // Same joint span as the edit comparison. A wide window that leaves the next word out lets its onset consonant
        // explain the ending a second time.
        if (issue.pair != null && issue.supportRange != null &&
            issue.supportRange!!.second > issue.supportRange!!.first
        ) {
            val a0 = obs[issue.supportRange!!.first].start
            val b0 = obs[issue.supportRange!!.second - 1].end + 1
            if (a0 < f.startFrame || b0 > f.endFrame) return null
            val through = nextWord(issue.last)
            val writtenPair = ref.substring(words[issue.word].start, words[through].end)
            val need = supportNeed(issue)
            val expectedNll = f.nll(ids(writtenPair), a0, b0)
            val observedNll = f.nll(ids(issue.pair!!), a0, b0)
            if (requireFinite && (!expectedNll.isFinite() || !observedNll.isFinite())) return null
            return expectedNll - observedNll >= need
        }
        if (at.isEmpty()) return null
        val f0 = obs[at.min()].start
        val f1 = obs[at.max()].end + 1
        if (f0 < f.startFrame || f1 > f.endFrame) return null
        if (issue.phrase != null && issue.supportRange != null) {
            val altNll = f.nll(ids(issue.phrase!!.alt), f0, f1)
            // A written phrase the frames cannot carry at all still loses to a finite parallel. NaN (both impossible)
            // is not evidence either way.
            if (!altNll.isFinite()) return if (requireFinite) null else false
            return f.nll(ids(issue.phrase!!.written), f0, f1) - altNll >= issue.phrase!!.need
        }
        val span = { a: Int, b: Int ->
            if (a < b) ref.substring(words[a].start, words[b - 1].end) else ""
        }
        val written = ref.substring(words[from].start, words[to].end)
        val observed = issue.observed ?: ""
        val decoded = if (issue.cls == "added_word") {
            span(from, issue.word) + observed + span(issue.word, to + 1)
        } else {
            span(from, issue.word) + (if (issue.cls == "wrong_word") observed else "") + span(issue.last + 1, to + 1)
        }
        val need = supportNeed(issue)
        val expectedNll = f.nll(ids(written), f0, f1)
        val observedNll = f.nll(ids(decoded), f0, f1)
        if (requireFinite && (!expectedNll.isFinite() || !observedNll.isFinite())) return null
        return expectedNll - observedNll >= need
    }

    /** Waqf and pause marks at the edges of a heard span. They are not part of the word. */
    private val waqfEdge = setOf('ۦ', 'ۥ', 'ں', '۾', 'ڇ', 'ۜ', 'ؙ')

    private fun stripEdge(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && waqfEdge.contains(s[a])) a++
        while (b > a && waqfEdge.contains(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** The heard word with phrase-labelled pauses removed and waqf tails stripped. */
    private fun heardSpan(issue: Issue): String {
        val from = maxOf(0, issue.obsFrom)
        val to = minOf(obs.size, issue.obsTo)
        val live = if (from < to) {
            obsSlice(from, to).filter { !it.junk }.joinToString("") { it.p.toString() }
        } else ""
        return stripEdge(if (live.isNotEmpty()) live else issue.observed ?: "")
    }

    private fun consonantCount(s: String): Int =
        collapseHeld(s.toList()).count { CONSONANTS.contains(it) }

    /** Manner of the last consonant run after waqf tails are stripped. Null when no consonant remains. */
    private fun lastManner(phones: String): String? {
        val bare = stripEdge(phones)
        for (i in bare.length - 1 downTo 0) {
            val m = MANNER[bare[i]]
            if (m != null) return m
        }
        return null
    }

    /** The isolated pausal form's final consonant. Connected text when the index has no pause form. */
    private fun pausalManner(wi: Int): String? =
        lastManner(index.isolatedWordForms[wi].ifEmpty { ref.substring(words[wi].start, words[wi].end) })

    /** A manner mismatch is sure without the 0.6 cut. The weak-evidence gate still reads the changed phones. */
    private fun heardSure(issue: Issue): Boolean = issue.manner || clarity(issue) >= minConfidence

    /**
     * Nats the decoded hypothesis must win by. A per-word substitution that does not spell another mushaf word
     * (not the written word, not a word or prefix within ±3) clears twice `SUPPORT`, unless the span has more
     * consonants than the written word: that extra speech is the next word's, and today's margin still owns it.
     * A manner mismatch replaces that lexical doubling. The weak-evidence gate still asks for twice `SUPPORT`.
     * A spelled word keeps today's margin.
     */
    private fun supportNeed(issue: Issue): Double {
        val weak = issue.cls == "wrong_word" && changedClarity(issue) < UNSURE
        // The lexical doubling is the gate a manner mismatch replaces. Weak evidence still doubles.
        if (issue.manner) return if (weak) 2 * support else support
        if (issue.stringPath) {
            val heard = heardSpan(issue)
            val written = ref.substring(words[issue.word].start, words[issue.last].end)
            if (consonantCount(heard) <= consonantCount(written) &&
                lexical.surplusWord(issue.word, heard) == null
            ) return 2 * support
        }
        return if (weak) 2 * support else support
    }

    /** Mean decoder confidence over a wrong word's decoded phonemes that differ from the written word. */
    private fun changedClarity(issue: Issue): Double {
        val heard = obsSlice(maxOf(0, issue.obsFrom), issue.obsTo)
        val changed = align(
            ref.substring(words[issue.word].start, words[issue.last].end),
            heard.joinToString("") { it.p.toString() },
        ).flatMap { o ->
            if (o is AlignmentOp.Sub || o is AlignmentOp.Ins) {
                val at = when (o) {
                    is AlignmentOp.Sub -> o.obsAt
                    is AlignmentOp.Ins -> o.obsAt
                    else -> -1
                }
                listOf(heard[at].conf)
            } else emptyList()
        }
        return if (changed.isNotEmpty()) changed.sum() / changed.size else 1.0
    }

    /** Heard phonemes after the issue, up to `to`, against the anchor with the word at `cutAt` removed. */
    private fun continuationFit(issue: Issue, cutAt: Int, to: Int): IssueDropFit? {
        val from = words[issue.word].start
        if (cutAt <= from || from >= hi || cutAt > hi) return null
        val heard = heardString(issue.obsTo, to)
        return issueDropFit(ref.substring(from, hi), ref.substring(cutAt, hi), heard)
    }

    /** An added word: delete its heard phones and align what follows to the anchor, which still contains that word. */
    private fun insertionFit(issue: Issue, to: Int): IssueDropFit? {
        val from = words[issue.word].start
        if (issue.obsTo <= issue.obsFrom || to <= issue.obsTo || from >= hi) return null
        val heard = heardString(issue.obsFrom, to)
        val kept = heardString(issue.obsTo, to)
        return insertionDropFit(ref.substring(from, hi), heard, kept)
    }

    /** Every issue open now ends `uncertain` and is never reopened. A real lock loss still does this to all of them. */
    private fun sealAll(out: MutableList<CheckerEvent>) {
        for (o in open.values.toList()) {
            open.remove(o.issue.word)
            decided.add(o.issue.word)
            out.add(event(o, "uncertain"))
        }
    }

    /**
     * A misfit hold. An issue stays open when its own edit makes the heard words up to the next open issue fit the
     * anchor again: the edited fit beats the unedited fit by the commit margin and falls below the misfit cost.
     * An omission or a wrong word deletes that written word. An added word deletes its heard phones instead and
     * keeps the phones that follow, against the anchor text those phones are. Anything else is downgraded as on a
     * lock loss.
     */
    private fun sealOpen(out: MutableList<CheckerEvent>, skip: Set<Int>? = null) {
        val ordered = open.values.sortedWith(compareBy { it.issue.obsFrom })
        val keep = LinkedHashSet<Int>()
        for (o in ordered) {
            val next = ordered.find { it.issue.obsFrom > o.issue.obsTo }
            val to = next?.issue?.obsFrom ?: obs.size
            val fit = continuationFit(o.issue, words[o.issue.word].end, to)
            val inserted = if (o.issue.cls == "added_word") insertionFit(o.issue, to) else null
            if (fit?.explains == true || inserted?.explains == true) keep.add(o.issue.word)
        }
        for (o in open.values.toList()) {
            if (keep.contains(o.issue.word) || skip?.contains(o.issue.word) == true) continue
            open.remove(o.issue.word)
            decided.add(o.issue.word)
            out.add(event(o, "uncertain"))
        }
    }

    private fun noteSpan(wi: Int) {
        if (wi < 0) return
        for (s in allSpans) {
            val id = spanId(s)
            if (resolvedSpans.contains(id) || watching.contains(id)) continue
            if (s.starts.any { st -> wi >= st && wi < st + s.length }) watching.add(id)
        }
    }

    /** A differing slot of a span the lock is still inside. Accusations there wait for the continuation. */
    private fun heldSlot(wi: Int): Boolean {
        for (id in watching) {
            if (resolvedSpans.contains(id)) continue
            val s = spanById[id] ?: continue
            for (st in s.starts) for (off in s.slots) if (st + off == wi) return true
        }
        return false
    }

    private fun slotWords(): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (id in watching) {
            if (resolvedSpans.contains(id)) continue
            val s = spanById[id] ?: continue
            for (st in s.starts) for (off in s.slots) out.add(st + off)
        }
        return out
    }

    private fun resolveWatching() {
        for (id in watching) resolvedSpans.add(id)
        watching.clear()
    }

    private fun sureOf(o: Open): Boolean =
        ((declared && !latched) || trackerLocked) && heardSure(o.issue) && supported(o.issue) != false

    /** Confirm frozen slot accusations, or drop the ones that named the copy the continuation rejected. */
    private fun releaseFrozen(out: MutableList<CheckerEvent>, drop: Boolean, from: Int = -1, end: Int = Int.MAX_VALUE) {
        for ((wi, f) in frozen.toList()) {
            if (wi < from || wi >= end) continue
            frozen.remove(wi)
            decided.add(f.o.issue.word)
            out.add(event(f.o, if (drop) "resolved" else if (f.sure) "confirmed" else "uncertain"))
        }
    }

    private fun freezeSlot(wi: Int, o: Open) {
        if (frozen.containsKey(wi)) return
        frozen[wi] = FrozenEntry(o, sureOf(o))
        open.remove(wi)
    }

    /** True while the cursor is still inside a watched span or on its first few continuation words. */
    private fun spanStillOpen(wi: Int): Boolean {
        for (id in watching) {
            if (resolvedSpans.contains(id)) continue
            val s = spanById[id] ?: continue
            if (s.starts.any { st -> wi >= st && wi < st + s.length + 4 }) return true
        }
        return false
    }

    /**
     * The continuation picked the other member. Slot accusations named the old copy, so they drop. Every other
     * decision stays, and the phones stay in the window a locked step already committed: no fresh-lock `lead`, no
     * slice of the word-1 gap. The anchor slides onto the parallel text and the next step keeps appending.
     */
    private fun rebase(from: Int, to: Int, out: MutableList<CheckerEvent>) {
        val span = allSpans.find { it.starts.contains(from) && it.starts.contains(to) } ?: return
        if (anchor == null) return
        val delta = to - from
        val end = from + span.length
        val slotAt = span.slots.map { from + it }.toSet()
        for (wi in open.keys.toList()) {
            if (wi < from || wi >= end) continue
            val o = open[wi]!!
            open.remove(wi)
            if (slotAt.contains(wi)) out.add(event(o, "resolved"))
            else {
                val moved = o.issue.word + delta
                // The in-order word was already accused (the real skip). Close the copy's duplicate instead of reopening it.
                if (decided.contains(moved)) {
                    decided.add(o.issue.word)
                    out.add(event(o, "resolved"))
                } else {
                    val issue = o.issue.copy(word = moved, last = o.issue.last + delta)
                    open[issue.word] = Open(o.id, issue, o.sinceMs)
                }
            }
        }
        releaseFrozen(out, true, from, end)
        for (w in decided.toList()) if (w >= from && w < end && !slotAt.contains(w)) decided.add(w + delta)
        for ((w, last) in ledgerSpans.toList()) {
            if (w < from || w >= end) continue
            ledgerSpans.remove(w)
            // A whole ayah sits inside the span, so the end slides with the start. A slot was the other copy.
            if (!slotAt.contains(w)) ledgerSpans[w + delta] = last + delta
        }
        if (anchor!! >= from && anchor!! <= end) anchor = anchor!! + delta
        else if (anchor!! < from) anchor = to
        if (furthest >= from && furthest < end) furthest += delta
        if (furthest < anchor!!) furthest = anchor!!
        passage?.let {
            it.from += delta
            it.to = maxOf(it.from, it.to + delta)
        }
        clearLedger()
        // The next lock in this same batch is the continuation. Score it against the slid window, not the copy's.
        val ayah = ayahOf[anchor!!]
        lo = words[ayahFirst[maxOf(0, ayah - 1)]].start
        val far = ayahOf[maxOf(anchor!!, furthest)]
        hi = words[ayahLast[minOf(ayahLast.size - 1, far + AHEAD_AYAHS)]].end
    }

    private fun dropAnchor(out: MutableList<CheckerEvent>) {
        // A fit that already accepted a skip has its evidence. The lock leaving is the same as the passage ending.
        for (wi in ledgerIssues.keys.toList()) {
            val o = open[wi]
            ledgerIssues.remove(wi)
            if (o == null) continue
            open.remove(wi)
            decided.add(wi)
            out.add(event(o, if (sureOf(o)) "confirmed" else "uncertain"))
        }
        sealAll(out)
        anchor = null
        furthest = -1
        obs = ArrayList(obs.takeLast(AFTER_DROP))
        // Ayahs already read stay read, so the next lock can still close a gap this jump opened.
    }

    private fun clearLedger() {
        readAyah.clear()
        lockedAyah.clear()
        settled = -1
        lockSurah = -1
        pendingGap = null
        ledgerIssues.clear()
    }

    private fun growSpan(map: MutableMap<Int, AyahSpan>, ayah: Int, word: Int, frameFrom: Int, frameTo: Int) {
        val h = map[ayah]
        if (h == null) map[ayah] = AyahSpan(word, word, frameFrom, frameTo)
        else {
            h.wordFrom = minOf(h.wordFrom, word)
            h.wordTo = maxOf(h.wordTo, word)
            h.frameFrom = minOf(h.frameFrom, frameFrom)
            h.frameTo = maxOf(h.frameTo, frameTo)
        }
    }

    /** Letter identity of an ayah. A refrain copy occurs among its neighbours; a skipped unique ayah does not. */
    private fun ayahText(ayah: Int): String {
        val sb = StringBuilder()
        for (i in ayahFirst[ayah]..ayahLast[ayah]) {
            sb.append(letterSkeleton(index.wordText[i]))
            sb.append('\u0000')
        }
        val s = sb.toString()
        return if (s.isNotEmpty()) s else ref.substring(words[ayahFirst[ayah]].start, words[ayahLast[ayah]].end)
    }

    private fun wordPhones(from: Int, to: Int): List<String> {
        val out = ArrayList<String>()
        for (i in from..to) out.add(ref.substring(words[i].start, words[i].end))
        return out
    }

    private fun spanFrames(ayah: Int): AyahSpan? = readAyah[ayah] ?: lockedAyah[ayah]

    /**
     * Frames still needed for an open gap: the left neighbour's speech, until the skip-versus-read sum runs.
     * Null when nothing is waiting, so the window drops frames as before.
     */
    private fun ledgerFloor(): Int? {
        val gap = pendingGap ?: return null
        if (frames == null) return null
        return spanFrames(gap.first)?.frameFrom
    }

    /** The tracker locked on `right` having come from `left`. Score once the right ayah is in, or the passage moves on. */
    private fun closeGap(out: MutableList<CheckerEvent>, issues: MutableMap<Int, Issue>, left: Int, right: Int, force: Boolean) {
        val f = frames ?: return
        if (f.startFrame < 0 || words[ayahFirst[left]].surah != words[ayahFirst[right]].surah) return
        val rightLock = lockedAyah[right]
        val rightRead = readAyah[right]
        val wordTo = maxOf(rightLock?.wordTo ?: -1, rightRead?.wordTo ?: -1)
        if (!force && wordTo < ayahLast[right] && settled <= right) return
        val pg = pendingGap
        if (pg != null && pg.first == left && pg.second == right) pendingGap = null
        val leftSpan = spanFrames(left)
        val rightTo = maxOf(rightLock?.frameTo ?: 0, rightRead?.frameTo ?: 0)
        if (leftSpan == null || wordTo < ayahFirst[right] || rightTo <= leftSpan.frameFrom) return
        if (leftSpan.frameFrom < f.startFrame || rightTo > f.endFrame) return
        val leftTo = maxOf(leftSpan.wordTo, lockedAyah[left]?.wordTo ?: -1)
        val leftWords = wordPhones(maxOf(ayahFirst[left], leftSpan.wordFrom), minOf(ayahLast[left], leftTo))
        val rightWords = wordPhones(ayahFirst[right], minOf(ayahLast[right], wordTo))
        val idOf = { p: Char -> tokenId[p] ?: 0 }
        var at = 0
        val boundary = rightLock?.frameFrom ?: rightRead?.frameFrom ?: rightTo
        for (i in obs.indices) if (obs[i].start < boundary) at = i
        for (a in (left + 1) until right) {
            if (ayahFinished(a) || ledgerDone.contains(a)) continue
            if (ayahText(a) == ayahText(left) || ayahText(a) == ayahText(right)) continue
            val n = words.subList(ayahFirst[a], ayahLast[a] + 1).count { it.end > it.start }
            if (n < 3) continue
            val margin = absenceMargin(
                f, idOf,
                listOf(leftWords, wordPhones(ayahFirst[a], ayahLast[a]), rightWords),
                1, leftSpan.frameFrom, rightTo,
            )
            ledgerDone.add(a)
            if (margin == null || margin < PHRASE_SWITCH) continue
            val wi = ayahFirst[a]
            if (decided.contains(wi) || heldSlot(wi)) continue
            val openIssue = open[wi]
            if (openIssue != null && openIssue.issue.cls != "wrong_word" && openIssue.issue.cls != "omitted_word") {
                // The seam already keyed this ayah. Remember the ayah so a later seam on the same run can be dropped.
                if (openIssue.issue.cls == "skipped_passage") ledgerSpans[wi] = ayahLast[a]
                continue
            }
            val existing = issues[wi]
            if (existing != null && existing.cls != "wrong_word" && existing.cls != "omitted_word") {
                if (existing.cls == "skipped_passage") ledgerSpans[wi] = ayahLast[a]
                continue
            }
            if (openIssue != null) {
                open.remove(wi)
                out.add(event(openIssue, "resolved"))
            }
            val issue = Issue(cls = "skipped_passage", word = wi, last = ayahLast[a], obsFrom = at, obsTo = at)
            ledgerIssues[wi] = issue
            ledgerSpans[wi] = issue.last
            issues[wi] = issue
        }
    }

    private fun keepLedger(issues: MutableMap<Int, Issue>) {
        for ((wi, issue) in ledgerIssues.toList()) {
            if (decided.contains(wi) || ayahFinished(ayahOf[wi])) {
                ledgerIssues.remove(wi)
                // Confirmed skips stay. A finished ayah was read, so the skip did not stand.
                if (!decided.contains(wi)) ledgerSpans.remove(wi)
                continue
            }
            // A wrong or omitted word on this ayah start is the skip misfiled. The fit already chose.
            val now = issues[wi]
            if (now == null || now.cls == "wrong_word" || now.cls == "omitted_word") issues[wi] = issue
        }
    }

    private fun ayahFinished(ayah: Int): Boolean =
        (lockedAyah[ayah]?.wordTo ?: -1) >= ayahLast[ayah] ||
            (readAyah[ayah]?.wordTo ?: -1) >= ayahLast[ayah]

    private fun noteLock() {
        if (cursor < 0 || obs.isEmpty()) return
        val ayah = ayahOf[cursor]
        val surah = words[ayahFirst[ayah]].surah
        val from = obs[minOf(chunkFrom, obs.size - 1)]
        val to = obs.last()
        growSpan(lockedAyah, ayah, cursor, from.start, to.end + 1)
        if (surah != lockSurah) {
            settled = -1
            lockSurah = surah
            pendingGap = null
        }
        if (settled < 0) {
            if (ayahFinished(ayah)) settled = ayah
            return
        }
        if (ayah > settled + 1 && pendingGap?.second != ayah &&
            words[ayahFirst[settled]].surah == surah && ayah - settled <= AHEAD_AYAHS + 1
        ) {
            pendingGap = Pair(settled, ayah)
        }
        if (ayahFinished(ayah) && ayah > settled) settled = ayah
    }

    private fun setWindow() {
        val a = ayahOf[anchor!!]
        lo = words[ayahFirst[maxOf(0, a - 1)]].start
        val f = ayahOf[maxOf(anchor!!, furthest)]
        hi = words[ayahLast[minOf(ayahLast.size - 1, f + AHEAD_AYAHS)]].end
    }

    private fun costsFor(p: Char): RowCosts {
        var c = rowCosts[p]
        if (c == null) {
            val n = hi - lo
            val sub = DoubleArray(n) { subCost(ref[lo + it], p) }
            val ins = DoubleArray(n + 1) { insertionCost(ref, lo + it, p, hi) }
            c = RowCosts(sub, ins)
            rowCosts[p] = c
        }
        return c
    }

    /** Weighted alignment of the window's phonemes from the anchor, with jump arcs to every word start in the window. */
    private fun align(): List<CheckerStep> {
        if (lo != costLo || hi != costHi) {
            costLo = lo
            costHi = hi
            rowCosts.clear()
            delCost = DoubleArray(hi - lo) { i ->
                val at = lo + i
                // The listener can shorten any doubled sound, including a shadda, without dropping the word.
                if ((at > 0 && ref[at] == ref[at - 1]) || (at + 1 < ref.length && ref[at] == ref[at + 1])) HELD
                else deletionCost(ref, at)
            }
        }
        val a0 = words[anchor!!].start - lo
        val n = hi - lo
        val m = obs.size
        val w = n + 1
        val d = DoubleArray((m + 1) * w) { Double.POSITIVE_INFINITY }
        val op = IntArray((m + 1) * w)
        val jumpFrom = IntArray(m + 1)
        val starts = ArrayList<Int>()
        for (i in 0..n) if (wordStarts.contains(lo + i)) starts.add(i)
        val jumps = { j: Int ->
            val row = j * w
            var k = 0
            for (i in 1..n) if (d[row + i] < d[row + k]) k = i
            jumpFrom[j] = k
            for (s in starts) {
                if (d[row + k] + JUMP < d[row + s]) {
                    d[row + s] = d[row + k] + JUMP
                    op[row + s] = OP_JUMP
                }
            }
        }
        d[a0] = 0.0
        op[a0] = OP_START
        for (i in (a0 + 1)..n) {
            d[i] = d[i - 1] + delCost[i - 1]
            op[i] = OP_DEL
        }
        jumps(0)
        for (j in 1..m) {
            val p = obs[j - 1].p
            val costs = costsFor(p)
            val sub = costs.sub
            val insCost = costs.ins
            // A repeated decoded sound can be lengthening even at a word edge, where the reference has no matching run.
            // Use the same held-sound charge as judging; keep ordinary insertion costs for unrelated speech.
            val noise = if (j >= 2 && p == obs[j - 2].p) HELD else UNIT.toDouble()
            val row = j * w
            val prev = row - w
            for (i in 0..n) {
                var best = Double.POSITIVE_INFINITY
                var o = OP_INS
                if (i > 0) {
                    best = d[prev + i - 1] + sub[i - 1]
                    o = OP_DIAG
                }
                val ins = d[prev + i] + (
                    if (obs[j - 1].junk) PHRASE
                    else if (j <= lead && i == a0) LEAD
                    else minOf(insCost[i], noise)
                    )
                if (ins < best) {
                    best = ins
                    o = if (j <= lead && i == a0) OP_LEAD else OP_INS
                }
                if (i > 0) {
                    val del = d[row + i - 1] + delCost[i - 1]
                    if (del < best) {
                        best = del
                        o = OP_DEL
                    }
                }
                d[row + i] = best
                op[row + i] = o
            }
            jumps(j)
        }

        var i = 0
        for (k in 1..n) if (d[m * w + k] < d[m * w + i]) i = k
        val out = ArrayList<CheckerStep>()
        var j = m
        while (true) {
            val o = op[j * w + i]
            if (o == OP_START) break
            if (o == OP_INS) {
                j--
                out.add(CheckerStep.Ins(lo + i, j))
            } else if (o == OP_LEAD) {
                j--
                out.add(CheckerStep.Lead(j))
            } else if (o == OP_DIAG) {
                i--
                j--
                out.add(CheckerStep.Diag(lo + i, j))
            } else if (o == OP_DEL) {
                i--
                out.add(CheckerStep.Del(lo + i))
            } else {
                out.add(CheckerStep.Jump)
                i = jumpFrom[j]
            }
        }
        return out.reversed()
    }

    /**
     * Mark phrases that end at each phoneme from `from` on: the alignment consumes them for `PHRASE`. Once anchored,
     * a run the window's own text fits at least as well is that text (ءَامِنِينَ, not آمين).
     */
    private fun markPhrases(from: Int) {
        for (end in (from + 1)..obs.size) {
            for (p in phrases) {
                val start = maxOf(0, end - Math.ceil(p.length * 1.6).toInt() - 4)
                val tail = heardString(start, end)
                val a = alignSubstring(tail, 0, tail.length, p, isStart = { true })
                if (a == null || a.end < tail.length - 1 || a.cost / UNIT / p.length > PHRASE_MAX_COST) continue
                if (anchor != null) {
                    val text = alignSubstring(ref, lo, hi, tail.substring(a.start), isStart = { true })
                    if (text != null && text.cost <= a.cost) continue
                }
                for (k in (start + a.start) until end) obs[k].junk = true
            }
        }
    }

    private fun charge(v: Visit, cost: Double) {
        v.cost += cost
        if (cost >= UNIT) v.hard += 1.0
    }

    private fun subCharge(r: Char, p: Char): Double =
        if (r == p) 0.0
        else if (CONSONANTS.contains(r) && CONSONANTS.contains(p)) subCost(r, p)
        else SOFT

    private fun delCharge(at: Int): Double =
        if ((at > 0 && ref[at] == ref[at - 1]) || (at + 1 < ref.length && ref[at] == ref[at + 1])) HELD
        else if (CONSONANTS.contains(ref[at])) UNIT.toDouble()
        else SOFT

    /** Inserting `p` before `ref[at]`, after the heard `prev`. */
    private fun insCharge(at: Int, p: Char, prev: Char?): Double =
        if ((at > 0 && p == ref[at - 1]) || (at < ref.length && p == ref[at]) || (prev != null && p == prev)) HELD
        else if (CONSONANTS.contains(p)) UNIT.toDouble()
        else SOFT

    /** Min-charge alignment of `obs[from, to)` onto word `wi`. The path is that alignment. */
    private fun chargeAlign(wi: Int, from: Int, to: Int): List<CheckerStep> {
        val start = words[wi].start
        val n = words[wi].end - start
        val m = maxOf(0, to - from)
        val width = m + 1
        val cost = DoubleArray((n + 1) * width)
        val back = IntArray((n + 1) * width)
        val kind = IntArray((n + 1) * width)
        for (j in 1..m) {
            cost[j] = cost[j - 1] + insCharge(start, obs[from + j - 1].p, obs.getOrNull(from + j - 2)?.p)
            back[j] = j - 1
            kind[j] = OP_INS
        }
        for (i in 1..n) {
            val at = start + i - 1
            val del = delCharge(at)
            val row = i * width
            val above = row - width
            cost[row] = cost[above] + del
            back[row] = above
            kind[row] = OP_DEL
            for (j in 1..m) {
                val p = obs[from + j - 1].p
                val sub = subCharge(ref[at], p)
                var best = cost[above + j - 1] + sub
                var b = above + j - 1
                var k = OP_DIAG
                val dcost = cost[above + j] + del
                if (dcost < best) {
                    best = dcost
                    b = above + j
                    k = OP_DEL
                }
                val icost = cost[row + j - 1] + insCharge(at + 1, p, obs.getOrNull(from + j - 2)?.p)
                if (icost < best) {
                    best = icost
                    b = row + j - 1
                    k = OP_INS
                }
                cost[row + j] = best
                back[row + j] = b
                kind[row + j] = k
            }
        }
        val path = ArrayList<CheckerStep>()
        var cell = n * width + m
        while (cell > 0) {
            val i = cell / width
            val j = cell % width
            val how = kind[cell]
            if (how == OP_DIAG) path.add(CheckerStep.Diag(start + i - 1, from + j - 1))
            else if (how == OP_DEL) path.add(CheckerStep.Del(start + i - 1))
            else path.add(CheckerStep.Ins(start + i, from + j - 1))
            cell = back[cell]
        }
        path.reverse()
        return path
    }

    /** Whether a visit's cost makes the words `[v.word, last]` it covers a wrong word rather than a mispronounced one. */
    private fun wrong(v: Visit, last: Int = v.word): Boolean {
        var weight = 0.0
        var consonants = 0
        for (k in words[v.word].start until words[last].end) {
            val hard = CONSONANTS.contains(ref[k])
            weight += if (hard) UNIT.toDouble() else SOFT
            if (hard) consonants++
        }
        val length = words[last].end - words[v.word].start
        return v.cost / ((weight * v.covered) / length) > WRONG &&
            v.hard >= maxOf(1, minOf(MIN_HARD, consonants)).toDouble()
    }

    private fun skeleton(wi: Int): String =
        ref.substring(words[wi].start, words[wi].end).filter { CONSONANTS.contains(it) }

    private fun hasConsonant(wi: Int): Boolean =
        ref.substring(words[wi].start, words[wi].end).any { CONSONANTS.contains(it) }

    /** Score phonemes heard where the alignment skipped words `[from, to]`, as one visit over them. */
    private fun scoreGap(from: Int, to: Int, said: List<Heard>): Visit {
        val start = words[from].start
        val end = words[to].end
        val v = Visit(word = from, covered = end - start, consumed = said.size)
        val a = alignSubstring(
            ref, start, end, said.joinToString("") { it.p.toString() },
            { o -> o == start }, AlignOptions(strictStart = true),
        ) ?: return v.apply { cost = Double.POSITIVE_INFINITY; hard = Double.POSITIVE_INFINITY }
        var at = start
        for (o in a.path) {
            when (o) {
                is AlignmentOp.Ins -> charge(v, insCharge(at, o.obs, said.getOrNull(o.obsAt - 1)?.p))
                is AlignmentOp.Del -> {
                    at = a.start + o.refAt + 1
                    charge(v, delCharge(at - 1))
                }
                is AlignmentOp.Match -> {
                    at = a.start + o.refAt + 1
                    charge(v, subCharge(o.ref, o.ref))
                }
                is AlignmentOp.Sub -> {
                    at = a.start + o.refAt + 1
                    charge(v, subCharge(o.ref, o.obs))
                }
            }
        }
        for (k in a.end until end) charge(v, delCharge(k))
        return v
    }

    /**
     * Costs of word `wi` read from its far edge (its start when `after`, else its end) against the first `j` of
     * `heard`, read the same way, for every `j`.
     */
    private fun edgeFit(wi: Int, heard: List<Char>, after: Boolean, noise: Boolean = false): DoubleArray {
        val w = words[wi]
        val n = w.end - w.start
        val r = { i: Int -> if (after) w.start + i else w.end - 1 - i }
        val pos = { i: Int -> if (after) w.start + i else w.end - i }
        val ins = { i: Int, j: Int ->
            val cost = insertionCost(ref, pos(i), heard[j - 1], w.end)
            if (noise) minOf(cost, if (j >= 2 && heard[j - 1] == heard[j - 2]) HELD else UNIT.toDouble()) else cost
        }
        val del = { i: Int ->
            if (noise && ((r(i) > 0 && ref[r(i)] == ref[r(i) - 1]) || (r(i) + 1 < ref.length && ref[r(i)] == ref[r(i) + 1]))) HELD
            else deletionCost(ref, r(i))
        }
        var row = DoubleArray(heard.size + 1)
        for (j in 1..heard.size) row[j] = row[j - 1] + ins(0, j)
        for (i in 1..n) {
            val next = DoubleArray(heard.size + 1)
            next[0] = row[0] + del(i - 1)
            for (j in 1..heard.size) {
                next[j] = minOf(
                    row[j - 1] + subCost(ref[r(i - 1)], heard[j - 1]),
                    row[j] + del(i - 1),
                    next[j - 1] + ins(i, j),
                )
            }
            row = next
        }
        return row
    }

    /**
     * An added word found inside a word can take the word's edge with it: وبنين كريم aligned as وبنِ[ينكر]يم, held
     * sounds being cheap to insert beside the word's own. Unless the word fits worse on its own, split the visit's
     * heard phonemes again where it fits best, the added word keeping the rest on its side: it was said between
     * the words.
     */
    private fun resplit(issue: Issue, v: Visit, after: Boolean) {
        if (v.obsFrom < 0) return
        val at = ArrayList<Int>()
        for (k in v.obsFrom until v.obsTo) if (!obs[k].junk) at.add(k)
        if (!after) at.reverse()
        val heard = at.map { obs[it].p }
        val keep = at.filter { it < issue.obsFrom || it >= issue.obsTo }.map { obs[it].p }
        val now = edgeFit(v.word, keep, after)[keep.size]
        val fit = edgeFit(v.word, heard, after)
        var best = -1
        for (s in 0..heard.size) {
            if (s + MIN_ADDED + 1 > heard.size) break
            if (heard.subList(s, heard.size).count { CONSONANTS.contains(it) } < 2) continue
            if (best < 0 || fit[s] < fit[best]) best = s
        }
        if (best < 0 || fit[best] > now) return
        val rest = at.subList(best, at.size).sorted()
        issue.obsFrom = rest[0]
        issue.obsTo = rest.last() + 1
        issue.observed = rest.joinToString("") { obs[it].p.toString() }
    }

    /** Rebuild a whole-word fit after choosing an insertion between two intact neighboring words. */
    private fun wordSteps(wi: Int, from: Int, to: Int): List<CheckerStep> {
        val word = words[wi]
        val n = word.end - word.start
        val m = to - from
        val width = m + 1
        val d = DoubleArray((n + 1) * width)
        val op = IntArray(d.size)
        val ins = { at: Int, j: Int ->
            minOf(
                insertionCost(ref, at, obs[from + j - 1].p, word.end),
                if (j >= 2 && obs[from + j - 1].p == obs[from + j - 2].p) HELD else UNIT.toDouble(),
            )
        }
        val del = { at: Int ->
            if ((at > 0 && ref[at] == ref[at - 1]) || (at + 1 < ref.length && ref[at] == ref[at + 1])) HELD
            else deletionCost(ref, at)
        }
        for (j in 1..m) {
            d[j] = d[j - 1] + ins(word.start, j)
            op[j] = OP_INS
        }
        for (i in 1..n) {
            val row = i * width
            val prev = row - width
            val at = word.start + i - 1
            d[row] = d[prev] + del(at)
            op[row] = OP_DEL
            for (j in 1..m) {
                val p = obs[from + j - 1].p
                d[row + j] = d[prev + j - 1] + subCost(ref[at], p)
                op[row + j] = OP_DIAG
                val deletion = d[prev + j] + del(at)
                if (deletion < d[row + j]) {
                    d[row + j] = deletion
                    op[row + j] = OP_DEL
                }
                val insertion = d[row + j - 1] + ins(at + 1, j)
                if (insertion < d[row + j]) {
                    d[row + j] = insertion
                    op[row + j] = OP_INS
                }
            }
        }
        val path = ArrayList<CheckerStep>()
        var i = n
        var j = m
        while (i != 0 || j != 0) {
            val o = op[i * width + j]
            if (o == OP_DIAG) {
                i--
                j--
                path.add(CheckerStep.Diag(word.start + i, from + j))
            } else if (o == OP_DEL) {
                i--
                path.add(CheckerStep.Del(word.start + i))
            } else {
                j--
                path.add(CheckerStep.Ins(word.start + i, from + j))
            }
        }
        path.reverse()
        return path
    }

    private data class FitMatch(val refAt: Int, val obs: Int)
    private data class FitSpanResult(val cost: Double, val matches: List<FitMatch>)

    /**
     * One insertion/deletion alignment of `reference` against `obs[from, to)`, the same DP as `wordSteps`. Each heard
     * phoneme is consumed once, so a consonant on the next word cannot also satisfy a suffix.
     */
    private fun fitSpan(reference: String, from: Int, to: Int): FitSpanResult? {
        val n = reference.length
        val m = to - from
        if (n == 0 || m < 0 || from < 0 || to > obs.size) return null
        val width = m + 1
        val d = DoubleArray((n + 1) * width)
        val op = IntArray(d.size)
        val ins = { at: Int, j: Int ->
            minOf(
                insertionCost(reference, at, obs[from + j - 1].p, n),
                if (j >= 2 && obs[from + j - 1].p == obs[from + j - 2].p) HELD else UNIT.toDouble(),
            )
        }
        val del = { at: Int ->
            if ((at > 0 && reference[at] == reference[at - 1]) ||
                (at + 1 < reference.length && reference[at] == reference[at + 1])
            ) HELD else deletionCost(reference, at)
        }
        for (j in 1..m) {
            d[j] = d[j - 1] + ins(0, j)
            op[j] = OP_INS
        }
        for (i in 1..n) {
            val row = i * width
            val prev = row - width
            val at = i - 1
            d[row] = d[prev] + del(at)
            op[row] = OP_DEL
            for (j in 1..m) {
                val p = obs[from + j - 1].p
                d[row + j] = d[prev + j - 1] + subCost(reference[at], p)
                op[row + j] = OP_DIAG
                val deletion = d[prev + j] + del(at)
                if (deletion < d[row + j]) {
                    d[row + j] = deletion
                    op[row + j] = OP_DEL
                }
                val insertion = d[row + j - 1] + ins(at + 1, j)
                if (insertion < d[row + j]) {
                    d[row + j] = insertion
                    op[row + j] = OP_INS
                }
            }
        }
        val matches = ArrayList<FitMatch>()
        var i = n
        var j = m
        while (i != 0 || j != 0) {
            val o = op[i * width + j]
            if (o == OP_DIAG) {
                i--
                j--
                matches.add(FitMatch(i, from + j))
            } else if (o == OP_DEL) i--
            else if (j != 0) j--
            else break
        }
        return FitSpanResult(d[n * width + m], matches)
    }

    /**
     * The hole between the remaining word's last confident phone and the next word's confident onset cannot hold the
     * dropped consonant and a long vowel. No confident edge on either side is not evidence the ending was unspoken.
     */
    private fun suffixUnspoken(formLength: Int, matches: List<FitMatch>): Boolean {
        if (frameMs <= 0) return false
        var stemEnd = -1
        var onsetStart = Int.MAX_VALUE
        for (m in matches) {
            val ph = obs[m.obs]
            if (ph.conf < minConfidence) continue
            if (m.refAt < formLength) stemEnd = maxOf(stemEnd, ph.end)
            else onsetStart = minOf(onsetStart, ph.start)
        }
        return stemEnd >= 0 && onsetStart < Int.MAX_VALUE &&
            (onsetStart - stemEnd - 1) * frameMs < SUFFIX_HOLD_MS
    }

    private data class Repair(val from: Int, val to: Int, val path: List<CheckerStep>)
    private data class FitForm(val form: String, val cost: Double, val matches: List<FitMatch>)
    private data class ParallelBest(
        val path: ParallelPath,
        val span: Pair<Int, Int>,
        val margin: Double,
        val need: Double,
    )

    /** The errors the current alignment shows, by word; and where it stands. `bound` is the words a counting visit holds. */
    private fun judge(inputSteps: List<CheckerStep>, recoverAdditions: Boolean = true): JudgeResult? {
        val steps = inputSteps.toMutableList()
        val visits = ArrayList<Visit>()
        val added = ArrayList<Issue>()
        var cur: Visit? = null
        var block = ArrayList<Int>()
        /** Insertions inside the current word, with where they sit. */
        var inner = ArrayList<Pair<Int, Int>>()
        var head = -1
        /** Added words found inside a visit, re-split once the visit is complete. */
        val within = ArrayList<Triple<Issue, Visit, Boolean>>()
        // A long run inside a word is another word said there, before it or after it by which half it sits in.
        fun flushInner() {
            val v = cur
            if (v == null || inner.isEmpty()) {
                inner = ArrayList()
                return
            }
            if (inner.size >= MIN_ADDED + 1 &&
                inner.count { CONSONANTS.contains(obs[it.second].p) } >= 2
            ) {
                val w = words[v.word]
                val after = inner[0].first - w.start >= (w.end - w.start) / 2.0
                val word = if (after) nextWord(v.word) else v.word
                val text = inner.joinToString("") { obs[it.second].p.toString() }
                val issue = Issue(
                    cls = "added_word", word = word, last = word,
                    obsFrom = inner[0].second, obsTo = inner.last().second + 1, observed = text,
                )
                added.add(issue)
                within.add(Triple(issue, v, after))
            } else {
                for (k in inner) charge(v, insCharge(k.first, obs[k.second].p, obs.getOrNull(k.second - 1)?.p))
            }
            inner = ArrayList()
        }
        fun close() {
            flushInner()
            cur?.let { visits.add(it) }
            cur = null
        }
        var stepAt = 0
        while (stepAt < steps.size) {
            val s = steps[stepAt]
            when (s) {
                is CheckerStep.Jump -> {
                    close()
                    block = ArrayList()
                }
                is CheckerStep.Lead -> {
                    // Lead phonemes are dropped before the anchor; they belong to no word.
                }
                is CheckerStep.Ins -> {
                    val c = cur
                    val inside = c != null && !wordStarts.contains(s.at) &&
                        s.at > words[c.word].start && s.at < words[c.word].end
                    if (!obs[s.obs].junk) {
                        if (inside) {
                            inner.add(Pair(s.at, s.obs))
                            c!!.obsTo = s.obs + 1
                            c.stepTo = stepAt + 1
                        } else if (cur != null || visits.isNotEmpty()) block.add(s.obs)
                    }
                }
                is CheckerStep.Diag, is CheckerStep.Del -> {
                    val refAt = if (s is CheckerStep.Diag) s.ref else (s as CheckerStep.Del).ref
                    val wi = index.wordAt(refAt)
                    if (cur == null || cur!!.word != wi) {
                        val prev: Visit? = cur
                        close()
                        // Only between two consecutive words of one run: elsewhere an insertion is junk around a jump or the lock edge.
                        if (block.size >= MIN_ADDED &&
                            block.count { CONSONANTS.contains(obs[it].p) } >= 2 &&
                            prev != null && nextWord(prev.word) == wi
                        ) {
                            val text = block.joinToString("") { obs[it].p.toString() }
                            added.add(
                                Issue(
                                    cls = "added_word", word = wi, last = wi,
                                    obsFrom = block[0], obsTo = block.last() + 1, observed = text,
                                ),
                            )
                        }
                        block = ArrayList()
                        cur = Visit(
                            word = wi,
                            obsFrom = if (s is CheckerStep.Diag) s.obs else -1,
                            obsTo = -1,
                            stepFrom = stepAt,
                        )
                    }
                    flushInner()
                    val v: Visit = cur!!
                    v.stepTo = stepAt + 1
                    v.covered++
                    if (s is CheckerStep.Diag) {
                        if (v.obsFrom < 0) v.obsFrom = s.obs
                        v.obsTo = s.obs + 1
                        v.consumed++
                        charge(v, subCharge(ref[s.ref], obs[s.obs].p))
                        if (ref[s.ref] == obs[s.obs].p && CONSONANTS.contains(ref[s.ref])) v.kept++
                        head = wi
                    } else charge(v, delCharge((s as CheckerStep.Del).ref))
                }
            }
            stepAt++
        }
        close()
        for (x in within) resplit(x.first, x.second, x.third)

        val length = { wi: Int -> words[wi].end - words[wi].start }
        // Vowels alone or one isolated consonant aren't a multi-phoneme word: a neighbour's noisy onset can land on it.
        val counts = { v: Visit ->
            v.consumed > 0 && v.covered.toDouble() / length(v.word) >= MIN_COVERAGE &&
                (if (hasConsonant(v.word)) v.kept > 0 && v.consumed >= minOf(2, length(v.word)) else true)
        }
        /** Each word's last counting visit, and whether any counting visit got it right. */
        val last = LinkedHashMap<Int, Visit>()
        val right = LinkedHashSet<Int>()
        for (v in visits) {
            if (!counts(v) || v.word < anchor!!) continue
            last[v.word] = v
            if (!wrong(v)) right.add(v.word)
        }
        for (v in visits) {
            if (!counts(v) || wrong(v) || v.word < anchor!! || v.obsFrom < 0 || v.obsTo <= v.obsFrom) continue
            growSpan(readAyah, ayahOf[v.word], v.word, obs[v.obsFrom].start, obs[v.obsTo - 1].end + 1)
        }
        if (recoverAdditions) {
            // Use whole-word pronunciations here: a cheap one-consonant substitution is tolerated by the
            // ordinary checker, but cannot prove that an intervening word was actually repeated.
            val original = visits.filter { counts(it) && !wrong(it) }
            val intact = original.filter { v -> lexical.matches(v.word, heardString(v.obsFrom, v.obsTo)) }
            val repairs = ArrayList<Repair>()
            val weight = { wi: Int ->
                ref.substring(words[wi].start, words[wi].end)
                    .sumOf { if (CONSONANTS.contains(it)) UNIT.toDouble() else SOFT }
            }
            // Preserve the existing repairs first; stricter extra candidates cannot replace their evidence.
            val pairs = ArrayList<Triple<Visit, Visit, Boolean>>()
            for (i in 1 until original.size) pairs.add(Triple(original[i - 1], original[i], false))
            for (i in 1 until intact.size) {
                pairs.add(Triple(intact[i - 1], intact[i], true))
                // An isolated forward detour cannot be a repeat of earlier speech. Backward detours
                // remain protected, as do multiword excursions and attempts at either neighbor.
                if (i >= 2) {
                    val left = intact[i - 2]
                    val detour = intact[i - 1]
                    val rightV = intact[i]
                    if (nextWord(left.word) == rightV.word && detour.word > nextWord(rightV.word) &&
                        !visits.any { v ->
                            v.word == detour.word && v.obsTo <= left.obsFrom && v.obsFrom >= 0 &&
                                lexical.matches(v.word, heardString(v.obsFrom, v.obsTo))
                        }
                    ) {
                        pairs.add(Triple(left, rightV, true))
                    }
                }
            }
            for ((left, rightV, strict) in pairs) {
                if (nextWord(left.word) != rightV.word || left.obsTo > rightV.obsFrom ||
                    left.stepFrom == null || rightV.stepTo == null
                ) continue
                if (repairs.any { left.stepFrom!! < it.to && rightV.stepTo!! > it.from }) continue
                // New segmentation candidates must establish an addition while its following word
                // is still current or just completed. Later realignment cannot open a stale warning;
                // an already suspected addition keeps its evidence until confirmation.
                if (strict && open[rightV.word]?.issue?.cls != "added_word" &&
                    intact.count { it.obsFrom >= rightV.obsTo } > 1
                ) continue
                // A tolerated repeat of the preceding word stays a repeat even if its pronunciation
                // is not an exact lexical form. Only the new strict candidates need this protection.
                if (strict && visits.any { v ->
                        v.word == left.word && v.obsFrom >= left.obsTo && v.obsTo <= rightV.obsFrom &&
                            counts(v) && !wrong(v)
                    }
                ) continue
                // A wrong attempt at the following word, then that word read correctly, is a self-correction.
                if (open[rightV.word]?.issue?.cls == "wrong_word" ||
                    visits.any { v ->
                        v.word == rightV.word && v.obsFrom >= left.obsTo && v.obsFrom < rightV.obsFrom
                    }
                ) continue
                if (added.any { a -> a.word == rightV.word && a.obsFrom >= left.obsFrom && a.obsTo <= rightV.obsTo }) continue
                val from = left.obsFrom
                val to = rightV.obsTo
                // A visit can start with deletions and insertions before its first diagonal match. Its obsFrom then
                // excludes that leading evidence; only replace a path whose observed indices are covered exactly.
                val represented = steps.subList(left.stepFrom!!, rightV.stepTo!!)
                    .filter { it.hasObs }.map { it.obsIndex }
                if (represented.size != to - from ||
                    represented.withIndex().any { (j, oi) -> oi != from + j }
                ) continue
                val heard = obsSlice(from, to)
                if (heard.any { it.junk }) continue
                val phones = heard.map { it.p }
                val n = phones.size
                val leftLength = words[left.word].end - words[left.word].start
                val rightLength = words[rightV.word].end - words[rightV.word].start
                if (n < leftLength + rightLength + MIN_ADDED) continue
                val leftNow = phones.subList(0, left.obsTo - from)
                val rightNow = phones.subList(rightV.obsFrom - from, phones.size).reversed()
                val leftLimit = minOf(WRONG * weight(left.word), edgeFit(left.word, leftNow, true, true)[leftNow.size])
                val rightLimit = minOf(
                    WRONG * weight(rightV.word),
                    edgeFit(rightV.word, rightNow, false, true)[rightNow.size],
                )
                val l = edgeFit(left.word, phones, true, true)
                val r = edgeFit(rightV.word, phones.reversed(), false, true)
                var joined = Double.POSITIVE_INFINITY
                for (a in 0..n) joined = minOf(joined, l[a] + r[n - a])
                val consonants = ArrayList<Int>()
                consonants.add(0)
                for (p in phones) consonants.add(consonants.last() + if (CONSONANTS.contains(p)) 1 else 0)
                var best: Triple<Int, Int, Double>? = null
                for (a in 1 until n) {
                    if (a + MIN_ADDED >= n) break
                    if (l[a] > leftLimit) continue
                    for (b in (a + MIN_ADDED) until n) {
                        if (consonants[b] - consonants[a] < 2 || r[n - b] > rightLimit) continue
                        val cost = l[a] + r[n - b]
                        if (joined - cost < MIN_HARD * UNIT) continue
                        if (best == null || cost < best!!.third ||
                            (cost == best!!.third && b - a < best!!.second - best!!.first)
                        ) best = Triple(a, b, cost)
                    }
                }
                if (best == null ||
                    phones.subList(best!!.first, best!!.second).filter { CONSONANTS.contains(it) }.toSet().size < 2
                ) continue
                val a = from + best!!.first
                val b = from + best!!.second
                val insertion = (a until b).map { CheckerStep.Ins(words[rightV.word].start, it) }
                repairs.add(
                    Repair(
                        left.stepFrom!!, rightV.stepTo!!,
                        wordSteps(left.word, from, a) + insertion + wordSteps(rightV.word, b, to),
                    ),
                )
            }
            if (repairs.isNotEmpty()) {
                val repaired = steps.toMutableList()
                for (r in repairs.sortedByDescending { it.from }) {
                    repaired.subList(r.from, r.to).clear()
                    repaired.addAll(r.from, r.path)
                }
                return judge(repaired, false)
            }
        }
        val stepEdits = ArrayList<StepEdit>()
        val seen = last.keys.toList()
        val issues = LinkedHashMap<Int, Issue>()
        if (seen.isEmpty()) return null
        // The lock span's first word can be a cut-off tail or leading junk: span edges are only good to a word.
        val first = seen.min() + if (fromLock) 1 else 0
        // A wrong visit far ahead can be junk aligned to a short word; only correct visits show how far the reciter got.
        val reached = (seen.filter { right.contains(it) } + first).max()
        var gap: Issue? = null
        fun flush(after: Visit? = null) {
            val g = gap ?: return
            // Evidence inside the gap means the words were said, differently: a wrong word, not an omission. Phrase marks
            // don't count against that, a garbled word can look like "amin".
            val said = if (after != null) obsSlice(g.obsFrom, after.obsFrom) else emptyList()
            val expected = words[g.last].end - words[g.word].start
            if (said.size >= maxOf(MIN_ADDED.toDouble(), expected / 2.0) &&
                said.any { CONSONANTS.contains(it.p) }
            ) {
                if (wrong(scoreGap(g.word, g.last, said), g.last)) {
                    issues[g.word] = g.copy(
                        cls = "wrong_word", obsTo = after!!.obsFrom,
                        observed = said.joinToString("") { it.p.toString() }, stringPath = true,
                    )
                }
                gap = null
                return
            }
            // Words past the head may still be coming: a gap is an omission once the reciter is past it, or already suspected.
            val was = open[g.word]?.issue?.cls
            if (!(g.last < head || was == "omitted_word" || was == "skipped_passage")) {
                gap = null
                return
            }
            // One heard stretch, two adjacent words, the outer neighbour already accepted. The alignment kept the
            // stretch on the word its hand costs prefer. Rank the two survivors by the listener channel instead,
            // and move the omission onto the other word when that survivor explains the stretch. The omission
            // count does not change. A tie, or an empty channel, leaves the alignment's placement.
            if (rankOmission && g.word == g.last && after != null && said.isEmpty() &&
                !issues.containsKey(prevWord(g.word))
            ) {
                val y = g.word
                val x = prevWord(y)
                val leftW = prevWord(x)
                val seg = last[x]
                val leftV = last[leftW]
                if (ayahOf[x] == ayahOf[y] && seg != null && right.contains(x) && seg.obsTo == g.obsFrom &&
                    seg.obsFrom >= 0 && seg.stepFrom != null && seg.stepTo != null &&
                    leftV != null && right.contains(leftW) && leftV.obsTo >= 0 &&
                    after.word == nextWord(y)
                ) {
                    val from = leftV.obsTo
                    val to = seg.obsTo
                    // More phones than the two words is other speech, not one segment shared by them.
                    val budget = words[x].end - words[x].start + (words[y].end - words[y].start)
                    val heard = if (to > from && to - from <= budget) {
                        collapseHeld(obsSlice(from, to).filter { !it.junk }.map { it.p })
                    } else emptyList()
                    val written = { wi: Int ->
                        collapseHeld(ref.substring(words[wi].start, words[wi].end).toList())
                    }
                    if (heard.isNotEmpty() &&
                        spanLogP(heard, written(y), omissionChannel) > spanLogP(heard, written(x), omissionChannel)
                    ) {
                        val dels = ArrayList<CheckerStep>()
                        for (k in words[x].start until words[x].end) dels.add(CheckerStep.Del(k))
                        var loE = seg.stepFrom!!
                        var hiE = seg.stepTo!!
                        for (k in steps.indices) {
                            val s = steps[k]
                            val o = if (s.hasObs) s.obsIndex else -1
                            val at = s.refIndex
                            val w = if (at >= 0) index.wordAt(at) else -1
                            if ((o >= from && o < to) || w == x || w == y) {
                                loE = minOf(loE, k)
                                hiE = maxOf(hiE, k + 1)
                            }
                        }
                        if (hiE < steps.size && steps[hiE] is CheckerStep.Jump) {
                            var k = hiE + 1
                            while (k < steps.size &&
                                (steps[k] is CheckerStep.Jump || steps[k] is CheckerStep.Lead)
                            ) k++
                            val s = steps.getOrNull(k)
                            val at = when (s) {
                                is CheckerStep.Diag -> s.ref
                                is CheckerStep.Del -> s.ref
                                is CheckerStep.Ins -> s.at
                                else -> -1
                            }
                            if (at >= 0 && index.wordAt(at) == after.word) hiE++
                        }
                        stepEdits.add(StepEdit(loE, hiE, dels + chargeAlign(y, from, to)))
                        right.remove(x)
                        issues[x] = Issue(cls = "omitted_word", word = x, last = x, obsFrom = from, obsTo = from)
                        gap = null
                        return
                    }
                }
            }
            // A word either side of a skip can recur on the other side (برب الناس [ملك الناس] إله الناس), so the alignment
            // can place the gap a word off. Of the equal placements, take the one on an ayah's start: reciters skip ayahs.
            // A look-alike ending (one leading consonant, the article heard erased) is the same slide, but only when that
            // jump sits on a blank run the pause test already labels, or on a cut-off visit. Nothing anchored: the lexical
            // placement stands, so a skip is never created or removed.
            val onStart = { i: Issue -> ayahFirst[ayahOf[i.word]] == i.word }
            val alike = { a: Int, b: Int ->
                val x = skeleton(a)
                val y = skeleton(b)
                if (x.isEmpty() || y.isEmpty() || x == y) false
                else {
                    val longer = if (x.length >= y.length) x else y
                    val shorter = if (x.length >= y.length) y else x
                    longer.length == shorter.length + 1 && longer.endsWith(shorter)
                }
            }
            val slide = { base: Issue, match: (Int, Int) -> Boolean ->
                val out = ArrayList<Issue>()
                var r = base
                var v = after
                while (v != null && nextWord(r.last) == v.word && match(r.word, v.word)) {
                    r = r.copy(word = nextWord(r.word), last = v.word, obsFrom = v.obsTo, obsTo = v.obsTo)
                    out.add(r)
                    v = last[nextWord(v.word)]
                }
                var l = base
                var u = last[prevWord(l.word)]
                while (u != null && match(u.word, l.last)) {
                    l = l.copy(word = u.word, last = prevWord(l.last), obsFrom = u.obsFrom, obsTo = u.obsFrom)
                    out.add(l)
                    u = last[prevWord(u.word)]
                }
                out
            }
            // Frames between two phonemes that the blank already owns. Missing posteriors leave the greedy blank run.
            val blankOwned = { from: Int, to: Int ->
                val f = frames
                if (f == null || f.startFrame < 0 || from < f.startFrame || to > f.endFrame) true
                else {
                    val owns = Math.log(0.5)
                    var ok = true
                    for (fr in from until to) if (f.row(fr)[0] < owns) {
                        ok = false
                        break
                    }
                    ok
                }
            }
            val anchored = { i: Issue ->
                val at = i.obsFrom
                var result = false
                if (at > 0 && at < obs.size && frameMs > 0) {
                    val a = obs[at - 1].end + 1
                    val b = obs[at].start
                    if (b - a >= minGapFrames(frameMs) && blankOwned(a, b)) result = true
                }
                if (!result) {
                    result = visits.any { v ->
                        v.consumed > 0 && v.obsFrom >= 0 &&
                            v.covered.toDouble() / length(v.word) < MIN_COVERAGE &&
                            v.obsFrom <= at && at <= v.obsTo
                    }
                }
                result
            }
            var current = g
            if (!onStart(current)) {
                val today = slide(current) { a, b -> skeleton(a) == skeleton(b) }.find { onStart(it) } ?: current
                val pauseSeam = if (onStart(today)) null
                else slide(current, alike).find { onStart(it) && anchored(it) }
                current = pauseSeam ?: today
            }
            val n = words.subList(current.word, current.last + 1).count { it.end > it.start }
            issues[current.word] = current.copy(cls = if (n >= 3) "skipped_passage" else "omitted_word")
            gap = null
        }
        for (wi in first..seen.max()) {
            if (length(wi) == 0) continue
            val v = last[wi]
            if (v == null) {
                // The phonemes either side of the gap: the last one before it in the evidence is where the skip happened.
                val before = last.values.filter { it.word < wi }.fold(0) { a, x -> maxOf(a, x.obsTo) }
                val g = gap
                if (g != null) g.last = wi
                else gap = Issue(cls = "omitted_word", word = wi, last = wi, obsFrom = before, obsTo = before)
                continue
            }
            flush(v)
            // The word being recited is judged only once it reaches the word's end: before that it may still come right.
            if (wi == head && v.covered < length(wi)) continue
            // Once recited right, a later wrong visit is a repeat that went astray; a wrong one then a right one is a correction.
            if (!right.contains(wi)) {
                issues[wi] = Issue(
                    cls = "wrong_word", word = wi, last = wi, obsFrom = v.obsFrom, obsTo = v.obsTo,
                    observed = heardString(v.obsFrom, v.obsTo), stringPath = true,
                )
            }
        }
        flush()
        for (a in added) if (a.word >= first && !issues.containsKey(a.word)) issues[a.word] = a
        val candidates = ArrayList<Issue>()
        val heard = { v: Visit -> heardString(v.obsFrom, v.obsTo) }
        val complete = { v: Visit ->
            v.covered == length(v.word) && v.obsFrom >= 0 &&
                v.stepFrom != null && v.stepTo != null && lexical.matches(v.word, heard(v))
        }
        val intact = visits.filter { counts(it) && !wrong(it) && complete(it) }
        for (i in 1 until intact.size) {
            val left = intact[i - 1]
            val rightV = intact[i]
            val wi = nextWord(left.word)
            if (wi < first || nextWord(wi) != rightV.word || left.obsTo >= rightV.obsFrom ||
                issues.containsKey(wi)
            ) continue
            // Equal sounds straddling a boundary admit another split, especially madd and ghunna.
            if (listOf(left.obsTo, rightV.obsFrom).any { at ->
                    obs.getOrNull(at - 1)?.p == obs.getOrNull(at)?.p
                }
            ) continue
            // A genuinely correct visit elsewhere protects repeats and self-corrections. The old soft `right` set alone
            // cannot establish that: it includes the consonant changes this separate candidate path is meant to catch.
            if (visits.any { it.word == wi && complete(it) }) continue
            if (added.any { a -> a.obsFrom < rightV.obsTo && a.obsTo > left.obsFrom }) continue
            val path = steps.subList(left.stepFrom!!, rightV.stepTo!!)
            if (path.any { s ->
                    when (s) {
                        is CheckerStep.Jump, is CheckerStep.Lead -> true
                        is CheckerStep.Diag -> index.wordAt(s.ref) < left.word || index.wordAt(s.ref) > rightV.word
                        is CheckerStep.Del -> index.wordAt(s.ref) < left.word || index.wordAt(s.ref) > rightV.word
                        else -> false
                    }
                }
            ) continue
            val represented = path.filter { it.hasObs }.map { it.obsIndex }
            if (represented.size != rightV.obsTo - left.obsFrom ||
                represented.withIndex().any { (j, oi) -> oi != left.obsFrom + j }
            ) continue
            if (obsSlice(left.obsFrom, rightV.obsTo).any { it.junk }) continue
            val observed = heardString(left.obsTo, rightV.obsFrom)
            if (lexical.alternative(
                    wi, observed,
                    LexicalContext(
                        before = if (ayahOf[left.word] == ayahOf[wi]) heard(left) else null,
                        after = if (ayahOf[rightV.word] == ayahOf[wi]) heard(rightV) else null,
                    ),
                )
            ) {
                candidates.add(
                    Issue(
                        cls = "wrong_word", word = wi, last = wi,
                        obsFrom = left.obsTo, obsTo = rightV.obsFrom, observed = observed,
                        supportRange = Pair(left.obsFrom, rightV.obsTo),
                    ),
                )
            }
        }
        // A pronoun or dual ending rides in the tail, where a pause or a held vowel is cheap and no consonant was
        // substituted. The insertion gap before the next visited word supplies the complete heard form.
        val settled = visits.filter {
            counts(it) && !wrong(it) && it.word >= first && it.obsFrom >= 0 &&
                it.stepFrom != null && it.stepTo != null
        }
        for (i in 0 until settled.size - 1) {
            val left = settled[i]
            val rightV = settled[i + 1]
            if (nextWord(left.word) != rightV.word || left.obsTo > rightV.obsFrom ||
                rightV.stepFrom!! < left.stepTo!!
            ) continue
            if (issues.containsKey(left.word)) continue
            // Another visit that is this word keeps a repeat or a self-correction. The tail may sit past the last matched
            // phone, so this visit's own slice can still be the written form.
            if (settled.any { v -> v !== left && v.word == left.word && lexical.matches(v.word, heard(v)) }) continue
            val between = steps.subList(left.stepTo!!, rightV.stepFrom!!)
            if (between.any { it !is CheckerStep.Ins }) continue
            val gapObs = between.filterIsInstance<CheckerStep.Ins>().map { it.obs }
            if (gapObs.size != rightV.obsFrom - left.obsTo ||
                gapObs.withIndex().any { (j, oi) -> oi != left.obsTo + j }
            ) continue
            if (obsSlice(left.obsFrom, rightV.obsFrom).any { it.junk }) continue
            if (added.any { a -> a.obsFrom < rightV.obsFrom && a.obsTo > left.obsFrom }) continue
            val observed = heardString(left.obsFrom, rightV.obsFrom)
            val ending = lexical.ending(left.word, observed)
            // An observed suffix has decoded consonant frames whose confidence and posteriors can corroborate it. A missing
            // suffix has no such local anchor; the ordinary omission path remains responsible for that evidence.
            if (ending?.observedConsonant != true) continue
            candidates.add(
                Issue(
                    cls = "wrong_word", word = left.word, last = left.word,
                    obsFrom = left.obsFrom, obsTo = rightV.obsFrom, observed = observed, wordLocal = true,
                ),
            )
        }
        // The other direction: the ending consonant was not said. A jump to the next word is cheaper than deleting it,
        // and held-sound tolerance can lend that word's onset to the suffix, so the two words are fit as one span.
        for (i in 0 until settled.size - 1) {
            val left = settled[i]
            val rightV = settled[i + 1]
            if (nextWord(left.word) != rightV.word || left.obsTo > rightV.obsFrom ||
                rightV.stepFrom!! < left.stepTo!!
            ) continue
            if (issues.containsKey(left.word) || candidates.any { it.word == left.word }) continue
            if (settled.any { v -> v !== left && v.word == left.word && lexical.matches(v.word, heard(v)) }) continue
            val alts = lexical.droppedSuffixes(left.word)
            if (alts.isEmpty()) continue
            val from = left.obsFrom
            val to = rightV.obsTo
            if (to <= from || obsSlice(from, to).any { it.junk }) continue
            if (visits.any { v -> v !== left && v !== rightV && v.obsFrom < to && v.obsTo > from }) continue
            if (added.any { a -> a.obsFrom < to && a.obsTo > from }) continue
            val next = ref.substring(words[rightV.word].start, words[rightV.word].end)
            val written = fitSpan(
                ref.substring(words[left.word].start, words[left.word].end) + next, from, to,
            ) ?: continue
            var best: FitForm? = null
            for (form in alts) {
                val fit = fitSpan(form + next, from, to)
                if (fit != null && (best == null || fit.cost < best!!.cost)) {
                    best = FitForm(form, fit.cost, fit.matches)
                }
            }
            // The same margin as an added ending: the shorter pair beats the written pair by a consonant.
            if (best == null || written.cost - best!!.cost < UNIT) continue
            if (!suffixUnspoken(best!!.form.length, best!!.matches)) continue
            candidates.add(
                Issue(
                    cls = "wrong_word", word = left.word, last = left.word, obsFrom = from,
                    obsTo = if (rightV.obsFrom > from) rightV.obsFrom else left.obsTo, observed = best!!.form,
                    supportRange = Pair(from, to), pair = best!!.form + next,
                ),
            )
        }
        // No visit of the next word: its phones were matched as the ending. The span is this visit plus whatever was
        // heard before the following settled word, so the onset consonant is inside the fit and cannot also be the suffix.
        for (left in settled) {
            if (left.covered != length(left.word)) continue
            if (issues.containsKey(left.word) || candidates.any { it.word == left.word }) continue
            if (settled.any { v -> v !== left && v.word == left.word && lexical.matches(v.word, heard(v)) }) continue
            val partner = nextWord(left.word)
            if (partner >= words.size || settled.any { it.word == partner }) continue
            val alts = lexical.droppedSuffixes(left.word)
            if (alts.isEmpty()) continue
            val later = settled.find { it.obsFrom >= left.obsTo && it.word > left.word }
            val from = left.obsFrom
            val to = later?.obsFrom ?: left.obsTo
            if (to <= from || obsSlice(from, to).any { it.junk }) continue
            if (visits.any { v ->
                    v !== left && v.word != partner && v.obsFrom < to && v.obsTo > from && counts(v)
                }
            ) continue
            if (added.any { a -> a.obsFrom < to && a.obsTo > from }) continue
            val next = ref.substring(words[partner].start, words[partner].end)
            if (next.isEmpty()) continue
            val written = fitSpan(
                ref.substring(words[left.word].start, words[left.word].end) + next, from, to,
            ) ?: continue
            var best: FitForm? = null
            for (form in alts) {
                val fit = fitSpan(form + next, from, to)
                if (fit != null && (best == null || fit.cost < best!!.cost)) {
                    best = FitForm(form, fit.cost, fit.matches)
                }
            }
            if (best == null || written.cost - best!!.cost < UNIT) continue
            val seq = best!!.form + next
            var need = 0
            var got = 0
            for (k in next.indices) if (CONSONANTS.contains(next[k])) {
                need++
                val at = best!!.form.length + k
                if (best!!.matches.any { m -> m.refAt == at && obs[m.obs].p == seq[at] }) got++
            }
            if (need == 0 || got < need) continue
            if (!suffixUnspoken(best!!.form.length, best!!.matches)) continue
            var onsetObs = to
            for (m in best!!.matches) if (m.refAt >= best!!.form.length) onsetObs = minOf(onsetObs, m.obs)
            candidates.add(
                Issue(
                    cls = "wrong_word", word = left.word, last = left.word, obsFrom = from,
                    obsTo = if (onsetObs > from) onsetObs else left.obsTo, observed = best!!.form,
                    supportRange = Pair(from, to), pair = seq, pairOnset = true,
                ),
            )
        }
        // An added word at an ayah start sits in the greedy phones before word 1, after both seam words already
        // have a complete in-order visit. Pauses and waqf tails are not part of that word. Neighbours, their
        // prefixes and non-Quran phrases are not an addition.
        val phraseSpan = { s: String ->
            phrases.any { p ->
                if (Math.abs(p.length - s.length) > Math.ceil(p.length * 0.6).toInt() + 4) false
                else {
                    val hit = alignSubstring(s, 0, s.length, p, isStart = { true })
                    hit != null && hit.start == 0 && hit.end >= s.length - 1 &&
                        hit.cost / UNIT / p.length <= PHRASE_MAX_COST
                }
            }
        }
        for (w1 in visits.map { it.word }.toSet()) {
            if (w1 < first || words[w1].end == words[w1].start || ayahFirst[ayahOf[w1]] != w1) continue
            val prev = prevWord(w1)
            if (prev < 0 || ayahOf[prev] == ayahOf[w1]) continue
            val lefts = visits.filter { it.word == prev && complete(it) }
            val rights = visits.filter { it.word == w1 && complete(it) }
            var best: Pair<Visit, Visit>? = null
            for (rightV in rights) for (left in lefts) {
                if (left.obsTo > rightV.obsFrom) continue
                if (left.stepTo != null && rightV.stepFrom != null && rightV.stepFrom!! < left.stepTo!!) continue
                if (best == null || rightV.obsFrom < best!!.second.obsFrom ||
                    (rightV.obsFrom == best!!.second.obsFrom && left.obsTo > best!!.first.obsTo)
                ) best = Pair(left, rightV)
            }
            if (best == null || best!!.second.obsFrom <= best!!.first.obsTo) continue
            val raw = obsSlice(best!!.first.obsTo, best!!.second.obsFrom)
                .filter { !it.junk }.joinToString("") { it.p.toString() }
            val said = stripEdge(raw)
            if (said.isEmpty() || phraseSpan(said)) continue
            if (lexical.surplusWord(w1, said) == null) continue
            val existing = issues[w1]
            if (existing != null && existing.cls != "added_word") continue
            issues[w1] = Issue(
                cls = "added_word", word = w1, last = w1,
                obsFrom = best!!.first.obsTo, obsTo = best!!.second.obsFrom, observed = said, lexicon = true,
            )
        }
        // A mushaf waqf sign inside an ayah, where the reciter actually stops. The previous word is read in its
        // pausal form and the pause is measured from the end of that token's own run, so one held frame of the
        // same class does not eat the silence. Ayah seams stay on the loop above.
        val formed = { s: String -> collapseHeld(s.toList()).joinToString("") }
        val vowel = setOf('َ', 'ُ', 'ِ', 'ا', 'ۦ', 'ۥ', '۪')
        val residueConsonant = CONSONANTS + setOf('ڇ', 'ں', '۾')
        val inventory = index.tokens.toList()
        val gapBlank = { from: Int, to: Int ->
            val f = frames
            if (f == null || f.startFrame < 0 || from < f.startFrame || to > f.endFrame) true
            else {
                val owns = Math.log(0.5)
                var ok = true
                for (fr in from until to) if (f.row(fr)[0] < owns) {
                    ok = false
                    break
                }
                ok
            }
        }
        val argmax = { frame: Int ->
            val row = frames!!.row(frame)
            var best = 0
            for (i in 1 until row.size) if (row[i] > row[best]) best = i
            best
        }
        /** The silence after `prev`, starting once that token's class is no longer the argmax. */
        val runEndPause = { prev: Int ->
            if (frameMs <= 0 || prev < 0 || prev + 1 >= obs.size) false
            else {
                var a = obs[prev].end + 1
                val b = obs[prev + 1].start
                val f = frames
                if (f != null && f.startFrame >= 0 && a >= f.startFrame && b <= f.endFrame) {
                    val id = tokenId[obs[prev].p]
                    if (id != null) while (a < b && argmax(a) == id) a++
                }
                b - a >= minGapFrames(frameMs) && gapBlank(a, b)
            }
        }
        val pausalVisit = { v: Visit ->
            val iso = index.isolatedWordForms[v.word]
            if (iso.isEmpty() || v.obsFrom < 0 || v.obsTo <= v.obsFrom || v.stepFrom == null || v.stepTo == null) false
            else formed(heard(v)) == formed(iso) || formed(stripEdge(heard(v))) == formed(stripEdge(iso))
        }
        // Obs index where the stem (and its proclitic vowel, if heard) begins. Phones before that in the
        // visit were glued on from the surplus. Null when the visit is not this word minus its proclitic.
        val residueClear = { at: Int ->
            val f = frames
            if (f == null || f.startFrame < 0) true
            else {
                val a = obs[at].start
                val b = obs[at].end + 1
                if (a < f.startFrame || b > f.endFrame) true
                else {
                    var clear = true
                    for (fr in a until b) {
                        val id = argmax(fr)
                        val c = inventory.getOrNull(id - 1)
                        if (id != 0 && c != null && residueConsonant.contains(c)) {
                            clear = false
                            break
                        }
                    }
                    clear
                }
            }
        }
        val procliticCut = { v: Visit ->
            var result: Int? = null
            var abort = false
            if (procliticOnset(v.word) && v.obsFrom >= 0 && v.obsTo > v.obsFrom &&
                v.stepFrom != null && v.stepTo != null
            ) {
                val heardRaw = heard(v)
                val tails = ArrayList<String>()
                for (full in listOf(
                    ref.substring(words[v.word].start, words[v.word].end),
                    index.isolatedWordForms[v.word],
                )) {
                    if (full.isNotEmpty() && "وفبكل".contains(full[0]) && formed(heardRaw) != formed(full)) {
                        tails.add(full.substring(1))
                    }
                }
                outer@ for (tail in tails) {
                    val stem = if (tail.isNotEmpty() && vowel.contains(tail[0])) tail.substring(1) else tail
                    val wantTail = formed(tail)
                    val wantStem = formed(stem)
                    if (wantStem.isEmpty()) continue
                    for (i in heardRaw.indices) {
                        val suf = formed(heardRaw.substring(i))
                        if (suf != wantTail && suf != wantStem) continue
                        val keepsVowel = suf == wantTail && tail.isNotEmpty() && vowel.contains(tail[0])
                        if (keepsVowel && !residueClear(v.obsFrom + i)) {
                            abort = true
                            break@outer
                        }
                        result = v.obsFrom + i
                        break@outer
                    }
                }
            }
            if (abort) null else result
        }
        for (rightWord in visits.map { it.word }.toSet()) {
            if (rightWord < first || words[rightWord].end == words[rightWord].start ||
                ayahFirst[ayahOf[rightWord]] == rightWord
            ) continue
            val prev = prevWord(rightWord)
            if (prev < 0 || ayahOf[prev] != ayahOf[rightWord] || !waqfMarked(prev)) continue
            val lefts = visits.filter { it.word == prev && (complete(it) || pausalVisit(it)) }
            val rights = visits.filter { it.word == rightWord && (complete(it) || procliticCut(it) != null) }
            var best: Pair<Visit, Visit>? = null
            for (rightV in rights) for (left in lefts) {
                if (left.obsTo > rightV.obsFrom) continue
                if (left.stepTo != null && rightV.stepFrom != null && rightV.stepFrom!! < left.stepTo!!) continue
                if (best == null || rightV.obsFrom < best!!.second.obsFrom ||
                    (rightV.obsFrom == best!!.second.obsFrom && left.obsTo > best!!.first.obsTo)
                ) best = Pair(left, rightV)
            }
            if (best == null) continue
            // A waqf-tail phone can sit just outside the pausal visit. The silence is after that phone.
            val cut = if (complete(best!!.second)) best!!.second.obsFrom else procliticCut(best!!.second)!!
            var pauseAt = best!!.first.obsTo - 1
            while (pauseAt + 1 < cut && waqfEdge.contains(obs[pauseAt + 1].p)) pauseAt++
            if (cut <= best!!.first.obsTo || !runEndPause(pauseAt)) continue
            val raw = obsSlice(best!!.first.obsTo, cut)
                .filter { !it.junk }.joinToString("") { it.p.toString() }
            val said = stripEdge(raw)
            if (said.isEmpty() || phraseSpan(said)) continue
            if (lexical.surplusWord(rightWord, said) == null) continue
            val existing = issues[rightWord]
            if (existing != null && existing.cls != "added_word") continue
            issues[rightWord] = Issue(
                cls = "added_word", word = rightWord, last = rightWord,
                obsFrom = best!!.first.obsTo, obsTo = cut, observed = said, lexicon = true,
            )
        }
        // A spelled span can be lent to either neighbour by the greedy alignment.
        // Use visits only to bound the seam, then certify presence on the posteriors
        // left after excision. No complete visit or initial ownership is required.
        // Without certificates or a discovery callback, this path cannot emit.
        val seamFn = options.seamInsertion
        val inspectSeams = seamFn != null || insertionEvidence.isNotEmpty()
        val seamPauses = ArrayList<Int>()
        val seamJunk = ArrayList<Int>()
        seamPauses.add(0)
        seamJunk.add(0)
        val seamUtterances = ArrayList<Pair<Int, Int>>()
        var utteranceFrom = -1
        for ((key, value) in seamExcisionCache.toList()) {
            if (value.until < obs[0].start) seamExcisionCache.remove(key)
        }
        if (inspectSeams && frames != null && frames!!.startFrame >= 0) {
            for (i in obs.indices) {
                seamPauses.add(seamPauses[i] + if (runEndPause(i)) 1 else 0)
                seamJunk.add(seamJunk[i] + if (obs[i].junk) 1 else 0)
                if (seamPauses[i + 1] > seamPauses[i]) {
                    if (utteranceFrom >= 0) seamUtterances.add(Pair(utteranceFrom, i + 1))
                    utteranceFrom = i + 1
                }
            }
        }
        seamUtterances.sortWith(compareByDescending<Pair<Int, Int>> { it.second - it.first }.thenBy { it.first })
        if (inspectSeams && frames != null && frames!!.startFrame >= 0) {
            for (w1 in visits.map { it.word }.toSet()) {
                if (w1 < first || length(w1) == 0 || decided.contains(w1) || issues[w1]?.lexicon == true) continue
                val prev = prevWord(w1)
                val w2 = nextWord(w1)
                if (prev < 0 || w2 >= words.size || words[prev].surah != words[w1].surah) continue
                val sameAyah = ayahOf[prev] == ayahOf[w1]
                if (if (sameAyah) !waqfMarked(prev) else ayahFirst[ayahOf[w1]] != w1) continue
                val lefts = visits.filter { it.word == prev && it.obsFrom >= 0 && it.obsTo > it.obsFrom }
                val ends = visits.filter { it.word == w2 && it.obsFrom >= 0 && it.obsTo > it.obsFrom }
                var found: Issue? = null
                for (left in lefts) for (end in ends) {
                    if (found != null || left.obsTo > end.obsFrom) continue
                    val from = left.obsFrom
                    val to = end.obsTo
                    val f0 = obs[from].start
                    val f1 = obs[to - 1].end + 1
                    if (f0 < frames!!.startFrame || f1 > frames!!.endFrame) continue
                    if (sameAyah && seamPauses[end.obsFrom] == seamPauses[from]) continue
                    if (!seamUtterances.any { it.first >= from && it.second <= end.obsFrom }) continue
                    val key = seamCacheKey(w1, f0, f1, obsSlice(from, end.obsFrom))
                    val cached = seamExcisionCache[key]
                    if (cached != null) {
                        if (cached.span != null) {
                            found = Issue(
                                cls = "added_word", word = w1, last = w1,
                                obsFrom = from + cached.span.from, obsTo = from + cached.span.to,
                                observed = cached.span.said, lexicon = true,
                            )
                        }
                        continue
                    }
                    val forms = listOf(prev, w1, w2).map { wi ->
                        listOf(
                            ref.substring(words[wi].start, words[wi].end),
                            index.isolatedWordForms[wi],
                        ).filter { it.isNotEmpty() }.distinct()
                    }
                    // Longer spans first: a whole word takes precedence over its lexical tail.
                    for ((a, b) in seamUtterances) {
                        if (found != null) break
                        if (a < from || b > end.obsFrom) continue
                        if (seamJunk[b] != seamJunk[a]) continue
                        // Excision must take an entire pause-bounded utterance, not a fragment
                        // of a word or a held run that the remaining frames can still explain.
                        val said = stripEdge(surplusNasal(heardString(a, b)))
                        if (said.isEmpty() || lexical.surplusWord(w1, said) == null || phraseSpan(said)) continue
                        if (lexical.fragmentOfSeam(w1, said)) continue
                        val margins = excisedNeighbourMargins(
                            frames!!, { p -> tokenId[p] ?: 0 }, forms, f0, f1,
                            Pair(obs[a].start, obs[b - 1].end + 1),
                        )
                        if (margins == null || margins.any { it < PHRASE_SWITCH }) continue
                        val preceding = prevWord(prev)
                        val prefix = visits.filter { v ->
                            v.word == preceding && ayahOf[preceding] == ayahOf[prev] &&
                                v.obsFrom >= 0 && v.obsTo <= left.obsFrom
                        }.sortedByDescending { it.obsTo }.firstOrNull()
                        val candidate = SeamInsertionCandidate(
                            word = w1, said = said,
                            fromFrame = obs[a].start, toFrame = obs[b - 1].end + 1,
                            seamFromFrame = f0, seamToFrame = f1,
                            prefixFrame = prefix?.let { obs[it.obsFrom].start },
                        )
                        val permitted = if (seamFn != null) seamFn(candidate)
                        else (insertionEvidence[
                            insertionKey(words[w1].ref(), said, candidate.fromFrame, candidate.toFrame),
                        ] ?: 0.0) > 0.5
                        if (!permitted) continue
                        found = Issue(
                            cls = "added_word", word = w1, last = w1,
                            obsFrom = a, obsTo = b, observed = said, lexicon = true,
                        )
                        break
                    }
                    seamExcisionCache[key] = SeamCacheEntry(
                        until = f1,
                        span = found?.let { SeamSpan(it.obsFrom - from, it.obsTo - from, it.observed!!) },
                    )
                }
                if (found != null) issues[w1] = found!!
            }
        }
        for (e in stepEdits.sortedByDescending { it.lo }) {
            steps.subList(e.lo, e.hi).clear()
            steps.addAll(e.lo, e.path)
        }
        // The identical two-word lead plus a locked ayah end closes this choice. Score only the greedy
        // phones before its labelled pause, treating waqf tails and held sounds identically on both sides.
        // These candidates still need affirmative posterior support and the unchanged weak-evidence gate.
        if (rankOmission && trackerLocked) for (slot in visits) {
            val wi = slot.word
            val fillers = formulas[wi] ?: continue
            if (slot.obsFrom < 0 || slot.obsTo <= slot.obsFrom || slot.stepFrom == null) continue
            if (wi < first || decided.contains(wi)) continue
            if (cursor != wi && (lockedAyah[ayahOf[wi]]?.wordTo ?: -1) < wi) continue
            val left = visits.find { it.word == wi - 1 && it.obsTo <= slot.obsFrom && counts(it) && !wrong(it) }
            val leadV = left?.let { l ->
                visits.find { it.word == wi - 2 && it.obsTo <= l.obsFrom && counts(it) && !wrong(it) }
            }
            if (left == null || leadV == null) continue
            val from = slot.obsFrom
            val next = visits.find { it.obsFrom >= slot.obsTo && it.word > wi }
            val limit = next?.obsFrom ?: obs.size
            var to = slot.obsTo
            while (to < limit && !labelledPause(to - 1)) to++
            if (!labelledPause(to - 1)) continue
            if (obsSlice(from, to).any { it.junk }) continue
            val path = steps.subList(slot.stepFrom!!, steps.size)
                .filter { it.hasObs && it.obsIndex >= from && it.obsIndex < to }
            if (path.size != to - from || path.any { s ->
                    (s !is CheckerStep.Diag && s !is CheckerStep.Ins) ||
                        index.wordAt(if (s is CheckerStep.Ins) s.at else (s as CheckerStep.Diag).ref) != wi
                }
            ) continue
            val said = stripEdge(heardString(from, to))
            val phones = { s: String -> collapseHeld(stripEdge(s).toList()) }
            if (said.isEmpty()) continue
            val written = ref.substring(words[wi].start, words[wi].end)
            var best = spanLogP(phones(said), phones(written), omissionChannel)
            var alternative: String? = null
            for (filler in fillers) {
                val score = spanLogP(phones(said), phones(filler), omissionChannel)
                if (score > best) {
                    best = score
                    alternative = filler
                }
            }
            if (alternative == null) continue
            candidates.add(
                Issue(cls = "wrong_word", word = wi, last = wi, obsFrom = from, obsTo = to, observed = said),
            )
        }
        // A twin that only drops the madd before a hamza. Waqf still removes that vowel, so a labelled pause stays
        // quiet. Everywhere else the span has to be shorter than this reciter's own matched madd munfasil.
        for (m in maddNow(steps).flags) {
            if (issues.containsKey(m.word) || decided.contains(m.word)) continue
            val drop = madd.drops[m.word]!!
            val visit = visits.find {
                it.word == m.word && it.obsFrom >= 0 && it.obsFrom <= m.consObs && it.obsTo > m.consObs
            }
            issues[m.word] = Issue(
                cls = "wrong_word", word = m.word, last = m.word,
                obsFrom = visit?.obsFrom ?: m.consObs, obsTo = m.hamzaObs,
                observed = drop.alt, madd = true,
            )
            right.remove(m.word)
        }
        // Phones from the previous word's last in-order visit up to the first labelled pause.
        // A gap inside that previous word is already before `start`.
        val closingSlot = { wi: Int, fallbackFrom: Int ->
            var result: Pair<Int, Int>? = null
            if (ayahLast[ayahOf[wi]] == wi) {
                val ayah = ayahOf[wi]
                val prev = prevWord(wi)
                var anchored = -1
                for (v in visits) {
                    if (v.word != prev || v.obsFrom < 0 || v.obsTo <= anchored) continue
                    // A repeat after a later word is not the in-order visit.
                    if (visits.any { u ->
                            u.obsFrom >= 0 && u.word > prev && u.obsTo > 0 && u.obsTo <= v.obsFrom
                        }
                    ) continue
                    anchored = v.obsTo
                }
                val start = if (anchored >= 0) anchored else fallbackFrom
                if (start >= 0 && start <= obs.size) {
                    var limit = obs.size
                    for (v in visits) {
                        if (v.obsFrom < start || ayahOf[v.word] == ayah) continue
                        if (v.obsFrom < limit) limit = v.obsFrom
                    }
                    if (limit > start) {
                        // The first labelled pause after the previous word. Speech before it is the slot; a pause
                        // earlier than that word is already outside `start`. Unowned phones after the pause (the
                        // next ayah's onset, not yet a visit) stay out.
                        var to = -1
                        for (i in start until limit) {
                            if (PAUSE_TOKEN.contains(obs[i].p)) {
                                to = i
                                break
                            }
                            if (i + 1 < obs.size && runEndPause(i)) {
                                to = i + 1
                                break
                            }
                        }
                        if (to > start) result = Pair(start, to)
                    }
                }
            }
            result
        }
        val slotText = { from: Int, to: Int ->
            obsSlice(from, to).filter { !it.junk && !PAUSE_TOKEN.contains(it.p) }.map { it.p }
        }
        val mannerContradicts = { wi: Int, phones: List<Char> ->
            val heardM = lastManner(phones.joinToString(""))
            val writtenM = pausalManner(wi)
            heardM != null && writtenM != null && heardM != writtenM
        }
        for ((wi, issue) in issues.toList()) {
            if (issue.phrase != null || issue.word != wi) continue
            if (issue.cls != "wrong_word" && issue.cls != "omitted_word") continue
            val slot = closingSlot(wi, issue.obsFrom) ?: continue
            val phones = slotText(slot.first, slot.second)
            if (!mannerContradicts(wi, phones)) continue
            if (issue.cls == "wrong_word" && issue.last == wi) {
                issue.manner = true
                continue
            }
            // An omission already raised on this word. Speech in the slot (two non-blank tokens, one a
            // consonant) that contradicts the pausal final becomes that same accusation as a wrong word.
            if (issue.cls != "omitted_word" || issue.word != wi) continue
            val consonant = phones.any { CONSONANTS.contains(it) || MANNER[it] == "nasal" }
            if (phones.size < 2 || !consonant) continue
            issues[wi] = Issue(
                cls = "wrong_word", word = wi, last = wi, obsFrom = slot.first, obsTo = slot.second,
                observed = phones.joinToString(""), stringPath = true, manner = true,
            )
        }
        return JudgeResult(
            issues, candidates, head, reached, steps, right, last.keys.toSet(),
            visits.filter { counts(it) },
        )
    }

    private fun seamCacheKey(w1: Int, f0: Int, f1: Int, phones: List<Heard>): String {
        val sb = StringBuilder()
        sb.append("[$w1,$f0,$f1,[")
        phones.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("[${jsonString(p.p.toString())},${p.start},${p.end},${p.junk}]")
        }
        sb.append("]]")
        return sb.toString()
    }

    /** Matched phonemes on the word after an error, heard after it: the next word has begun. */
    private fun onset(steps: List<CheckerStep>, issue: Issue): Boolean {
        // A parallel phrase is only raised once its shared exit word has matched, which is already past the
        // interior word. The immediate next word can be the suffix the two phrases disagree on, so exact
        // phoneme hits there are the wrong test.
        if (issue.phrase != null) return true
        val next = nextWord(issue.last)
        // After the Quran's last word there is no next word to begin: decide at the passage end instead.
        if (next >= words.size) return false
        var n = 0
        for (s in steps) {
            if (s is CheckerStep.Diag && s.obs >= issue.obsTo && index.wordAt(s.ref) == next &&
                ref[s.ref] == obs[s.obs].p
            ) n++
        }
        return n >= minOf(ONSET, words[next].end - words[next].start)
    }

    /**
     * Parallel phrases the alignment has entered and left: the written phonemes against each attested substitute,
     * over the frames from the shared entry word to the shared exit word. Suffix frames sit in that one span, so
     * they cannot be lent to a neighbour and counted again. The best path that clears `PHRASE_SWITCH` is a wrong word.
     */
    private fun parallelCandidates(aligned: List<CheckerStep>): List<Issue> {
        val f = frames ?: return emptyList()
        if (f.startFrame < 0 || parallels.isEmpty()) return emptyList()
        var loW = Int.MAX_VALUE
        var hiW = -1
        for (s in aligned) if (s is CheckerStep.Diag) {
            val wi = index.wordAt(s.ref)
            if (wi < loW) loW = wi
            if (wi > hiW) hiW = wi
        }
        if (hiW < loW) return emptyList()
        val ids = { s: String -> s.map { tokenId[it] ?: 0 }.toIntArray() }
        val covered = { start: Int, length: Int ->
            val w0 = words[start].start
            val w1 = words[start + length - 1].end
            val entry = start
            val exit = start + length - 1
            var from = -1
            var to = -1
            var entryDiag = false
            var exitDiag = false
            var run = false
            var stopped = false
            val reset = {
                from = -1
                to = -1
                entryDiag = false
                exitDiag = false
                run = false
            }
            for (s in aligned) {
                if (stopped) break
                when (s) {
                    is CheckerStep.Jump, is CheckerStep.Lead -> {
                        if (exitDiag) stopped = true
                        else reset()
                    }
                    else -> {
                        val refAt = s.refIndex
                        if (refAt < w0 || refAt >= w1) {
                            if (exitDiag) stopped = true
                            else if (run) reset()
                        } else {
                            run = true
                            val wi = index.wordAt(minOf(refAt, w1 - 1))
                            if (s is CheckerStep.Diag && wi == entry) entryDiag = true
                            if (s is CheckerStep.Diag && wi == exit) exitDiag = true
                            if (s.hasObs && entryDiag) {
                                if (from < 0) from = s.obsIndex
                                to = s.obsIndex + 1
                            }
                        }
                    }
                }
            }
            if (entryDiag && exitDiag && to > from) Pair(from, to) else null
        }
        /** Mean decoder confidence over the heard phones that miss the written phrase and match the parallel. */
        val switchedClarity = { path: ParallelPath, span: Pair<Int, Int> ->
            val heardList = obsSlice(span.first, span.second)
            val said = heardList.joinToString("") { it.p.toString() }
            val off = align(path.written, said).flatMap { o ->
                when (o) {
                    is AlignmentOp.Sub -> listOf(o.obsAt)
                    is AlignmentOp.Ins -> listOf(o.obsAt)
                    else -> emptyList()
                }
            }.toSet()
            val on = align(path.alt, said).flatMap { o ->
                if (o is AlignmentOp.Match && off.contains(o.obsAt)) listOf(heardList[o.obsAt].conf)
                else emptyList()
            }
            if (on.isNotEmpty()) on.sum() / on.size else 1.0
        }
        val out = ArrayList<Issue>()
        val taken = LinkedHashSet<Int>()
        for (w in loW..hiW) {
            val paths = parallels[w] ?: continue
            if (taken.contains(w) || decided.contains(w)) continue
            var best: ParallelBest? = null
            for (path in paths) {
                val span = covered(path.start, 4) ?: continue
                val f0 = obs[span.first].start
                val f1 = obs[span.second - 1].end + 1
                if (f1 - f0 < 4 || f0 < f.startFrame || f1 > f.endFrame) continue
                val altNll = f.nll(ids(path.alt), f0, f1)
                if (!altNll.isFinite()) continue
                // Blank-owned frames say nothing about which phrase was said. A flat phone frame (the whole inventory
                // sharing the mass) is not a heard word either.
                var speech = 0.0
                var heardCount = 0
                val blankOwns = Math.log(0.5)
                for (fr in f0 until f1) {
                    val row = f.row(fr)
                    if (row[0] >= blankOwns) continue
                    var top = row[1]
                    for (i in 2 until row.size) if (row[i] > top) top = row[i]
                    speech += Math.exp(top)
                    heardCount++
                }
                if (heardCount < 4 || speech / heardCount < PHRASE_PEAK) continue
                val margin = f.nll(ids(path.written), f0, f1) - altNll
                // The attested-parallel discount is for a substitute heard surely. Where the phones that leave the written
                // phrase for this one were decoded below `UNSURE` (ل heard as ن on a phone band), it is the confusable
                // sound `supportNeed` doubles for, and it takes that same bar.
                val need = if (switchedClarity(path, span) < UNSURE) 2 * support else PHRASE_SWITCH
                if (!(margin >= need) || (best != null && margin <= best!!.margin)) continue
                best = ParallelBest(path, span, margin, need)
            }
            if (best == null) continue
            taken.add(w)
            out.add(
                Issue(
                    cls = "wrong_word", word = w, last = w,
                    obsFrom = best!!.span.first, obsTo = best!!.span.second, observed = best!!.path.altWord,
                    supportRange = Pair(best!!.span.first, best!!.span.second),
                    phrase = PhraseIssue(best!!.path.written, best!!.path.alt, best!!.need),
                ),
            )
        }
        return out
    }

    private fun evaluate(out: MutableList<CheckerEvent>, final: Boolean) {
        // The session ended, or the cursor left the span, before a unique continuation arrived.
        // Frozen slot accusations were judged on the committed window; this copy is the one they name.
        if (watching.isNotEmpty() && (final || (cursor >= 0 && !spanStillOpen(cursor)))) {
            resolveWatching()
            releaseFrozen(out, false)
        }
        if (anchor == null || obs.isEmpty()) return
        setWindow()
        steps = align()
        val judged = judge(steps)
        val beforeGap = pendingGap
        noteLock()
        val issues = judged?.issues ?: LinkedHashMap()
        if (beforeGap != null &&
            (pendingGap == null || beforeGap.first != pendingGap!!.first || beforeGap.second != pendingGap!!.second)
        ) {
            closeGap(out, issues, beforeGap.first, beforeGap.second, true)
        }
        if (pendingGap != null) closeGap(out, issues, pendingGap!!.first, pendingGap!!.second, final)
        keepLedger(issues)
        // One unread run, two emitters. The seam starts after the ledger ayah and the words between them are bound
        // refrain or shared duplicate-span text (or there are none). The ledger key is the earlier one, so the seam
        // is dropped. A unique word between them keeps both. The span is the one the fit already accepted, including
        // a skip confirmed before this window's anchor.
        val bound = judged?.bound ?: emptySet()
        if (ledgerSpans.isNotEmpty()) for ((wi, issue) in issues.toList()) {
            if (issue.cls != "skipped_passage" || ledgerSpans.containsKey(wi)) continue
            for ((_, last) in ledgerSpans) {
                if (issue.word <= last) continue
                var same = true
                for (w in (last + 1) until issue.word) {
                    if (words[w].end == words[w].start) continue
                    if (!bound.contains(w) || !copyText.contains(w)) {
                        same = false
                        break
                    }
                }
                if (same) {
                    issues.remove(wi)
                    break
                }
            }
        }
        if (judged == null && issues.isEmpty()) return
        if (judged != null) steps = judged.steps
        val head = judged?.head ?: cursor
        val reached = judged?.furthest ?: cursor
        val right = judged?.right ?: LinkedHashSet()
        // Use the final (possibly repaired) path and identical frames/context for both acoustic hypotheses. Unlike the
        // legacy path, a lexical candidate needs affirmative support; absent or indeterminate posteriors mean abstain.
        if (judged != null) for (candidate in judged.lexical) if (supported(candidate, true) == true) {
            issues[candidate.word] = candidate
            right.remove(candidate.word)
        }
        for (candidate in parallelCandidates(steps)) {
            val existing = issues.values.find { it.word <= candidate.word && candidate.word <= it.last }
            if (existing != null &&
                !(existing.cls == "omitted_word" && existing.word == candidate.word && existing.last == candidate.word)
            ) continue
            if (supported(candidate, true) != true) continue
            issues[candidate.word] = candidate
            right.remove(candidate.word)
        }
        // Only the support-path addition is removed. Keep the wrong word's decision evidence and report the
        // combined heard span, so absorbing a tail cannot promote an uncertain wrong word into a warning.
        if (judged != null) for ((wi, added) in issues.toList()) {
            if (added.cls != "added_word" || added.lexicon || decided.contains(wi)) continue
            val wrong = issues[prevWord(wi)]
            if (wrong == null || wrong.cls != "wrong_word") continue
            val location = toRef(wrong.word)
            val confirmed = if (decided.contains(wrong.word)) {
                emitted.find { e ->
                    e.state == "confirmed" && e.errorClass == "wrong_word" &&
                        e.location.surah == location.surah && e.location.ayah == location.ayah &&
                        e.location.word == location.word
                }
            } else null
            // An uncertain decision is not an accusation that can own another word's speech.
            if (decided.contains(wrong.word) && confirmed == null) continue
            val union = swapTailUnion(
                SwapAccusation(wrong.obsFrom, wrong.obsTo, wrong.cls, wrong.word, wrong.last, wrong.lexicon),
                SwapAccusation(added.obsFrom, added.obsTo, added.cls, added.word, added.last, added.lexicon),
                object : SwapTailContext {
                    override fun nextWord(wi: Int): Int = this@CheckerImpl.nextWord(wi)
                    override val visits: List<ObsSpan> = judged.visits.map { ObsSpan(it.obsFrom, it.obsTo) }
                    override fun labelledPause(prev: Int): Boolean = this@CheckerImpl.labelledPause(prev)
                    override fun heard(span: ObsSpan): String =
                        heardSpan(wrong.copy(obsFrom = span.obsFrom, obsTo = span.obsTo))
                    override fun spellsWord(wi: Int, heard: String): String? =
                        lexical.surplusWord(wi, heard)
                },
            )
            if (union == null) continue
            issues[wrong.word] = wrong.copy(
                heardUnion = HeardUnion(union.span.obsFrom, union.span.obsTo, union.observed),
            )
            issues.remove(wi)
            if (confirmed != null) confirmed.observed = union.observed
        }
        // An addition after the Quran's last word (past the end of An-Nas) has no following word to sit before, so it
        // cannot be located or reported; without this its index is out of range and the session dies.
        for (wi in issues.keys.toList()) if (wi >= words.size) issues.remove(wi)
        furthest = maxOf(furthest, reached)
        for ((wi, o) in open.toList()) {
            val now = issues[wi]
            if (now == null) {
                // Commit moved the anchor past a held slot. The accusation was made on the locked-step window;
                // keep it until the continuation, instead of dropping it because this window no longer covers it.
                if (heldSlot(wi) && wi < anchor!!) {
                    freezeSlot(wi, o)
                    continue
                }
                open.remove(wi)
                out.add(event(o, "resolved"))
                ledgerSpans.remove(wi)
            } else if (now.cls != o.issue.cls) {
                o.issue = now
                out.add(event(o, "suspected"))
            } else o.issue = now
        }
        for ((wi, issue) in issues) {
            if (open.containsKey(wi) || decided.contains(wi)) continue
            val o = Open(
                id = "${words[wi].surah}:${words[wi].ayah}:${words[wi].word}#${++seq}",
                issue = issue, sinceMs = nowMs,
            )
            open[wi] = o
            out.add(event(o, "suspected"))
        }
        for (o in open.values.toList()) {
            val issue = o.issue
            val omission = issue.cls == "omitted_word" || issue.cls == "skipped_passage"
            // The swallowed next word never advances `head`. The joint fit already heard it; wait until a later word or
            // the passage end so earlier audio can still resolve the suspicion.
            val followed = issue.pairOnset || onset(steps, issue)
            val past = head != issue.word || (issue.pairOnset && final)
            val due = if (omission) {
                final || ayahOf[reached] >= ayahOf[issue.last] + 2
            } else {
                past && followed && (
                    confirm is ConfirmPolicy.NextWord ||
                        (confirm is ConfirmPolicy.Delay && nowMs - o.sinceMs >= confirm.ms) ||
                        (final && declared && (!latched || (policy.finaliseFree ?: true)) &&
                            right.any { it > issue.last })
                    )
            }
            // A slot the two copies disagree on stays open until the continuation picks a copy.
            if (heldSlot(issue.word)) continue
            if (due) decide(o, out)
            // The session ended first. A declared passage (or a latched free one, with `finaliseFree`) confirms once a later
            // word was recited right; otherwise it ends uncertain.
            else if (final) decide(o, out, "uncertain")
        }
        val p = passage
        if (latched && p != null) {
            p.from = maxOf(p.from, ayahFirst[maxOf(0, ayahOf[anchor!!] - 1)])
            p.to = maxOf(p.to, furthest, anchor!!)
        }
        // Same commit a locked step uses, including while a span is held. Skipping it left `lead` set and the
        // whole opening in one alignment, so the word-1 gap the spelled-surplus rule reads was no longer that gap.
        if (!final && obs.size > MAX_WINDOW) commit(steps, out)
    }

    /** Freeze the window's older part at a word boundary, deciding errors left behind it. */
    private fun commit(steps: List<CheckerStep>, out: MutableList<CheckerEvent>) {
        val limit = obs.size - KEEP
        var cut = -1
        var at = -1
        for (k in steps.indices) {
            val s = steps[k]
            if (s !is CheckerStep.Diag || s.obs >= limit) continue
            val next = steps.getOrNull(k + 1)
            if (wordStarts.contains(s.ref + 1) && next is CheckerStep.Diag && next.ref == s.ref + 1) {
                cut = s.obs + 1
                at = s.ref + 1
            }
        }
        if (cut < 0) return
        val floor = obs[cut].start
        for (b in maddNow(steps).baseline) {
            if (b.frame < floor && pastMadd.none { it.frame == b.frame }) pastMadd.add(b)
        }
        val word = index.wordAt(at)
        for (o in open.values.toList()) {
            if (o.issue.word < word && heldSlot(o.issue.word)) freezeSlot(o.issue.word, o)
        }
        for (o in open.values.toList()) {
            if (o.issue.word < word && !heldSlot(o.issue.word)) decide(o, out)
        }
        for (o in open.values) {
            val range = o.issue.supportRange
            val union = o.issue.heardUnion
            o.issue = o.issue.copy(
                obsFrom = maxOf(0, o.issue.obsFrom - cut),
                obsTo = maxOf(0, o.issue.obsTo - cut),
                heardUnion = union?.copy(
                    obsFrom = maxOf(0, union.obsFrom - cut),
                    obsTo = maxOf(0, union.obsTo - cut),
                ),
                supportRange = range?.let { Pair(maxOf(0, it.first - cut), maxOf(0, it.second - cut)) },
            )
        }
        obs = ArrayList(obs.subList(cut, obs.size))
        anchor = word
        lead = 0
        fromLock = false
    }

    private fun track(events: List<EngineEvent>, out: MutableList<CheckerEvent>): Int? {
        var next: Int? = null
        for (e in events) {
            if (e !is PositionEvent) continue
            val p = e
            // The continuation picked a copy. Held slots are judged against that member, not sealed here.
            if (p.relocated != null) {
                trackerLocked = true
                val from = wordIndex(p.relocated.first)
                val to = wordIndex(p.relocated.second)
                resolveWatching()
                if (from >= 0 && to >= 0 && from != to) rebase(from, to, out)
                else releaseFrozen(out, false)
                continue
            }
            if (p.state == "locked" && p.observed.isNotEmpty()) {
                cursor = wordIndex(p.observed[0].word)
                // Only the first lock, before it has heard a location-unique word, holds differing slots.
                if (p.spanHold) {
                    noteSpan(cursor)
                    noteSpan(wordIndex(p.observed[0].span.start))
                }
            }
            // A misfit hold keeps this lock and keeps delivering tokens. An open issue stays only when its edit
            // explains the drop; the rest end uncertain. Slot accusations fall back to this copy instead of being sealed.
            if (p.hold == "start") {
                val skip = slotWords()
                resolveWatching()
                releaseFrozen(out, false)
                sealOpen(out, skip)
            }
            if (p.state != "locked") {
                trackerLocked = false
                // Lost, it latches again on the next lock.
                if (latched && passage != null) {
                    passage = null
                    declared = false
                }
                if (declared) continue
                if (anchor != null) dropAnchor(out)
                next = null
                continue
            }
            trackerLocked = true
            val first = p.observed.firstOrNull() ?: continue
            val start = wordIndex(first.span.start)
            val end = wordIndex(first.word)
            if (latched && passage == null) {
                passage = PassageRange(start, end)
                declared = true
                next = start
                continue
            }
            if (declared) {
                if (end - start + 1 < JUMP_WORDS || !offPassage(start) || !offPassage(end) ||
                    fitsPassage(start, end)
                ) continue
                if (latched) passage = PassageRange(start, end)
                else declared = false
                if (anchor != null) dropAnchor(out)
                next = start
                continue
            }
            if (passage != null && !offPassage(end)) {
                // Back on the declared passage after a jump.
                declared = true
                if (anchor != null) dropAnchor(out)
                next = start
                continue
            }
            if (anchor == null) {
                if (next == null) next = start
            } else if (words[end].start < lo || words[end].start >= hi) {
                // A jump the window can't follow: start over where the tracker went.
                dropAnchor(out)
                next = start
            }
        }
        return next
    }

    /** Start aligning: from the declared passage, or where the tracker locked. */
    private fun anchorOn(next: Int?, events: List<EngineEvent>) {
        if (anchor == null && next == null && declared && obs.isNotEmpty()) {
            anchor = passage!!.from
            lead = obs.size
            fromLock = false
            furthest = -1
        } else if (anchor == null && next != null) {
            anchor = next
            val endWord = events.filterIsInstance<PositionEvent>().lastOrNull()
                ?.observed?.firstOrNull()?.word
            val span = if (endWord != null) words[wordIndex(endWord)].end - words[next].start else 0
            lead = maxOf(0, obs.size - span - 4)
            fromLock = true
            furthest = -1
        }
    }

    override fun push(evidence: PhonemeEvidence, events: List<EngineEvent>): List<CheckerEvent> {
        for (p in evidence.phonemes) for (m in p.seamInsertions ?: emptyList()) {
            if (m.marginal.isFinite() && m.marginal >= 0 && m.marginal <= 1 && m.toFrame > m.fromFrame) {
                val key = insertionKey(m.word, m.observed, m.fromFrame, m.toFrame)
                insertionEvidence[key] = maxOf(insertionEvidence[key] ?: 0.0, m.marginal)
            }
        }
        val out = ArrayList<CheckerEvent>()
        frameMs = evidence.frameMs
        val f = frames
        if (f != null && evidence.posteriors != null) f.append(evidence.posteriors)
        val next = track(events, out)
        val before = obs.size
        obs.addAll(evidence.phonemes.map { Heard(it.p, it.start, it.end, it.conf) })
        chunkFrom = before
        markPhrases(before)
        val lastPh = obs.lastOrNull()
        if (lastPh != null) nowMs = maxOf(nowMs, (lastPh.end + 1) * frameMs.toDouble())
        anchorOn(next, events)
        if (anchor == null) {
            if (obs.size > HISTORY) obs = ArrayList(obs.takeLast(HISTORY))
        } else evaluate(out, false)
        if (f != null && obs.isNotEmpty()) {
            val floor = ledgerFloor()
            f.dropBefore(if (floor == null) obs[0].start else minOf(obs[0].start, floor))
        }
        return out
    }

    override fun end(events: List<EngineEvent>): List<CheckerEvent> {
        val out = ArrayList<CheckerEvent>()
        anchorOn(track(events, out), events)
        evaluate(out, true)
        return out
    }

    override fun mistakes(): List<CheckerEvent> =
        emitted.filter { it.state == "confirmed" || (policy.countResolved == true && it.state == "resolved") }
}

/** A JSON string literal, as `JSON.stringify` renders it (only escaping that can occur in this data). */
private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                else sb.append(c)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
