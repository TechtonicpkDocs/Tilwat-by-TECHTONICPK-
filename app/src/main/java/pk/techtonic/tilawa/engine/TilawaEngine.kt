// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/**
 * The public facade the UI uses. It owns one [TilawaSession] at a time and forwards its events to a
 * [TilawaListener].
 *
 * The 0-based boundary: the engine (and the upstream goldens) number words 1-based inside [WordRef], but
 * the UI indexes the word list of an ayah directly, so every [WordRef] crossing this facade is converted
 * to 0-based — [PositionEvent.expected], every observed candidate's `word` and `span`, the `relocated`
 * pair, trace shortlist spans, [CheckerEvent.location], and the word number inside a checker event's id
 * (`s:a:w#seq`), so the id and the location agree. [Mistake.wordIndex] is 0-based for the same reason.
 * Capabilities pass through unchanged (they carry no word).
 *
 * `pushAudio` with no active session is a silent no-op returning no events: the mic callback can outlive
 * the session by one chunk after `end()`, and that race must not surface as a fatal error. A
 * [SessionEndedError] from a session that ended mid-push is swallowed the same way; any other failure
 * (for example an inference error) still propagates.
 *
 * `load()` replaces the engine core and drops the current session. `end()` is idempotent and keeps the
 * session referenced, so `mistakes()` still works after it.
 */
interface TilawaListener {
    fun onCapabilities(event: CapabilityEvent)
    fun onPosition(event: PositionEvent)
    fun onChecker(event: CheckerEvent)
}

class TilawaEngine(
    private val runner: OrtRunner,
    private val manifest: ModelManifest = LITE_MANIFEST,
) {
    @Volatile
    private var listener: TilawaListener? = null

    @Volatile
    private var core: TilawaEngineCore? = null

    @Volatile
    private var session: TilawaSession? = null

    private var unsubscribe: (() -> Unit)? = null

    /**
     * Sets the listener. If a session is already running, its capabilities are delivered at once, as a
     * fresh subscription would.
     */
    fun setListener(listener: TilawaListener?) {
        this.listener = listener
        if (listener != null) {
            session?.let { listener.onCapabilities(it.capabilities) }
        }
    }

    /** Loads a reference index (a [QuranReference] in the app) and builds the engine core around it. */
    fun load(reference: ReferenceIndex) {
        // The phrase list lives on the index; the asset version is provenance-only (no engine logic reads
        // it) and "phrases-v1" is the version of the phrases-v1.json the app ships.
        val phraseAsset = reference.phraseList.takeIf { it.isNotEmpty() }
            ?.let { PhraseAsset("phrases-v1", it) }
        core = TilawaEngineCore(reference, phraseAsset, runner, manifest)
        unsubscribe?.invoke()
        unsubscribe = null
        session = null
    }

    fun startFreeRecitation(policy: SessionPolicy = SessionPolicy()) {
        val c = core ?: throw IllegalStateException("load() a reference before starting a session")
        startSession(c.startFreeRecitation(policy))
    }

    fun startMemorization(
        surah: Int,
        fromAyah: Int,
        toAyah: Int,
        policy: SessionPolicy = SessionPolicy(),
    ) {
        val c = core ?: throw IllegalStateException("load() a reference before starting a session")
        startSession(c.startMemorization(MemorizationPassage(surah, fromAyah, toAyah), policy))
    }

    private fun startSession(s: TilawaSession) {
        unsubscribe?.invoke()
        session = s
        unsubscribe = s.subscribe { e -> dispatch(e) }
    }

    private fun dispatch(e: EngineEvent) {
        val l = listener ?: return
        when (val converted = convert(e)) {
            is CapabilityEvent -> l.onCapabilities(converted)
            is PositionEvent -> l.onPosition(converted)
            is CheckerEvent -> l.onChecker(converted)
        }
    }

    fun pushAudio(samples: FloatArray, sampleRate: Int): List<EngineEvent> {
        val s = session
        if (s == null || s.state != "active") return emptyList()
        return try {
            s.pushAudio(samples, sampleRate).map { convert(it) }
        } catch (_: SessionEndedError) {
            emptyList()
        }
    }

    fun end(): List<EngineEvent> {
        val s = session ?: return emptyList()
        return s.end().map { convert(it) }
    }

    /** Confirmed mistakes (plus resolved ones when the session policy counts them), 0-based. */
    fun mistakes(): List<Mistake> {
        val s = session ?: return emptyList()
        return s.mistakes().map { e ->
            Mistake(
                surah = e.location.surah,
                ayah = e.location.ayah,
                wordIndex = e.location.word - 1,
                errorClass = e.errorClass,
                expected = e.expected ?: "",
                observed = e.observed ?: "",
            )
        }
    }

    private fun convert(e: EngineEvent): EngineEvent = when (e) {
        is CapabilityEvent -> e
        is PositionEvent -> convert(e)
        is CheckerEvent -> convert(e)
    }

    private fun convert(e: PositionEvent): PositionEvent = e.copy(
        expected = e.expected?.let { zeroBased(it) },
        observed = e.observed.map { c -> c.copy(word = zeroBased(c.word), span = zeroBased(c.span)) },
        relocated = e.relocated?.let { Pair(zeroBased(it.first), zeroBased(it.second)) },
        trace = e.trace?.let { t ->
            t.copy(shortlist = t.shortlist.map { sc -> sc.copy(span = zeroBased(sc.span)) })
        },
    )

    private fun convert(e: CheckerEvent): CheckerEvent = e.copy(
        id = zeroBasedId(e.id),
        location = zeroBased(e.location),
    )

    private fun zeroBased(w: WordRef): WordRef = w.copy(word = w.word - 1)

    private fun zeroBased(span: WordSpan): WordSpan =
        span.copy(start = zeroBased(span.start), end = zeroBased(span.end))

    /** Rewrites the word number in an id of the form `s:a:w#seq` to 0-based; anything else passes through. */
    private fun zeroBasedId(id: String): String {
        val hash = id.indexOf('#')
        val head = if (hash >= 0) id.substring(0, hash) else id
        val tail = if (hash >= 0) id.substring(hash) else ""
        val parts = head.split(':')
        if (parts.size != 3) return id
        val w = parts[2].toIntOrNull() ?: return id
        return "${parts[0]}:${parts[1]}:${w - 1}$tail"
    }
}
