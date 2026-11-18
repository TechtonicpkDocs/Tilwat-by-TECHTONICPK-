// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Streaming 80-dim log-mel fbank at 16 kHz, identical to the student's training features:
 * `torchaudio.compliance.kaldi.fbank(w, num_mel_bins=80, dither=0, energy_floor=1e-10, high_freq=-400)` on float
 * samples in [-1, 1]: 25 ms povey window, 10 ms shift, snip_edges, per-frame DC removal, pre-emphasis 0.97.
 * Double (Float64) arithmetic throughout, operation order matching the TS exactly.
 */
const val SAMPLE_RATE = 16000
private const val WIN = 400
private const val HOP = 160
private const val NFFT = 512
private const val MELS = 80
private const val EPS = 1.1920928955078125e-7 // 2^-23

private fun mel(hz: Double): Double = 1127 * ln(1 + hz / 700)

private val povey = DoubleArray(WIN) { i -> (0.5 - 0.5 * cos((2 * PI * i) / (WIN - 1))).pow(0.85) }

private data class MelFilter(val start: Int, val weights: DoubleArray)

private fun makeMelBank(highFrequency: Double): List<MelFilter> {
    val lo = mel(20.0)
    val hi = mel(highFrequency)
    val step = (hi - lo) / (MELS + 1)
    return (0 until MELS).map { m ->
        val l = lo + m * step
        val c = lo + (m + 1) * step
        val r = lo + (m + 2) * step
        val bins = ArrayList<Int>()
        val weights = ArrayList<Double>()
        for (k in 0 until NFFT / 2) {
            val f = mel((k * SAMPLE_RATE).toDouble() / NFFT)
            val v = minOf((f - l) / (c - l), (r - f) / (r - c))
            if (v > 0) {
                bins.add(k)
                weights.add(v)
            }
        }
        MelFilter(bins.firstOrNull() ?: 0, weights.toDoubleArray())
    }
}

private val melBank: List<MelFilter> = makeMelBank(SAMPLE_RATE / 2.0 - 400)

private val cosTable = DoubleArray(NFFT / 2) { k -> cos((-2 * PI * k) / NFFT) }
private val sinTable = DoubleArray(NFFT / 2) { k -> sin((-2 * PI * k) / NFFT) }
private val reversed = IntArray(NFFT) { i ->
    var r = 0
    var b = 1
    var x = i
    while (b < NFFT) {
        r = (r shl 1) or (x and 1)
        b = b shl 1
        x = x shr 1
    }
    r
}

private fun fft(re: DoubleArray, im: DoubleArray) {
    for (i in 0 until NFFT) {
        val j = reversed[i]
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= NFFT) {
        val half = len shr 1
        val stride = NFFT / len
        var i = 0
        while (i < NFFT) {
            for (k in 0 until half) {
                val wr = cosTable[k * stride]
                val wi = sinTable[k * stride]
                val a = i + k
                val b = a + half
                val xr = re[b] * wr - im[b] * wi
                val xi = re[b] * wi + im[b] * wr
                re[b] = re[a] - xr
                im[b] = im[a] - xi
                re[a] += xr
                im[a] += xi
            }
            i += len
        }
        len = len shl 1
    }
}

class Fbank(highFrequency: Double? = null) {
    private val bank: List<MelFilter> = if (highFrequency == null) melBank else makeMelBank(highFrequency)
    private var pending = FloatArray(0)
    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)

    /** Accepts 16 kHz float samples in [-1, 1]; returns every newly complete frame. */
    fun push(samples: FloatArray): List<FloatArray> {
        val buf = FloatArray(pending.size + samples.size)
        pending.copyInto(buf, 0)
        samples.copyInto(buf, pending.size)
        val out = ArrayList<FloatArray>()
        var off = 0
        while (off + WIN <= buf.size) {
            re.fill(0.0)
            im.fill(0.0)
            var mean = 0.0
            for (i in 0 until WIN) mean += buf[off + i].toDouble()
            mean /= WIN
            for (i in WIN - 1 downTo 0) {
                val x = buf[off + i].toDouble() - mean
                val prev = if (i > 0) buf[off + i - 1].toDouble() - mean else x
                re[i] = (x - 0.97 * prev) * povey[i]
            }
            fft(re, im)
            val frame = FloatArray(MELS)
            for (m in 0 until MELS) {
                val filter = bank[m]
                var e = 0.0
                for (j in filter.weights.indices) {
                    val k = filter.start + j
                    e += filter.weights[j] * (re[k] * re[k] + im[k] * im[k])
                }
                frame[m] = ln(maxOf(e, EPS)).toFloat()
            }
            out.add(frame)
            off += HOP
        }
        pending = buf.copyOfRange(off, buf.size)
        return out
    }
}
