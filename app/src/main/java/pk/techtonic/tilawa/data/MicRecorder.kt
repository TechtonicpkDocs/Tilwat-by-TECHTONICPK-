// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import pk.techtonic.tilawa.engine.MicrophoneError

/**
 * Microphone capture at 16 kHz mono 16-bit PCM, pushing FloatArray chunks (100 ms each) to the engine
 * on a background thread. No-arg constructor (UI contract).
 *
 * `start()` throws a [MicrophoneError] plainly when the microphone cannot be opened — permission denied
 * (`reason = "denied"`) or the mic unavailable/in use (`reason = "unavailable"`) — and never crashes.
 * A mid-stream failure (the mic dying, or the engine callback throwing) stops the loop and is reported
 * once through the optional `onError`; a normal `stop()` never reports an error.
 *
 * `stop()` is idempotent and safe to call when not recording. `start()` while already recording is a
 * no-op.
 */
class MicRecorder {

    companion object {
        const val SAMPLE_RATE = 16000

        /** 100 ms of audio per callback. */
        private const val CHUNK_SAMPLES = 1600
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    private var running = false

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    fun start(onSamples: (FloatArray) -> Unit, onError: ((Exception) -> Unit)? = null) {
        if (isRecording) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuf <= 0) 32000 else maxOf(minBuf, 16000)
        val record: AudioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: SecurityException) {
            throw MicrophoneError(
                "denied",
                "Microphone permission denied — allow the microphone to record recitation.",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw MicrophoneError("unavailable", "The microphone is not available on this device.", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicrophoneError(
                "unavailable",
                "The microphone could not be opened — it may be in use by another app.",
            )
        }
        try {
            record.startRecording()
        } catch (e: SecurityException) {
            record.release()
            throw MicrophoneError(
                "denied",
                "Microphone permission denied — allow the microphone to record recitation.",
                e,
            )
        } catch (e: IllegalStateException) {
            record.release()
            throw MicrophoneError("unavailable", "The microphone could not start recording.", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw MicrophoneError("unavailable", "The microphone could not start recording.")
        }
        recorder = record
        running = true
        isRecording = true
        val t = Thread({
            val shorts = ShortArray(CHUNK_SAMPLES)
            val floats = FloatArray(CHUNK_SAMPLES)
            while (running) {
                val read = record.read(shorts, 0, CHUNK_SAMPLES)
                // stop() unblocks a pending read; that is a normal exit, not an error.
                if (!running) break
                if (read < 0) {
                    onError?.invoke(
                        MicrophoneError("unavailable", "The microphone stopped mid-recording (error $read).")
                    )
                    break
                }
                if (read == 0) continue
                for (i in 0 until read) floats[i] = shorts[i] / 32768f
                try {
                    onSamples(floats.copyOf(read))
                } catch (e: Exception) {
                    onError?.invoke(e)
                    break
                }
            }
            running = false
            isRecording = false
        }, "TilawaMic")
        thread = t
        t.start()
    }

    fun stop() {
        if (!isRecording && recorder == null) return
        running = false
        val record = recorder
        recorder = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
        try {
            record?.release()
        } catch (_: Exception) {
            // Release is best-effort.
        }
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        isRecording = false
    }
}
