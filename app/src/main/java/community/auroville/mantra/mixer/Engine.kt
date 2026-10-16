package community.auroville.mantra.mixer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Plays the live mix (levels can move while it plays) and writes the same mix to a WAV file.
 * Playback and export run the same Space.mixBlock (vocal space) and the same limiter, so what is heard is what is exported.
 */
class Engine {
    @Volatile var vocal: Decoder.Track? = null
    @Volatile var backing: Decoder.Track? = null
    @Volatile var vocalGain = 1f
    @Volatile var backingGain = 1f
    @Volatile var vocalOffsetFrames = 0
    /** Vocal space: how far the backing steps aside where the vocal sings, dB (0 = off). */
    @Volatile var spaceDb = 3.0
    /** The playback's vocal space, read by the meter. */
    val space = Space()
    @Volatile var position = 0          // frames
    @Volatile var playing = false
        private set
    private var worker: Thread? = null

    val length: Int
        get() = Mix.mixFrames(vocal?.frames ?: 0, backing?.frames ?: 0, vocalOffsetFrames)

    fun play(onEnd: () -> Unit) {
        if (playing || length == 0) return
        playing = true
        worker = thread(name = "mix-playback") {
            val block = 2048
            val minBuf = AudioTrack.getMinBufferSize(Mix.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Mix.RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBuf, block * 8 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val buf = FloatArray(block * 2)
            val lim = Mix.Limiter()
            space.reset()
            track.play()
            var ended = false
            while (playing) {
                val len = length
                if (position >= len) { ended = true; break }
                val n = minOf(block, len - position)
                space.strengthDb = spaceDb
                space.mixBlock(vocal?.pcm ?: FloatArray(0), backing?.pcm ?: FloatArray(0), position, n,
                    vocalGain, backingGain, vocalOffsetFrames, buf)
                lim.process(buf, n)
                track.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                position += n
            }
            track.stop(); track.release()
            playing = false
            if (ended) { position = 0; onEnd() }
        }
    }

    fun pause() {
        playing = false
        worker?.join(500)
        worker = null
    }

    /** The whole mix, from the top, to [out] as 16-bit 48 kHz WAV. Returns the deepest limiter reduction in dB. */
    fun export(out: OutputStream, progress: (Float) -> Unit): Double {
        val len = length
        val block = 8192
        val buf = FloatArray(block * 2)
        val pcm = ByteArray(block * 4)
        val seed = intArrayOf(12345)
        val lim = Mix.Limiter()
        val sp = Space().apply { strengthDb = spaceDb }
        out.write(Mix.wavHeader(len))
        var p = 0
        val v = vocal?.pcm ?: FloatArray(0)
        val b = backing?.pcm ?: FloatArray(0)
        while (p < len) {
            val n = minOf(block, len - p)
            sp.mixBlock(v, b, p, n, vocalGain, backingGain, vocalOffsetFrames, buf)
            lim.process(buf, n)
            Mix.toPcm16(buf, n * 2, pcm, seed)
            out.write(pcm, 0, n * 4)
            p += n
            progress(p.toFloat() / len)
        }
        out.flush()
        return Mix.gainToDb(lim.maxReduction)
    }
}
