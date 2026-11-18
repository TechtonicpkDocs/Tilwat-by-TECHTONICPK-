// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin

/** Streaming windowed-sinc resampler, low-passed just under the output Nyquist. */
class Resampler(val from: Int, val to: Int) {
    private val ratio: Double = from.toDouble() / to
    private val cutoff: Double = minOf(1.0, to.toDouble() / from) * 0.95
    private var history = FloatArray(0)
    /** Input position of the next output sample, relative to `history[0]`. */
    private var pos = 0.0

    fun push(input: FloatArray): FloatArray {
        if (from == to) return input
        val buf = FloatArray(history.size + input.size)
        history.copyInto(buf, 0)
        input.copyInto(buf, history.size)
        val taps = ceil(TAPS / cutoff).toInt()
        val out = ArrayList<Double>()
        while (pos + taps < buf.size) {
            val c = floor(pos).toInt()
            var s = 0.0
            var norm = 0.0
            for (k in (c - taps + 1)..(c + taps)) {
                val x = (k - pos) * cutoff
                val w = 0.5 + 0.5 * cos((PI * (k - pos)) / (taps + 1))
                val h = (if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)) * w
                norm += h
                if (k >= 0) s += buf[k].toDouble() * h
            }
            out.add(s / norm)
            pos += ratio
        }
        val keep = maxOf(0, floor(pos).toInt() - taps)
        history = buf.copyOfRange(keep, buf.size)
        pos -= keep
        return FloatArray(out.size) { out[it].toFloat() }
    }

    /** The samples still held back for the filter's right side. */
    fun flush(): FloatArray {
        val taps = ceil(TAPS / cutoff).toInt()
        return if (from == to) FloatArray(0) else push(FloatArray(taps + 1))
    }

    companion object {
        private const val TAPS = 16
    }
}

/**
 * Energy VAD with an adaptive noise floor, on 16 kHz audio in 20 ms frames. It gates what reaches the listener, so the
 * model doesn't run on long silences. Dropped audio compresses the session's timeline, so the hangover must outlast the
 * checker's confirm delay (2.5 s by default): pending errors confirm on the blanks decoded during the hangover.
 */
class Vad(
    marginDb: Double = 12.0,
    hangMs: Int = 4000,
    preRollMs: Int = 300,
) {
    private var floorDb = -60.0
    private var speaking = false
    private var silentMs = 0
    private var pre = ArrayList<FloatArray>()
    private var rest = FloatArray(0)
    private val margin: Double = marginDb
    private val hang: Int = hangMs
    private val preRoll: Int = preRollMs

    val active: Boolean get() = speaking

    /** Returns the audio to pass on: speech plus pre-roll and hangover, in order. */
    fun push(samples: FloatArray): FloatArray {
        val buf = FloatArray(rest.size + samples.size)
        rest.copyInto(buf, 0)
        samples.copyInto(buf, rest.size)
        val out = ArrayList<FloatArray>()
        var off = 0
        while (off + FRAME <= buf.size) {
            val f = buf.copyOfRange(off, off + FRAME)
            var e = 0.0
            for (i in f.indices) e += f[i].toDouble() * f[i].toDouble()
            val db = 10 * log10(e / f.size + 1e-12)
            val loud = db > floorDb + margin
            if (!loud) floorDb += (db - floorDb) * (if (db < floorDb) 0.2 else 0.01)
            if (loud) {
                if (!speaking) out.addAll(pre)
                pre = ArrayList()
                speaking = true
                silentMs = 0
            } else if (speaking) {
                silentMs += 20
                if (silentMs > hang) speaking = false
            }
            if (speaking) out.add(f.copyOf()) else {
                pre.add(f.copyOf())
                if (pre.size * 20 > preRoll) pre.removeAt(0)
            }
            off += FRAME
        }
        rest = buf.copyOfRange(off, buf.size)
        val n = out.sumOf { it.size }
        val res = FloatArray(n)
        var o = 0
        for (f in out) {
            f.copyInto(res, o)
            o += f.size
        }
        return res
    }

    companion object {
        private const val FRAME = 320
    }
}
