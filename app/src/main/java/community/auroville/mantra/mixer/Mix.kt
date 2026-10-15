package community.auroville.mantra.mixer

// The mechanism, with nothing from Android in it: gains, resampling, mixing, limiting, the WAV header.
// scripts/verify.py fails the build the day an `import android.` appears here, so Test 1 can run on a desk.

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

object Mix {
    const val RATE = 48_000
    const val MIN_DB = -30
    const val MAX_DB = 12

    /** Fader position in dB to a linear gain; at the bottom of the fader the track is silent. */
    fun dbToGain(db: Double): Float = if (db <= MIN_DB) 0f else 10.0.pow(db / 20.0).toFloat()

    fun gainToDb(g: Float): Double = if (g <= 0f) Double.NEGATIVE_INFINITY else 20.0 * log10(g.toDouble())

    /**
     * Resample interleaved stereo float audio from [fromRate] to [toRate] with 4-point cubic (Catmull-Rom)
     * interpolation: far cleaner than linear, no filter tables. 44.1 -> 48 kHz is the case it is here for.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val frames = input.size / 2
        val outFrames = ((frames.toLong() * toRate) / fromRate).toInt()
        val out = FloatArray(outFrames * 2)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outFrames) {
            val pos = i * step
            val k = floor(pos).toInt()
            val t = (pos - k).toFloat()
            for (c in 0..1) {
                val p0 = input[2 * (k - 1).coerceIn(0, frames - 1) + c]
                val p1 = input[2 * k.coerceIn(0, frames - 1) + c]
                val p2 = input[2 * (k + 1).coerceIn(0, frames - 1) + c]
                val p3 = input[2 * (k + 2).coerceIn(0, frames - 1) + c]
                out[2 * i + c] = 0.5f * ((2f * p1) + (-p0 + p2) * t +
                        (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                        (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
            }
        }
        return out
    }

    /** Mono to interleaved stereo, or stereo passed through; more channels are folded to the first two. */
    fun toStereo(input: FloatArray, channels: Int): FloatArray = when (channels) {
        2 -> input
        1 -> FloatArray(input.size * 2) { input[it / 2] }
        else -> FloatArray(input.size / channels * 2) { input[(it / 2) * channels + (it % 2)] }
    }

    /**
     * One block of the mix: [vocal] shifted by [vocalOffsetFrames] (positive = later), plus [backing], from
     * frame [start] for [frames] frames, interleaved stereo. Missing audio past either end is silence.
     */
    fun mixBlock(
        vocal: FloatArray, backing: FloatArray, start: Int, frames: Int,
        vocalGain: Float, backingGain: Float, vocalOffsetFrames: Int, out: FloatArray,
    ) {
        val vf = vocal.size / 2
        val bf = backing.size / 2
        for (i in 0 until frames) {
            val f = start + i
            val v = f - vocalOffsetFrames
            for (c in 0..1) {
                val a = if (v in 0 until vf) vocal[2 * v + c] * vocalGain else 0f
                val b = if (f in 0 until bf) backing[2 * f + c] * backingGain else 0f
                out[2 * i + c] = a + b
            }
        }
    }

    /** Length of the mix in frames: the longer of the two, the vocal counted with its offset. */
    fun mixFrames(vocalFrames: Int, backingFrames: Int, vocalOffsetFrames: Int): Int =
        maxOf(backingFrames, vocalFrames + vocalOffsetFrames).coerceAtLeast(0)

    /**
     * A peak limiter with instant attack and smooth release, ceiling [ceiling] (linear). Keeps state between
     * blocks, so playback and export limit identically. Nothing above the ceiling ever leaves it.
     */
    class Limiter(private val ceiling: Float = 0.89f, releaseMs: Double = 80.0, rate: Int = RATE) {
        private val release = (1.0 - 1.0 / (releaseMs / 1000.0 * rate)).toFloat()
        private var gain = 1f
        var maxReduction = 1f
            private set

        fun process(buf: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val peak = maxOf(abs(buf[2 * i]), abs(buf[2 * i + 1]))
                val need = if (peak * gain > ceiling) ceiling / peak else 1f
                gain = if (need < gain) need else gain + (1f - gain) * (1f - release)
                if (gain < maxReduction) maxReduction = gain
                buf[2 * i] *= gain
                buf[2 * i + 1] *= gain
            }
        }
    }

    /** Float to 16-bit little-endian PCM with TPDF dither. */
    fun toPcm16(buf: FloatArray, samples: Int, out: ByteArray, seed: IntArray) {
        for (i in 0 until samples) {
            seed[0] = seed[0] * 1664525 + 1013904223
            val r1 = (seed[0] ushr 8) / 16777216f
            seed[0] = seed[0] * 1664525 + 1013904223
            val r2 = (seed[0] ushr 8) / 16777216f
            val s = (buf[i] * 32767f + (r1 - r2)).roundToInt().coerceIn(-32768, 32767)
            out[2 * i] = (s and 0xff).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xff).toByte()
        }
    }

    /** The 44-byte header of a 16-bit stereo PCM WAV file holding [frames] frames at [rate]. */
    fun wavHeader(frames: Int, rate: Int = RATE): ByteArray {
        val data = frames.toLong() * 4
        val h = ByteArray(44)
        fun put(o: Int, s: String) = s.forEachIndexed { i, ch -> h[o + i] = ch.code.toByte() }
        fun le(o: Int, v: Long, n: Int) { for (i in 0 until n) h[o + i] = ((v shr (8 * i)) and 0xff).toByte() }
        put(0, "RIFF"); le(4, 36 + data, 4); put(8, "WAVE")
        put(12, "fmt "); le(16, 16, 4); le(20, 1, 2); le(22, 2, 2); le(24, rate.toLong(), 4)
        le(28, rate.toLong() * 4, 4); le(32, 4, 2); le(34, 16, 2)
        put(36, "data"); le(40, data, 4)
        return h
    }

    fun clock(frames: Int, rate: Int = RATE): String {
        val s = frames / rate
        return "%d:%02d".format(s / 60, s % 60)
    }
}
