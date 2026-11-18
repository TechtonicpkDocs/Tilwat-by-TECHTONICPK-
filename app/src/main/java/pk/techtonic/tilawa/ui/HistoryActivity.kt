package pk.techtonic.tilawa.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import pk.techtonic.tilawa.R
import pk.techtonic.tilawa.data.SessionHistoryDb
import pk.techtonic.tilawa.data.SessionRecord
import pk.techtonic.tilawa.databinding.ActivityHistoryBinding

/**
 * Session history: newest first, with date, passage, mode, duration and
 * mistake counts. Tap a session to see its mistakes; delete one session
 * or clear all, each behind a confirmation. System back from the detail
 * view returns to the list first.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var db: SessionHistoryDb

    private val sessions = mutableListOf<SessionRecord>()
    private lateinit var sessionsAdapter: HistorySessionAdapter

    private val detailMistakes = mutableListOf<MistakeItem>()
    private lateinit var detailAdapter: MistakeAdapter

    private var showingDetail = false
    private var currentSessionId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = SessionHistoryDb(this)

        sessionsAdapter = HistorySessionAdapter(sessions) { session -> showDetail(session) }
        binding.sessionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.sessionsRecycler.adapter = sessionsAdapter

        detailAdapter = MistakeAdapter(detailMistakes)
        binding.detailRecycler.layoutManager = LinearLayoutManager(this)
        binding.detailRecycler.adapter = detailAdapter

        binding.clearAllButton.setOnClickListener { confirmClearAll() }
        binding.detailBackButton.setOnClickListener { showList() }
        binding.deleteSessionButton.setOnClickListener { confirmDeleteSession() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (showingDetail) {
                        showList()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        loadSessions()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun loadSessions() {
        Thread {
            val loaded = try {
                db.getSessions()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                sessions.clear()
                sessions.addAll(loaded)
                sessionsAdapter.notifyDataSetChanged()
                val empty = loaded.isEmpty()
                binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
                binding.sessionsRecycler.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun showDetail(session: SessionRecord) {
        currentSessionId = session.id
        binding.detailTitle.text = if (session.mode == SessionActivity.MODE_FREE) {
            getString(R.string.history_free_label)
        } else {
            getString(
                R.string.history_passage_format,
                SurahData.urduName(session.surah),
                session.fromAyah,
                session.toAyah
            )
        }
        Thread {
            val mistakes = try {
                db.getMistakes(session.id)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                detailMistakes.clear()
                detailMistakes.addAll(
                    mistakes.map {
                        MistakeItem(it.surah, it.ayah, it.wordIndex, it.errorClass, it.expected, it.observed)
                    }
                )
                detailAdapter.notifyDataSetChanged()
                val empty = detailMistakes.isEmpty()
                binding.detailEmptyText.visibility = if (empty) View.VISIBLE else View.GONE
                binding.detailRecycler.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }.start()
        binding.listContainer.visibility = View.GONE
        binding.detailContainer.visibility = View.VISIBLE
        showingDetail = true
    }

    private fun showList() {
        binding.detailContainer.visibility = View.GONE
        binding.listContainer.visibility = View.VISIBLE
        showingDetail = false
        currentSessionId = -1L
        loadSessions()
    }

    private fun confirmDeleteSession() {
        if (currentSessionId < 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.btn_yes) { _, _ ->
                Thread {
                    try {
                        db.deleteSession(currentSessionId)
                    } catch (_: Exception) {
                    }
                    runOnUiThread {
                        Toast.makeText(this, R.string.session_deleted, Toast.LENGTH_SHORT).show()
                        showList()
                    }
                }.start()
            }
            .setNegativeButton(R.string.btn_no, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(R.string.confirm_clear_message)
            .setPositiveButton(R.string.btn_yes) { _, _ ->
                Thread {
                    try {
                        db.clearAll()
                    } catch (_: Exception) {
                    }
                    runOnUiThread {
                        Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                        loadSessions()
                    }
                }.start()
            }
            .setNegativeButton(R.string.btn_no, null)
            .show()
    }
}
