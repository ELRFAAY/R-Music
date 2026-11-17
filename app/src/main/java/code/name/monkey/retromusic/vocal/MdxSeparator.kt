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

    /**
     * Layout of [out]: [ch0.re, ch0.im, ch1.re, ch1.im][bin < dimF][frame].
     * Both channels go through ONE complex FFT (left = real part, right = imaginary part) and are
     * pulled apart afterwards: half the FFT work.
     */
    private fun stft(ch: Array<FloatArray>, out: FloatArray) {
        val xl = ch[0]
        val xr = ch[1]
        for (t in 0 until dimT) {
            val s0 = t * hop - nFft / 2
            for (i in 0 until nFft) {
                var p = s0 + i
                if (p < 0) p = -p
                if (p >= chunk) p = 2 * (chunk - 1) - p
                frameRe[i] = xl[p] * window[i]
                frameIm[i] = xr[p] * window[i]
            }
            fft.forward(frameRe, frameIm, specRe, specIm)
            for (f in 0 until dimF) {
                val g = if (f == 0) 0 else nFft - f
                val aRe = specRe[f]
                val aIm = specIm[f]
                val bRe = specRe[g]
                val bIm = -specIm[g]            // conj(Z[N-f])
                val lRe = (aRe + bRe) * 0.5     // left  = (a + b) / 2
                val lIm = (aIm + bIm) * 0.5
                val dRe = (aRe - bRe) * 0.5     // right = (a - b) / 2i
                val dIm = (aIm - bIm) * 0.5
                out[(0 * dimF + f) * dimT + t] = lRe.toFloat()
                out[(1 * dimF + f) * dimT + t] = lIm.toFloat()
                out[(2 * dimF + f) * dimT + t] = dIm.toFloat()
                out[(3 * dimF + f) * dimT + t] = (-dRe).toFloat()
            }
        }
    }

    private val accRe = DoubleArray(nFft)
    private val accIm = DoubleArray(nFft)

    /** Inverse of [stft] for both channels with one FFT per frame (left = real, right = imaginary). */
    private fun istft(flat: FloatArray, out: Array<FloatArray>) {
        val len = nFft + hop * (dimT - 1)
        val env = DoubleArray(len)
        for (t in 0 until dimT) for (i in 0 until nFft) env[t * hop + i] += window[i] * window[i]
        val sigL = DoubleArray(len)
        val sigR = DoubleArray(len)
        val mirrorEnd = nFft - nBins + 1
        for (t in 0 until dimT) {
            java.util.Arrays.fill(accRe, 0.0)
            java.util.Arrays.fill(accIm, 0.0)
            for (f in 0 until nBins) {
                if (f >= dimF) break
                val lre = flat[(0 * dimF + f) * dimT + t].toDouble()
                val lim = flat[(1 * dimF + f) * dimT + t].toDouble()
                val rre = flat[(2 * dimF + f) * dimT + t].toDouble()
                val rim = flat[(3 * dimF + f) * dimT + t].toDouble()
                // Z = XL + i*XR, then conj for the "inverse by forward FFT" trick
                accRe[f] = lre - rim
                accIm[f] = -(lim + rre)
                if (f in 1 until mirrorEnd) {
                    // Z[N-f] built from the Hermitian mirrors conj(XL[f]), conj(XR[f])
                    accRe[nFft - f] = lre + rim
                    accIm[nFft - f] = lim - rre
                }
            }
            fft.forward(accRe, accIm, specRe, specIm)
            for (i in 0 until nFft) {
                sigL[t * hop + i] += (specRe[i] / nFft) * window[i]
                sigR[t * hop + i] += (-specIm[i] / nFft) * window[i]
            }
        }
        for (c in 0..1) {
            val sig = if (c == 0) sigL else sigR
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
