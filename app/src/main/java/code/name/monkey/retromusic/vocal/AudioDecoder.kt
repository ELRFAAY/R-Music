package code.name.monkey.retromusic.vocal

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Decodes any song to 44.1 kHz stereo 16-bit (interleaved) in memory. */
object AudioDecoder {
    const val RATE = 44100

    class Pcm(val samples: ShortArray, val frames: Int)

    fun decode(context: Context, uri: Uri, expectedMs: Long, isCancelled: () -> Boolean): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "no audio track" }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        var out = ShortArray(((expectedMs / 1000.0 + 5) * srcRate * 2).toInt().coerceAtLeast(1 shl 20))
        var outLen = 0   // in shorts, always stereo interleaved at the source rate
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ib, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                if (ob >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(ob)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val count = sb.remaining()
                        val frames = count / channels
                        if (outLen + frames * 2 > out.size) out = out.copyOf(maxOf(out.size * 3 / 2, outLen + frames * 2))
                        val tmp = ShortArray(count)
                        sb.get(tmp)
                        for (f in 0 until frames) {
                            val l = tmp[f * channels]
                            val r = if (channels > 1) tmp[f * channels + 1] else l
                            out[outLen++] = l
                            out[outLen++] = r
                        }
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
        val srcFrames = outLen / 2
        if (srcRate == RATE) return Pcm(out, srcFrames)
        // linear resample to 44.1 kHz
        val dstFrames = (srcFrames.toLong() * RATE / srcRate).toInt()
        val dst = ShortArray(dstFrames * 2)
        val ratio = srcRate.toDouble() / RATE
        for (i in 0 until dstFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(srcFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val fr = (pos - i0).toFloat()
            for (c in 0..1) {
                val a = out[i0 * 2 + c]
                val b = out[i1 * 2 + c]
                dst[i * 2 + c] = (a + (b - a) * fr).toInt().toShort()
            }
        }
        return Pcm(dst, dstFrames)
    }
}
