package code.name.monkey.retromusic.vocal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex FFT for any size made of small prime factors (7680 = 2^9 * 3 * 5, the MDX-Net window).
 * Recursive decimation in time, no allocation per call. Not thread safe (own scratch buffers).
 */
class MixedFft(val n: Int) {
    private val cosT = DoubleArray(n) { cos(-2.0 * PI * it / n) }
    private val sinT = DoubleArray(n) { sin(-2.0 * PI * it / n) }
    private val factors: IntArray
    private val tr = DoubleArray(8)
    private val ti = DoubleArray(8)

    init {
        val f = ArrayList<Int>()
        var m = n
        for (p in intArrayOf(2, 3, 5, 7)) {
            while (m % p == 0) { f.add(p); m /= p }
        }
        require(m == 1) { "unsupported FFT size $n" }
        factors = f.toIntArray()
    }

    /** Forward transform. [inRe]/[inIm] are read, the result lands in [outRe]/[outIm]. */
    fun forward(inRe: DoubleArray, inIm: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        rec(inRe, inIm, 0, 1, outRe, outIm, 0, n, 0)
    }

    private fun rec(
        iRe: DoubleArray, iIm: DoubleArray, iOff: Int, iStride: Int,
        oRe: DoubleArray, oIm: DoubleArray, oOff: Int, len: Int, fi: Int
    ) {
        if (len == 1) {
            oRe[oOff] = iRe[iOff]
            oIm[oOff] = iIm[iOff]
            return
        }
        val p = factors[fi]
        val m = len / p
        for (r in 0 until p) {
            rec(iRe, iIm, iOff + r * iStride, iStride * p, oRe, oIm, oOff + r * m, m, fi + 1)
        }
        val step = n / len      // twiddle step for size len
        val stepP = n / p       // twiddle step for size p
        for (k in 0 until m) {
            for (r in 0 until p) {
                val idx = oOff + r * m + k
                val w = (step * r * k) % n
                val c = cosT[w]
                val s = sinT[w]
                tr[r] = oRe[idx] * c - oIm[idx] * s
                ti[r] = oRe[idx] * s + oIm[idx] * c
            }
            for (q in 0 until p) {
                var sr = 0.0
                var si = 0.0
                for (r in 0 until p) {
                    val w = (stepP * ((r * q) % p)) % n
                    val c = cosT[w]
                    val s = sinT[w]
                    sr += tr[r] * c - ti[r] * s
                    si += tr[r] * s + ti[r] * c
                }
                val o = oOff + k + q * m
                oRe[o] = sr
                oIm[o] = si
            }
        }
    }
}
