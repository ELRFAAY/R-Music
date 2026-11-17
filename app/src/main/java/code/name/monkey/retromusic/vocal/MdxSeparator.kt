package code.name.monkey.retromusic.vocal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos

/** MDX-Net (Kim_Vocal_2) vocal extraction: STFT -> ONNX -> inverse STFT, chunk by chunk. */
class MdxSeparator(modelFile: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession(modelFile)
    private val inputName: String = session.inputNames.first()
    private val inputShape =
        (session.inputInfo[inputName]!!.info as ai.onnxruntime.TensorInfo).shape
    private val dimF: Int = inputShape[2].toInt()
    private val dimT: Int = inputShape[3].toInt()

    // Kim_Vocal_2 (dim_f 3072) uses a 7680 window, the lighter UVR_MDXNET_9482 (dim_f 2048) 6144.
    private val nFft = if (dimF <= 2048) 6144 else 7680
    private val hop = 1024
    private val nBins = nFft / 2 + 1
    private val chunk: Int = hop * (dimT - 1)
    private val trim = nFft / 2
    private val gen: Int = chunk - 2 * trim

    private val fft = MixedFft(nFft)
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2.0 * PI * it / nFft) }

    private fun createSession(modelFile: File): OrtSession {
        val opts = OrtSession.SessionOptions()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        opts.setIntraOpNumThreads(threads)
        // XNNPACK = faster CPU kernels for the convolutions; if this build cannot use it, carry on without
        try {
            opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        } catch (_: Throwable) {
        }
        return env.createSession(modelFile.absolutePath, opts)
    }

    /** Writes the vocals of [pcm] as a 16-bit stereo WAV. [progress] gets 0..100. */
    fun separate(pcm: AudioDecoder.Pcm, wav: File, isCancelled: () -> Boolean, progress: (Int) -> Unit) {
        val n = pcm.frames
        val left = FloatArray(n) { pcm.samples[it * 2] / 32768f }
        val right = FloatArray(n) { pcm.samples[it * 2 + 1] / 32768f }
        val pad = gen - (n % gen)
        val total = n + pad
        val chunks = total / gen
        val raf = RandomAccessFile(wav, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
        var written = 0L
        try {
            val inBuf = FloatArray(4 * dimF * dimT)
            val chBuf = Array(2) { FloatArray(chunk) }
            val outWave = Array(2) { FloatArray(chunk) }
            for (ci in 0 until chunks) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val start = ci * gen - trim   // position in the original signal of this window
                for (c in 0..1) {
                    val src = if (c == 0) left else right
                    val dst = chBuf[c]
                    for (i in 0 until chunk) {
                        val p = start + i
                        dst[i] = if (p in 0 until n) src[p] else 0f
                    }
                }
                stft(chBuf, inBuf)
                val tensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(inBuf), longArrayOf(1, 4, dimF.toLong(), dimT.toLong())
                )
                val result = session.run(mapOf(inputName to tensor))
                val outArr = result[0].value
                val flat = flatten(outArr, inBuf.size)
                result.close()
                tensor.close()
                istft(flat, outWave)
                // keep the middle part (drop trim on both sides)
                val valid = minOf(gen, n - ci * gen).coerceAtLeast(0)
                val bb = ByteBuffer.allocate(valid * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until valid) {
                    for (c in 0..1) {
                        val v = (outWave[c][trim + i] * 32767f).coerceIn(-32768f, 32767f)
                        bb.putShort(v.toInt().toShort())
                    }
                }
                raf.write(bb.array(), 0, valid * 4)
                written += valid * 4L
                progress(((ci + 1) * 100) / chunks)
            }
            // WAV header
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + written).toInt())
            h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray()); h.putInt(16)
            h.putShort(1); h.putShort(2); h.putInt(AudioDecoder.RATE); h.putInt(AudioDecoder.RATE * 4)
            h.putShort(4); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(written.toInt())
            raf.seek(0)
            raf.write(h.array())
        } finally {
            raf.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun flatten(value: Any, size: Int): FloatArray {
        val out = FloatArray(size)
        var idx = 0
        val a = value as Array<Array<Array<FloatArray>>>   // [1][4][dimF][dimT]
        for (c in 0 until 4) for (f in 0 until dimF) {
            val row = a[0][c][f]
            System.arraycopy(row, 0, out, idx, dimT)
            idx += dimT
        }
        return out
    }

    private val frameRe = DoubleArray(nFft)
    private val frameIm = DoubleArray(nFft)
    private val specRe = DoubleArray(nFft)
    private val specIm = DoubleArray(nFft)

    /** Layout of [out]: [ch0.re, ch0.im, ch1.re, ch1.im][bin < dimF][frame]. */
    private fun stft(ch: Array<FloatArray>, out: FloatArray) {
        for (c in 0..1) {
            val x = ch[c]
            for (t in 0 until dimT) {
                val s0 = t * hop - nFft / 2
                for (i in 0 until nFft) {
                    var p = s0 + i
                    if (p < 0) p = -p
                    if (p >= chunk) p = 2 * (chunk - 1) - p
                    frameRe[i] = x[p] * window[i]
                    frameIm[i] = 0.0
                }
                fft.forward(frameRe, frameIm, specRe, specIm)
                for (f in 0 until dimF) {
                    out[((c * 2) * dimF + f) * dimT + t] = specRe[f].toFloat()
                    out[((c * 2 + 1) * dimF + f) * dimT + t] = specIm[f].toFloat()
                }
            }
        }
    }

    private val accRe = DoubleArray(nFft)
    private val accIm = DoubleArray(nFft)

    private fun istft(flat: FloatArray, out: Array<FloatArray>) {
        val len = nFft + hop * (dimT - 1)
        val env = DoubleArray(len)
        for (t in 0 until dimT) for (i in 0 until nFft) env[t * hop + i] += window[i] * window[i]
        for (c in 0..1) {
            val sig = DoubleArray(len)
            for (t in 0 until dimT) {
                // conj trick: inverse = conj(fft(conj(X))) / n ; X is Hermitian so only bins < nBins matter
                for (f in 0 until nBins) {
                    val re = if (f < dimF) flat[((c * 2) * dimF + f) * dimT + t].toDouble() else 0.0
                    val im = if (f < dimF) flat[((c * 2 + 1) * dimF + f) * dimT + t].toDouble() else 0.0
                    accRe[f] = re
                    accIm[f] = -im
                    if (f in 1 until nFft - nBins + 1) {
                        accRe[nFft - f] = re
                        accIm[nFft - f] = im   // conj of the mirrored conj
                    }
                }
                fft.forward(accRe, accIm, specRe, specIm)
                for (i in 0 until nFft) {
                    sig[t * hop + i] += (specRe[i] / nFft) * window[i]
                }
            }
            val o = out[c]
            for (i in 0 until chunk) {
                val e = env[i + nFft / 2]
                o[i] = (sig[i + nFft / 2] / (if (e > 1e-11) e else 1.0)).toFloat()
            }
        }
    }

    override fun close() {
        session.close()
    }
}
