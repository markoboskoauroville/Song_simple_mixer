package community.auroville.mantra.mixer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Any audio file Android can open (mp3, wav, flac, m4a, ogg/opus) to interleaved stereo float at Mix.RATE. */
object Decoder {
    class Track(val name: String, val pcm: FloatArray, val sourceRate: Int) {
        val frames get() = pcm.size / 2
    }

    fun decode(ctx: Context, uri: Uri, name: String, progress: (Float) -> Unit = {}): Track {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("no audio in $name")
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val chunks = ArrayList<FloatArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                oi >= 0 -> {
                    val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val floats = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        FloatArray(info.size / 4).also { ob.asFloatBuffer().get(it) }
                    } else {
                        val sb = ob.asShortBuffer()
                        FloatArray(info.size / 2) { sb.get(it) / 32768f }
                    }
                    if (floats.isNotEmpty()) { chunks.add(floats); total += floats.size }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()

        val all = FloatArray(total)
        var p = 0
        for (c in chunks) { System.arraycopy(c, 0, all, p, c.size); p += c.size }
        progress(1f)
        return Track(name, Mix.resample(Mix.toStereo(all, channels), rate, Mix.RATE), rate)
    }
}
