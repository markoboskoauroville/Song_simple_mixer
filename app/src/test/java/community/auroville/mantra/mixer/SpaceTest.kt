package community.auroville.mantra.mixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Test 1, vocal space: the backing steps aside where the vocal sings, and nowhere else. */
class SpaceTest {
    private val rate = Mix.RATE
    private fun tone(hz: Double, amp: Double, seconds: Double) =
        FloatArray((seconds * rate).toInt() * 2) { amp.toFloat() * sin(2 * PI * hz * (it / 2) / rate).toFloat() }

    private fun render(s: Space, v: FloatArray, b: FloatArray, block: Int, vg: Float = 1f): FloatArray {
        val frames = b.size / 2
        val out = FloatArray(frames * 2)
        val buf = FloatArray(block * 2)
        var p = 0
        while (p < frames) {
            val n = minOf(block, frames - p)
            s.mixBlock(v, b, p, n, vg, 1f, 0, buf)
            System.arraycopy(buf, 0, out, 2 * p, 2 * n)
            p += n
        }
        return out
    }

    /** Level of a [hz] sine in the left channel over [from, to) seconds, by correlation, in dB. */
    private fun level(x: FloatArray, hz: Double, from: Double, to: Double): Double {
        var c = 0.0; var q = 0.0
        val a = (from * rate).toInt(); val e = (to * rate).toInt()
        for (i in a until e) { val w = 2 * PI * hz * i / rate; c += x[2 * i] * sin(w); q += x[2 * i] * kotlin.math.cos(w) }
        return 20 * log10(2 * sqrt(c * c + q * q) / (e - a))
    }

    @Test fun silentVocalLeavesBackingUntouched() {
        val b = FloatArray(rate * 2) { (((it * 7919L) % 2001) - 1000) / 1500f }   // broadband, both channels
        val out = render(Space().apply { strengthDb = 6.0 }, FloatArray(0), b, 333)
        var err = 0f
        for (i in b.indices) err = maxOf(err, kotlin.math.abs(out[i] - b[i]))
        assertTrue("reconstruction error $err", err < 1e-4f)
    }

    @Test fun ducksOnlyWhereTheVocalSings() {
        val secs = 2.0
        // backing: a 1 kHz line (where the vocal sings), a 3.5 kHz line (where it does not), a 60 Hz bass
        val b1 = tone(1000.0, 0.2, secs); val b2 = tone(3500.0, 0.2, secs); val b3 = tone(60.0, 0.2, secs)
        val b = FloatArray(b1.size) { b1[it] + b2[it] + b3[it] }
        val v = tone(1000.0, 0.3, secs)
        val s = Space().apply { strengthDb = 4.0 }
        val out = render(s, v, b, 1024, vg = 1f)
        // subtract the vocal to hear only what the backing became
        val back = FloatArray(out.size) { out[it] - v[it] }
        val at1k = level(back, 1000.0, 1.0, 1.9) - level(b1, 1000.0, 1.0, 1.9)
        val at3k5 = level(back, 3500.0, 1.0, 1.9) - level(b2, 3500.0, 1.0, 1.9)
        val at60 = level(back, 60.0, 1.0, 1.9) - level(b3, 60.0, 1.0, 1.9)
        // a lone sine shares its band with quiet neighbours (1-2-1 smoothing), so it gets most, never more, of 4 dB
        assertTrue("1 kHz under the vocal moved $at1k dB", at1k > -4.05 && at1k < -2.8)
        assertTrue("3.5 kHz away from the vocal moved $at3k5 dB", at3k5 > -0.5)
        assertTrue("60 Hz bass moved $at60 dB", kotlin.math.abs(at60) < 0.2)
        assertTrue(s.maxReductionDb >= -4.0 - 1e-9)
    }

    @Test fun strengthZeroIsTheOldMix() {
        val v = tone(440.0, 0.3, 0.5); val b = tone(220.0, 0.3, 0.5)
        val a = render(Space().apply { strengthDb = 0.0 }, v, b, 512)
        val ref = FloatArray(a.size); Mix.mixBlock(v, b, 0, a.size / 2, 1f, 1f, 0, ref)
        for (i in a.indices) assertEquals(ref[i], a[i], 0f)
    }

    @Test fun blockSizeDoesNotChangeTheResult() {
        val v = tone(800.0, 0.3, 1.0); val b = FloatArray(v.size) { (((it * 104729L) % 2001) - 1000) / 3000f }
        val a = render(Space().apply { strengthDb = 6.0 }, v, b, 100)
        val c = render(Space().apply { strengthDb = 6.0 }, v, b, 8192)
        for (i in a.indices) assertEquals(a[i], c[i], 1e-6f)
    }
}
