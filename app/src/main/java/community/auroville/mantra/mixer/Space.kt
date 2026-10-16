package community.auroville.mantra.mixer

// Vocal space: the backing track steps aside, frequency by frequency, wherever the vocal is singing.
// The idea of Wavesfactory Trackspacer and of soothe's external sidechain (oeksound); the per-band sidechain
// matching follows Robbert van der Helm's Spectral Compressor (nih-plug, GPL-3.0), written fresh here, not copied.
// Nothing from Android in it, so Test 1 runs it on a desk.
//
// Short-time Fourier transform, 2048 points, hop 512 (75 % overlap), sqrt-Hann in and out: with no reduction
// the backing comes out bit-for-bit what went in, to float precision. Per hop, in 1/3-octave bands from 100 Hz to
// 12 kHz (the voice; kick and sub and air are never touched):
//   ratio  = vocal band power / backing band power          (how much the vocal is fighting for that band)
//   amount = 0 at ratio −15 dB … 1 at 0 dB and above          (the vocal at least as loud as the backing there)
//          × a gate that fades out below −70 dBFS vocal        (breaths and bleed do not duck anything)
//   target = −strength × amount, then smoothed 1-2-1 across neighbouring bands (no narrow notches: soothe's habit)
//   gain   = target followed with 20 ms attack, 200 ms release, interpolated per FFT bin on a log-frequency axis.
// The same real gain is applied to left and right, so the stereo image does not move.

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class Space(private val rate: Int = Mix.RATE) {
    companion object {
        const val N = 2048
        const val HOP = 512
        const val MAX_DB = 6.0
        const val LOW_HZ = 100.0
        const val HIGH_HZ = 12_000.0
        private const val BITS = 11
    }

    /** Most the backing may be pulled down, in dB (0 = off, bypassed exactly). Can change while playing. */
    @Volatile var strengthDb = 3.0

    /** The deepest band reduction right now, in dB (≤ 0), for a meter. */
    @Volatile var meterDb = 0.0
        private set

    /** The deepest reduction applied anywhere since the last reset, in dB (≤ 0). */
    var maxReductionDb = 0.0
        private set

    // window, FFT tables
    private val win = FloatArray(N) { sin(PI * it / N).toFloat() }                 // periodic sqrt-Hann
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }
    private val rev = IntArray(N) { Integer.reverse(it) ushr (32 - BITS) }

    // bands: 1/3 octave from LOW_HZ, centres in log2(Hz); band of each bin, −1 outside
    private val bandCount = (ln(HIGH_HZ / LOW_HZ) / ln(2.0) * 3).toInt()
    private val centreLog = DoubleArray(bandCount) { log2(LOW_HZ) + (it + 0.5) / 3.0 }
    private val binBand = IntArray(N / 2 + 1) { k ->
        val f = k.toDouble() * rate / N
        if (f < LOW_HZ || f >= LOW_HZ * 2.0.pow(bandCount / 3.0)) -1 else ((log2(f / LOW_HZ)) * 3).toInt().coerceIn(0, bandCount - 1)
    }
    private val binCount = IntArray(bandCount).also { c -> binBand.forEach { if (it >= 0) c[it]++ } }

    private val attack = exp(-HOP / (0.020 * rate))
    private val release = exp(-HOP / (0.200 * rate))
    private val env = DoubleArray(bandCount)         // per band, dB ≤ 0
    private val binGain = FloatArray(N / 2 + 1)

    // work buffers
    private val vRe = FloatArray(N); private val vIm = FloatArray(N)
    private val zRe = FloatArray(N); private val zIm = FloatArray(N)
    private val vPow = DoubleArray(bandCount); private val bPow = DoubleArray(bandCount)
    private val target = DoubleArray(bandCount)

    // overlap-add: ring of output samples (stereo), absolute frame index mod RING
    private val ring = 4 * N
    private val accL = FloatArray(ring); private val accR = FloatArray(ring)
    private var nextFrame = 0      // next STFT frame k to compute; frame k covers samples [k*HOP − N, k*HOP)
    private var outPos = -1        // next output sample; −1 = needs reset

    fun reset() { outPos = -1; env.fill(0.0); meterDb = 0.0; maxReductionDb = 0.0 }

    /**
     * The mix for frames [start, start+frames): the vocal (shifted by [vocalOffsetFrames], times [vocalGain]) plus
     * the backing (times [backingGain]) with room made for the vocal. Same contract as Mix.mixBlock. Calls must be
     * consecutive; a jump (a seek) restarts the analysis there.
     */
    fun mixBlock(
        vocal: FloatArray, backing: FloatArray, start: Int, frames: Int,
        vocalGain: Float, backingGain: Float, vocalOffsetFrames: Int, out: FloatArray,
    ) {
        if (strengthDb <= 0.0 || backingGain == 0f) {
            outPos = -1; env.fill(0.0); meterDb = 0.0
            Mix.mixBlock(vocal, backing, start, frames, vocalGain, backingGain, vocalOffsetFrames, out)
            return
        }
        if (start != outPos) {
            outPos = start
            nextFrame = Math.floorDiv(start, HOP) + 1
            accL.fill(0f); accR.fill(0f)
        }
        val vf = vocal.size / 2
        for (i in 0 until frames) {
            val f = start + i
            // every frame that touches sample f must be in the accumulator before f leaves it
            while (nextFrame.toLong() * HOP - N <= f) {
                frame(nextFrame, vocal, backing, vocalGain, backingGain, vocalOffsetFrames); nextFrame++
            }
            val r = Math.floorMod(f, ring)
            val v = f - vocalOffsetFrames
            val a = if (v in 0 until vf) vocalGain else 0f
            out[2 * i] = accL[r] + if (a != 0f) vocal[2 * v] * a else 0f
            out[2 * i + 1] = accR[r] + if (a != 0f) vocal[2 * v + 1] * a else 0f
            accL[r] = 0f; accR[r] = 0f
        }
        outPos = start + frames
    }

    private fun frame(k: Int, vocal: FloatArray, backing: FloatArray, vg: Float, bg: Float, off: Int) {
        val s = k * HOP - N
        val vf = vocal.size / 2
        val bf = backing.size / 2
        for (n in 0 until N) {
            val t = s + n
            val w = win[n]
            val v = t - off
            vRe[n] = if (v in 0 until vf) (vocal[2 * v] + vocal[2 * v + 1]) * 0.5f * vg * w else 0f
            vIm[n] = 0f
            // left and right packed into one complex FFT: z = L + iR
            if (t in 0 until bf) { zRe[n] = backing[2 * t] * bg * w; zIm[n] = backing[2 * t + 1] * bg * w } else { zRe[n] = 0f; zIm[n] = 0f }
        }
        fft(vRe, vIm, false)
        fft(zRe, zIm, false)

        vPow.fill(0.0); bPow.fill(0.0)
        for (b in 0..N / 2) {
            val band = binBand[b]
            if (band < 0) continue
            vPow[band] += (vRe[b] * vRe[b] + vIm[b] * vIm[b]).toDouble()
            val m = (N - b) % N
            // |L|² + |R|² = (|Z[b]|² + |Z[N−b]|²) / 2
            bPow[band] += 0.5 * (zRe[b] * zRe[b] + zIm[b] * zIm[b] + zRe[m] * zRe[m] + zIm[m] * zIm[m])
        }

        val strength = strengthDb.coerceIn(0.0, MAX_DB)
        // a full-scale sine through this window: |X|² ≈ (N/4)²; levels are per bin relative to that
        val ref = (N / 4.0) * (N / 4.0)
        for (i in 0 until bandCount) {
            val vb = vPow[i] / binCount[i]; val bb = bPow[i] / binCount[i]
            val ratioDb = 10 * log10((vb + 1e-12) / (bb + 1e-12))
            val levelDb = 10 * log10(vb / ref + 1e-15)
            val amount = ((ratioDb + 15) / 15).coerceIn(0.0, 1.0) * ((levelDb + 70) / 10).coerceIn(0.0, 1.0)
            target[i] = -strength * amount
        }
        var deepest = 0.0
        for (i in 0 until bandCount) {
            val l = target[maxOf(i - 1, 0)]; val r = target[minOf(i + 1, bandCount - 1)]
            val t = 0.25 * l + 0.5 * target[i] + 0.25 * r
            env[i] = if (t < env[i]) t + (env[i] - t) * attack else t + (env[i] - t) * release
            if (env[i] < deepest) deepest = env[i]
        }
        meterDb = deepest
        if (deepest < maxReductionDb) maxReductionDb = deepest

        // per bin: interpolate the band envelope on log frequency; fade to 0 dB over half an octave at both ends
        for (b in 0..N / 2) {
            val f = b.toDouble() * rate / N
            val db = if (f <= 0.0) 0.0 else {
                val x = log2(f)
                when {
                    x <= centreLog[0] -> env[0] * (1 - (centreLog[0] - x) * 2).coerceIn(0.0, 1.0)
                    x >= centreLog[bandCount - 1] -> env[bandCount - 1] * (1 - (x - centreLog[bandCount - 1]) * 2).coerceIn(0.0, 1.0)
                    else -> {
                        val p = (x - centreLog[0]) * 3
                        val j = p.toInt().coerceAtMost(bandCount - 2)
                        val u = p - j
                        env[j] * (1 - u) + env[j + 1] * u
                    }
                }
            }
            binGain[b] = 10.0.pow(db / 20).toFloat()
        }
        for (b in 0 until N) {
            val g = binGain[if (b <= N / 2) b else N - b]
            zRe[b] *= g; zIm[b] *= g
        }
        fft(zRe, zIm, true)

        // sqrt-Hann twice at 75 % overlap sums to 2: halve it
        for (n in 0 until N) {
            val r = Math.floorMod(s + n, ring)
            val w = win[n] * 0.5f
            accL[r] += zRe[n] * w
            accR[r] += zIm[n] * w
        }
    }

    /** In-place radix-2 complex FFT of size N; the inverse includes the 1/N. */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var i = 0
            while (i < N) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]
                    val wi = if (inverse) sinT[j * step] else -sinT[j * step]
                    val a = i + j; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += size
            }
            size *= 2
        }
        if (inverse) { val s = 1f / N; for (i in 0 until N) { re[i] *= s; im[i] *= s } }
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
