package com.imaginstudio.imagetools.pixellab.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class InnerShadowMaskResult(
    val width: Int,
    val height: Int,
    val alpha: IntArray
)

data class StrokeMaskResult(
    val width: Int,
    val height: Int,
    val pad: Int,
    val alpha: IntArray
)

object ContourMaskMath {

    /**
     * Builds a sharp, solid contour stroke alpha mask around non-transparent pixels
     * using exact 2D Euclidean Distance Transform (Felzenszwalb-Huttenlocher, O(W*H)).
     */
    fun computeContourStrokeAlpha(
        origAlpha: IntArray,
        sw: Int,
        sh: Int,
        pad: Int,
        radiusPx: Float
    ): StrokeMaskResult {
        val w = sw + pad * 2
        val h = sh + pad * 2
        val paddedOrig = IntArray(w * h)
        for (y in 0 until sh) {
            val srcOff = y * sw
            val dstOff = (y + pad) * w + pad
            System.arraycopy(origAlpha, srcOff, paddedOrig, dstOff, sw)
        }

        val inf = 1e9f
        val d1 = FloatArray(w * h)
        val maxLen = max(w, h)
        val fBuf = FloatArray(maxLen)
        val dBuf = FloatArray(maxLen)
        val vBuf = IntArray(maxLen)
        val zBuf = FloatArray(maxLen + 1)

        // Vertical EDT pass: core subject silhouette threshold at alpha >= 110
        for (x in 0 until w) {
            for (y in 0 until h) {
                fBuf[y] = if (paddedOrig[y * w + x] >= 110) 0f else inf
            }
            edt1D(fBuf, dBuf, vBuf, zBuf, h)
            for (y in 0 until h) {
                d1[y * w + x] = dBuf[y]
            }
        }

        // Horizontal EDT pass + razor-crisp solid contour ring synthesis
        val r = radiusPx.coerceAtLeast(0.85f)
        val aa = 0.55f
        val outerLimit = r + 0.5f
        val outerLimitSq = outerLimit * outerLimit
        val outAlpha = IntArray(w * h)

        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) fBuf[x] = d1[rowOff + x]
            edt1D(fBuf, dBuf, vBuf, zBuf, w)
            for (x in 0 until w) {
                val dSq = dBuf[x]
                if (dSq > outerLimitSq) {
                    outAlpha[rowOff + x] = 0
                } else {
                    val dist = sqrt(dSq)
                    val outerCov = ((r + 0.35f - dist) / aa).coerceIn(0f, 1f)
                    val origA = paddedOrig[rowOff + x] / 255f
                    val strokeCov = if (dSq >= 0.5f) {
                        // 100% solid contour outside the subject core (no washed-out edge)
                        outerCov
                    } else {
                        // Solid AA seam just inside translucent boundary, without flooding deep translucent interiors
                        outerCov * ((origA - 0.35f) / 0.65f).coerceIn(0.25f, 1f)
                    }
                    outAlpha[rowOff + x] = (strokeCov * 255f).roundToInt().coerceIn(0, 255)
                }
            }
        }

        return StrokeMaskResult(w, h, pad, outAlpha)
    }

    /**
     * Builds a contour inner shadow alpha mask strictly inside the subject alpha silhouette
     * using deterministic 3-pass O(W*H) sliding-window box blur (immune to Skia BlurMaskFilter quirks).
     */
    fun computeContourInnerShadowAlpha(
        origAlpha: IntArray,
        sw: Int,
        sh: Int,
        dx: Float,
        dy: Float,
        blur: Float
    ): InnerShadowMaskResult {
        val pad = ceil(blur * 1.8f + max(abs(dx), abs(dy)) + 4f).toInt().coerceIn(8, 128)
        val pw = sw + pad * 2
        val ph = sh + pad * 2
        val bufA = IntArray(pw * ph)
        val bufB = IntArray(pw * ph)

        // 1. Sample shifted silhouette smoothly & compute exterior shadow emitter
        for (py in 0 until ph) {
            val sy = (py - pad) - dy
            val sy0 = floor(sy).toInt()
            val ty = sy - sy0
            val rowOff = py * pw
            for (px in 0 until pw) {
                val sx = (px - pad) - dx
                val sx0 = floor(sx).toInt()
                val tx = sx - sx0
                val shiftedA = sampleAlphaBilinear(origAlpha, sw, sh, sx0, sy0, tx, ty)
                bufA[rowOff + px] = (255 - min(255, (shiftedA * 2.25f).roundToInt())).coerceIn(0, 255)
            }
        }

        // 2. Separable 3-pass box blur approximating true Gaussian blur in O(pw * ph)
        val rFloat = (blur * 0.52f).coerceAtLeast(0f)
        if (rFloat >= 0.05f) {
            if (rFloat < 1.0f) {
                val unblurred = bufA.copyOf()
                boxBlur3Pass(bufA, bufB, pw, ph, 1)
                val t = rFloat.coerceIn(0f, 1f)
                val invT = 1f - t
                for (i in bufA.indices) {
                    bufA[i] = (unblurred[i] * invT + bufA[i] * t).roundToInt().coerceIn(0, 255)
                }
            } else {
                val radius = rFloat.roundToInt().coerceIn(1, pad - 1)
                boxBlur3Pass(bufA, bufB, pw, ph, radius)
            }
        }

        // 3. Clip strictly to unshifted subject silhouette alpha
        val out = IntArray(sw * sh)
        for (y in 0 until sh) {
            val srcRow = (y + pad) * pw + pad
            val dstRow = y * sw
            for (x in 0 until sw) {
                val origA = origAlpha[dstRow + x]
                out[dstRow + x] = if (origA == 0) {
                    0
                } else {
                    ((bufA[srcRow + x] * origA + 127) / 255).coerceIn(0, 255)
                }
            }
        }
        return InnerShadowMaskResult(sw, sh, out)
    }

    private fun sampleAlphaBilinear(
        alpha: IntArray,
        w: Int,
        h: Int,
        x0: Int,
        y0: Int,
        tx: Float,
        ty: Float
    ): Float {
        if (x0 < -1 || x0 >= w || y0 < -1 || y0 >= h) return 0f
        fun at(x: Int, y: Int): Int {
            return if (x in 0 until w && y in 0 until h) alpha[y * w + x] else 0
        }
        val a00 = at(x0, y0)
        val a10 = at(x0 + 1, y0)
        val a01 = at(x0, y0 + 1)
        val a11 = at(x0 + 1, y0 + 1)
        val top = a00 + (a10 - a00) * tx
        val bot = a01 + (a11 - a01) * tx
        return top + (bot - top) * ty
    }

    private fun boxBlur3Pass(bufA: IntArray, bufB: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0 || w <= 0 || h <= 0) return
        val win = 2 * r + 1
        val half = win / 2
        repeat(3) {
            // Horizontal: bufA -> bufB
            for (y in 0 until h) {
                val rowOff = y * w
                var sum = 0
                for (dx in -r..r) {
                    val sx = dx.coerceIn(0, w - 1)
                    sum += bufA[rowOff + sx]
                }
                for (x in 0 until w) {
                    bufB[rowOff + x] = ((sum + half) / win).coerceIn(0, 255)
                    val outIdx = (x - r).coerceIn(0, w - 1)
                    val inIdx = (x + r + 1).coerceIn(0, w - 1)
                    sum += bufA[rowOff + inIdx] - bufA[rowOff + outIdx]
                }
            }
            // Vertical: bufB -> bufA
            for (x in 0 until w) {
                var sum = 0
                for (dy in -r..r) {
                    val sy = dy.coerceIn(0, h - 1)
                    sum += bufB[sy * w + x]
                }
                for (y in 0 until h) {
                    bufA[y * w + x] = ((sum + half) / win).coerceIn(0, 255)
                    val outIdx = (y - r).coerceIn(0, h - 1)
                    val inIdx = (y + r + 1).coerceIn(0, h - 1)
                    sum += bufB[inIdx * w + x] - bufB[outIdx * w + x]
                }
            }
        }
    }

    private fun edt1D(f: FloatArray, d: FloatArray, v: IntArray, z: FloatArray, n: Int) {
        if (n <= 0) return
        var k = 0
        v[0] = 0
        z[0] = -1e12f
        z[1] = 1e12f
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val vk = v[k]
                s = ((f[q] + q * q) - (f[vk] + vk * vk)) / (2f * (q - vk))
                if (s > z[k]) break
                k--
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e12f
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val vk = v[k]
            val dx = (q - vk).toFloat()
            d[q] = dx * dx + f[vk]
        }
    }
}
