package pk.techtonic.tilawa.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One saved recitation session. For free-recitation sessions surah,
 * fromAyah and toAyah are 0 (no declared passage).
 */
data class SessionRecord(
    val id: Long,
    val startedAt: Long,
    val mode: String,
    val surah: Int,
    val fromAyah: Int,
    val toAyah: Int,
    val durationSec: Int,
    val mistakeCount: Int
)

/** One mistake inside a saved session. */
data class MistakeRecord(
    val id: Long,
    val sessionId: Long,
    val surah: Int,
    val ayah: Int,
    val wordIndex: Int,
    val errorClass: String,
    val expected: String,
    val observed: String
)

/**
 * Session history in plain SQLite (fleet standard: SQLite only, no Room).
 * The database is excluded from auto-backup and device transfer
 * (res/xml/backup_rules.xml + data_extraction_rules.xml).
 */
class SessionHistoryDb(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "tilawa_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_MISTAKES = "mistakes"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_SESSIONS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "started_at INTEGER NOT NULL, " +
                "mode TEXT NOT NULL, " +
                "surah INTEGER NOT NULL, " +
                "from_ayah INTEGER NOT NULL, " +
                "to_ayah INTEGER NOT NULL, " +
                "duration_sec INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE $TABLE_MISTAKES (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id INTEGER NOT NULL, " +
                "surah INTEGER NOT NULL, " +
                "ayah INTEGER NOT NULL, " +
                "word_index INTEGER NOT NULL, " +
                "error_class TEXT NOT NULL, " +
                "expected TEXT NOT NULL, " +
                "observed TEXT NOT NULL, " +
                "FOREIGN KEY(session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MISTAKES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }

    /** Inserts a session row and returns its id. */
    fun insertSession(
        mode: String,
        surah: Int,
        fromAyah: Int,
        toAyah: Int,
        durationSec: Int
    ): Long {
        val values = ContentValues().apply {
            put("started_at", System.currentTimeMillis())
            put("mode", mode)
            put("surah", surah)
            put("from_ayah", fromAyah)
            put("to_ayah", toAyah)
            put("duration_sec", durationSec)
        }
        return writableDatabase.insert(TABLE_SESSIONS, null, values)
    }

    /** Inserts all mistakes of one session in a single transaction. */
    fun insertMistakes(sessionId: Long, mistakes: List<MistakeRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (mistake in mistakes) {
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("surah", mistake.surah)
                    put("ayah", mistake.ayah)
                    put("word_index", mistake.wordIndex)
                    put("error_class", mistake.errorClass)
                    put("expected", mistake.expected)
                    put("observed", mistake.observed)
                }
                db.insert(TABLE_MISTAKES, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** All sessions, newest first, each with its mistake count. */
    fun getSessions(): List<SessionRecord> {
        val result = mutableListOf<SessionRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT s.id, s.started_at, s.mode, s.surah, s.from_ayah, s.to_ayah, " +
                "s.duration_sec, COUNT(m.id) AS mistake_count " +
                "FROM $TABLE_SESSIONS s LEFT JOIN $TABLE_MISTAKES m " +
                "ON m.session_id = s.id " +
                "GROUP BY s.id ORDER BY s.started_at DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    SessionRecord(
                        id = it.getLong(0),
                        startedAt = it.getLong(1),
                        mode = it.getString(2) ?: "",
                        surah = it.getInt(3),
                        fromAyah = it.getInt(4),
                        toAyah = it.getInt(5),
                        durationSec = it.getInt(6),
                        mistakeCount = it.getInt(7)
                    )
                )
            }
        }
        return result
    }

    /** All mistakes of one session, in the order they happened. */
    fun getMistakes(sessionId: Long): List<MistakeRecord> {
        val result = mutableListOf<MistakeRecord>()
        val cursor = readableDatabase.query(
            TABLE_MISTAKES,
            arrayOf("id", "session_id", "surah", "ayah", "word_index", "error_class", "expected", "observed"),
            "session_id = ?",
            arrayOf(sessionId.toString()),
            null,
            null,
            "id ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    MistakeRecord(
                        id = it.getLong(0),
                        sessionId = it.getLong(1),
                        surah = it.getInt(2),
                        ayah = it.getInt(3),
                        wordIndex = it.getInt(4),
                        errorClass = it.getString(5) ?: "",
                        expected = it.getString(6) ?: "",
                        observed = it.getString(7) ?: ""
                    )
                )
            }
        }
        return result
    }

    /** Deletes one session and all its mistakes. */
    fun deleteSession(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_MISTAKES, "session_id = ?", arrayOf(id.toString()))
        db.delete(TABLE_SESSIONS, "id = ?", arrayOf(id.toString()))
    }

    /** Deletes every session and every mistake. */
    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_MISTAKES, null, null)
        db.delete(TABLE_SESSIONS, null, null)
    }
}
