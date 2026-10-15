package community.auroville.mantra.mixer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Test 1: the mechanism, on a desk, no Android. */
class MixTest {
    @Test fun faderEnds() {
        assertEquals(0f, Mix.dbToGain(Mix.MIN_DB.toDouble()), 0f)           // bottom of the fader = silence
        assertEquals(1f, Mix.dbToGain(0.0), 1e-6f)
        assertEquals(3.981f, Mix.dbToGain(12.0), 1e-3f)
        assertEquals(-6.0206, Mix.gainToDb(0.5f), 1e-3)
    }

    @Test fun monoBecomesStereo() {
        assertArrayEquals(floatArrayOf(1f, 1f, 2f, 2f), Mix.toStereo(floatArrayOf(1f, 2f), 1), 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 4f, 5f), Mix.toStereo(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 3), 0f)
    }

    @Test fun resampleKeepsDurationAndPitch() {
        val from = 44_100; val frames = from          // one second of 1 kHz
        val x = FloatArray(frames * 2) { sin(2 * PI * 1000 * (it / 2) / from).toFloat() }
        val y = Mix.resample(x, from, 48_000)
        assertEquals(48_000, y.size / 2)
        // count zero crossings of the left channel: 1 kHz for one second = about 2000
        var z = 0
        for (i in 1 until y.size / 2) if ((y[2 * i] >= 0) != (y[2 * i - 2] >= 0)) z++
        assertTrue("crossings $z", abs(z - 2000) <= 2)
        // the interpolated sine stays a sine: compare with the exact 48 kHz sine away from the edges
        var err = 0.0
        for (i in 100 until 47_900) err = maxOf(err, abs(y[2 * i] - sin(2 * PI * 1000 * i / 48_000.0)))
        assertTrue("max error $err", err < 0.01)
    }

    @Test fun mixAddsWithGainsAndOffset() {
        val v = floatArrayOf(1f, 1f, 1f, 1f)                 // 2 frames
        val b = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)   // 3 frames
        val out = FloatArray(8)
        Mix.mixBlock(v, b, 0, 4, 0.5f, 2f, 1, out)          // vocal one frame later
        assertArrayEquals(floatArrayOf(1f, 1f, 1.5f, 1.5f, 1.5f, 1.5f, 0f, 0f), out, 1e-6f)
        assertEquals(3, Mix.mixFrames(2, 3, 1))
        assertEquals(5, Mix.mixFrames(2, 3, 3))
    }

    @Test fun limiterNeverPassesTheCeiling() {
        val n = 48_000
        val buf = FloatArray(n * 2) { (if (it % 96 < 48) 3f else -3f) }   // +9.5 dBFS square wave
        val lim = Mix.Limiter(ceiling = 0.89f)
        lim.process(buf, n)
        assertTrue(buf.all { abs(it) <= 0.89f + 1e-6f })
        assertTrue(lim.maxReduction < 0.3f)
    }

    @Test fun wavHeaderIsWellFormed() {
        val h = Mix.wavHeader(48_000)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4)); assertEquals("data", String(h, 36, 4))
        val data = (h[40].toInt() and 0xff) or ((h[41].toInt() and 0xff) shl 8) or ((h[42].toInt() and 0xff) shl 16)
        assertEquals(48_000 * 4, data)
    }

    @Test fun pcm16Clips() {
        val out = ByteArray(4)
        Mix.toPcm16(floatArrayOf(2f, -2f), 2, out, intArrayOf(1))
        assertEquals(32767, (out[0].toInt() and 0xff) or (out[1].toInt() shl 8))
        assertEquals(-32768, (out[2].toInt() and 0xff) or (out[3].toInt() shl 8))
    }
}
