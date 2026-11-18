package pk.techtonic.tilawa.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import pk.techtonic.tilawa.R
import pk.techtonic.tilawa.data.MicRecorder
import pk.techtonic.tilawa.data.MistakeRecord
import pk.techtonic.tilawa.data.OnnxSession
import pk.techtonic.tilawa.data.SessionHistoryDb
import pk.techtonic.tilawa.databinding.ActivitySessionBinding
import pk.techtonic.tilawa.engine.CapabilityEvent
import pk.techtonic.tilawa.engine.CheckerEvent
import pk.techtonic.tilawa.engine.Mistake
import pk.techtonic.tilawa.engine.PositionEvent
import pk.techtonic.tilawa.engine.QuranReference
import pk.techtonic.tilawa.engine.TilawaEngine
import pk.techtonic.tilawa.engine.TilawaListener
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The recitation screen. Shows the declared passage in Uthmani text (Amiri),
 * highlights the current word as position events arrive, flags confirmed
 * mistakes as they happen, and saves the session to history on end.
 *
 * The engine runs on a background thread; every UI update goes through
 * runOnUiThread. The screen stays on during a session.
 *
 * Assumptions against the engine contract (for the coordinator to confirm):
 * QuranReference takes a Context and loads ref-v2.json from assets;
 * MicRecorder has a no-arg constructor; WordRef.word is 0-based (it indexes
 * the word list in ref-v2.json); Mistake's shape follows the contract
 * (surah, ayah, wordIndex, errorClass, expected, observed) — it had not
 * landed when this file was written, so its mapping lives in one function
 * (List<Mistake>.toItems) for a one-spot fix if it lands differently.
 * Event shapes follow the LANDED engine/Events.kt: WordRef(surah, ayah,
 * word) with no text field, PositionEvent.observed is a ranked list of
 * PositionCandidate, CheckerEvent carries location: WordRef plus nullable
 * expected/observed strings, and capabilities arrive as CapabilityEvent.
 */
class SessionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SURAH = "surah"
        const val EXTRA_FROM_AYAH = "from_ayah"
        const val EXTRA_TO_AYAH = "to_ayah"
        const val MODE_MEMORIZATION = "mem"
        const val MODE_FREE = "free"

        private const val MODEL_ASSET_NAME = "model.onnx"
        // Published size of spliced-clean-lite (README); a copied file of
        // any other size is treated as partial and re-copied.
        private const val MODEL_EXPECTED_BYTES = 24650054L
        private const val SAMPLE_RATE = 16000
        private const val MIC_LEVEL_UPDATE_INTERVAL_MS = 150L
    }

    private lateinit var binding: ActivitySessionBinding
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var engine: TilawaEngine? = null
    private var recorder: MicRecorder? = null
    private var reference: QuranReference? = null

    private var mode: String = MODE_MEMORIZATION
    private var surah = 1
    private var fromAyah = 1
    private var toAyah = 7

    private var sessionRunning = false
    private var sessionEnded = false
    private var sessionStartMs = 0L

    private val liveMistakes = mutableListOf<MistakeItem>()
    private val shownEventIds = mutableSetOf<String>()
    private lateinit var mistakeAdapter: MistakeAdapter

    /** (ayah, wordIndex) -> character range in the Mushaf text. */
    private var wordSpans: Map<Pair<Int, Int>, IntRange> = emptyMap()
    private var currentHighlight: BackgroundColorSpan? = null
    private var lastLevelUpdateMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MEMORIZATION
        surah = intent.getIntExtra(EXTRA_SURAH, 1)
        fromAyah = intent.getIntExtra(EXTRA_FROM_AYAH, 1)
        toAyah = intent.getIntExtra(EXTRA_TO_AYAH, 7)

        binding.sessionTitle.text = if (mode == MODE_FREE) {
            getString(R.string.session_title_free)
        } else {
            getString(R.string.session_title_mem) + " — " +
                getString(R.string.surah_format, SurahData.urduName(surah))
        }

        mistakeAdapter = MistakeAdapter(liveMistakes)
        binding.mistakesRecycler.layoutManager = LinearLayoutManager(this)
        binding.mistakesRecycler.adapter = mistakeAdapter

        binding.endButton.setOnClickListener { onEndClicked() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onBackRequested()
                }
            }
        )

        setStatus(R.string.status_loading)
        executor.execute { startEngine() }
    }

    override fun onDestroy() {
        if (!sessionEnded) {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                engine?.end()
            } catch (_: Exception) {
            }
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---- Engine startup (background thread) ----

    private fun startEngine() {
        try {
            reference = QuranReference(this)
        } catch (_: Exception) {
            runOnUiThread { showFatalError(R.string.error_reference_failed) }
            return
        }
        val modelFile = copyModelToFilesDir()
        if (modelFile == null) {
            runOnUiThread { showFatalError(R.string.error_model_missing) }
            return
        }
        try {
            val session = OnnxSession(modelFile.absolutePath)
            val eng = TilawaEngine(session)
            eng.setListener(object : TilawaListener {
                override fun onCapabilities(c: CapabilityEvent) {
                    // Capabilities are not shown; the position line carries
                    // the state the reciter needs.
                }

                override fun onPosition(p: PositionEvent) {
                    runOnUiThread { onPositionEvent(p) }
                }

                override fun onChecker(e: CheckerEvent) {
                    runOnUiThread { onCheckerEvent(e) }
                }
            })
            eng.load(reference!!)
            engine = eng
            if (mode == MODE_FREE) {
                eng.startFreeRecitation()
            } else {
                eng.startMemorization(surah, fromAyah, toAyah)
            }
        } catch (_: Exception) {
            runOnUiThread { showFatalError(R.string.error_engine_failed) }
            return
        }
        runOnUiThread { onEngineReady() }
        try {
            val rec = MicRecorder()
            recorder = rec
            rec.start(onSamples = { samples ->
                engine?.pushAudio(samples, SAMPLE_RATE)
                updateMicLevel(samples)
            })
        } catch (_: Exception) {
            runOnUiThread { showFatalError(R.string.error_mic_unavailable) }
        }
    }

    /**
     * Copies model.onnx from assets to internal storage (ONNX Runtime needs
     * a file path). A copy of the wrong size is re-copied; a missing asset
     * returns null and the caller shows a plain message.
     */
    private fun copyModelToFilesDir(): File? {
        val outFile = File(filesDir, MODEL_ASSET_NAME)
        if (outFile.exists() && outFile.length() == MODEL_EXPECTED_BYTES) {
            return outFile
        }
        return try {
            assets.open(MODEL_ASSET_NAME).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.length() == MODEL_EXPECTED_BYTES) outFile else null
        } catch (_: Exception) {
            null
        }
    }

    private fun onEngineReady() {
        if (mode == MODE_FREE) {
            binding.mushafText.setText(R.string.free_recitation_hint)
        } else {
            buildMushaf()
        }
        sessionRunning = true
        sessionStartMs = System.currentTimeMillis()
        setStatus(R.string.status_waiting)
    }

    // ---- Mushaf display ----

    private fun buildMushaf() {
        val ref = reference ?: return
        val builder = SpannableStringBuilder()
        val spans = mutableMapOf<Pair<Int, Int>, IntRange>()
        for (ayah in fromAyah..toAyah) {
            val words = ref.ayahWords(surah, ayah)
            for ((index, word) in words.withIndex()) {
                val start = builder.length
                builder.append(word)
                spans[Pair(ayah, index)] = IntRange(start, builder.length)
                builder.append(" ")
            }
            builder.append("﴿").append(toArabicIndic(ayah)).append("﴾\n")
        }
        wordSpans = spans
        // SPANNABLE buffer: the highlight span is added/removed in place.
        binding.mushafText.setText(builder, TextView.BufferType.SPANNABLE)
    }

    private fun toArabicIndic(number: Int): String {
        val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        return number.toString().map { c ->
            if (c in '0'..'9') digits[c - '0'] else c
        }.joinToString("")
    }

    // ---- Engine events (main thread) ----

    private fun onPositionEvent(p: PositionEvent) {
        val statusRes = when (p.state) {
            "locked" -> R.string.status_locked
            "ambiguous" -> R.string.status_ambiguous
            "lost" -> R.string.status_lost
            else -> R.string.status_acquiring
        }
        setStatus(statusRes)
        // WordRef carries no text: look the word up in the reference.
        // observed is a ranked list, best first; show the top candidate.
        val expectedRef = p.expected
        binding.expectedWordText.text = expectedRef?.let {
            wordText(it.surah, it.ayah, it.word)
        } ?: ""
        val observedRef = p.observed.firstOrNull()?.word
        binding.observedWordText.text = observedRef?.let {
            wordText(it.surah, it.ayah, it.word)
        } ?: ""
        expectedRef?.let { highlightWord(it.ayah, it.word) }
    }

    /** The Uthmani word at (surah, ayah, word); empty when out of range. */
    private fun wordText(surah: Int, ayah: Int, word: Int): String {
        val ref = reference ?: return ""
        return try {
            ref.ayahWords(surah, ayah).getOrNull(word) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun onCheckerEvent(e: CheckerEvent) {
        // Only confirmed mistakes are shown live. Suspected ones may still
        // resolve; uncertain ones are not shown as mistakes.
        if (e.state != "confirmed") return
        if (!shownEventIds.add(e.id)) return
        liveMistakes.add(
            MistakeItem(
                e.location.surah,
                e.location.ayah,
                e.location.word,
                e.errorClass,
                e.expected ?: "",
                e.observed ?: ""
            )
        )
        mistakeAdapter.notifyItemInserted(liveMistakes.size - 1)
        binding.mistakesRecycler.scrollToPosition(liveMistakes.size - 1)
    }

    private fun highlightWord(ayah: Int, wordIndex: Int) {
        if (mode != MODE_MEMORIZATION) return
        val range = wordSpans[Pair(ayah, wordIndex)] ?: return
        val text = binding.mushafText.text as? Spannable ?: return
        currentHighlight?.let { text.removeSpan(it) }
        val span = BackgroundColorSpan(
            ContextCompat.getColor(this, R.color.highlight_current)
        )
        text.setSpan(span, range.first, range.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        currentHighlight = span
        // Keep the current word on screen; best effort only.
        try {
            val layout = binding.mushafText.layout ?: return
            val line = layout.getLineForOffset(range.first)
            val y = layout.getLineTop(line) - binding.mushafText.height / 3
            binding.mushafScroll.scrollTo(0, y.coerceAtLeast(0))
        } catch (_: Exception) {
        }
    }

    private fun updateMicLevel(samples: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - lastLevelUpdateMs < MIC_LEVEL_UPDATE_INTERVAL_MS) return
        lastLevelUpdateMs = now
        var sum = 0.0
        for (sample in samples) sum += (sample * sample).toDouble()
        val rms = sqrt(sum / samples.size.coerceAtLeast(1))
        val level = (rms * 500.0).toInt().coerceIn(0, 100)
        runOnUiThread { binding.micLevelBar.progress = level }
    }

    // ---- End of session ----

    private fun onEndClicked() {
        if (sessionEnded) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_end_title)
            .setMessage(R.string.confirm_end_message)
            .setPositiveButton(R.string.btn_yes) { _, _ -> endSession() }
            .setNegativeButton(R.string.btn_no, null)
            .show()
    }

    private fun onBackRequested() {
        if (sessionEnded || !sessionRunning) {
            finish()
        } else {
            onEndClicked()
        }
    }

    private fun endSession() {
        if (sessionEnded) return
        sessionEnded = true
        sessionRunning = false
        binding.endButton.isEnabled = false
        executor.execute {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            var finalMistakes: List<Mistake> = emptyList()
            try {
                engine?.end()
                finalMistakes = engine?.mistakes() ?: emptyList()
            } catch (_: Exception) {
            }
            val durationSec =
                ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt().coerceAtLeast(0)
            runOnUiThread { showSessionEnd(finalMistakes, durationSec) }
            saveToHistory(finalMistakes, durationSec)
        }
    }

    private fun showSessionEnd(finalMistakes: List<Mistake>, durationSec: Int) {
        // The engine's final list is authoritative: replace the live list.
        liveMistakes.clear()
        liveMistakes.addAll(finalMistakes.toItems())
        mistakeAdapter.notifyDataSetChanged()
        setStatus(R.string.status_ended)
        binding.mistakesEmptyText.visibility =
            if (liveMistakes.isEmpty()) View.VISIBLE else View.GONE
        val durationText = getString(
            R.string.history_duration_format,
            durationSec / 60,
            durationSec % 60
        )
        binding.summaryText.text = getString(
            R.string.session_summary_format,
            durationText,
            liveMistakes.size
        )
        binding.summaryText.visibility = View.VISIBLE
        binding.endButton.setText(R.string.btn_back)
        binding.endButton.isEnabled = true
    }

    private fun saveToHistory(finalMistakes: List<Mistake>, durationSec: Int) {
        try {
            val db = SessionHistoryDb(this)
            val savedSurah = if (mode == MODE_FREE) 0 else surah
            val savedFrom = if (mode == MODE_FREE) 0 else fromAyah
            val savedTo = if (mode == MODE_FREE) 0 else toAyah
            val sessionId = db.insertSession(mode, savedSurah, savedFrom, savedTo, durationSec)
            db.insertMistakes(
                sessionId,
                finalMistakes.toItems().map {
                    MistakeRecord(0, sessionId, it.surah, it.ayah, it.wordIndex, it.errorClass, it.expected, it.observed)
                }
            )
            db.close()
            runOnUiThread {
                Toast.makeText(this, R.string.session_saved, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            // History save must never break the end flow.
        }
    }

    // ---- Errors ----

    /** A startup failure: plain message on screen, End becomes Back, no crash. */
    private fun showFatalError(messageRes: Int) {
        sessionRunning = false
        sessionEnded = true
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            engine?.end()
        } catch (_: Exception) {
        }
        setStatus(messageRes)
        binding.mushafText.setText(messageRes)
        binding.endButton.setText(R.string.btn_back)
        binding.endButton.isEnabled = true
    }

    private fun setStatus(messageRes: Int) {
        binding.statusText.setText(messageRes)
    }

    /**
     * The ONLY place that reads Mistake's fields. If Mistake lands with a
     * different shape than the contract (e.g. location: WordRef like
     * CheckerEvent), fix this one function.
     */
    private fun List<Mistake>.toItems(): List<MistakeItem> =
        map {
            MistakeItem(it.surah, it.ayah, it.wordIndex, it.errorClass, it.expected, it.observed)
        }
}
