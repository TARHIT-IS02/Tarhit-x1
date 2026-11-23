package com.imaginstudio.imagetools.pixellab.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object LayerTransformMath {

    enum class AlignTarget {
        LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM
    }

    fun rotatedHalfExtents(width: Float, height: Float, rotationDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val cosA = abs(cos(rad)).toFloat()
        val sinA = abs(sin(rad)).toFloat()
        val halfW = (abs(width) * cosA + abs(height) * sinA) / 2f
        val halfH = (abs(width) * sinA + abs(height) * cosA) / 2f
        return halfW to halfH
    }

    fun alignLayerToCanvasBounds(
        curX: Float,
        curY: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        target: AlignTarget
    ): Pair<Float, Float> {
        val (halfW, halfH) = rotatedHalfExtents(width, height, rotationDeg)
        val cw = canvasWidth.toFloat()
        val ch = canvasHeight.toFloat()
        return when (target) {
            AlignTarget.LEFT -> halfW to curY
            AlignTarget.CENTER_H -> (cw / 2f) to curY
            AlignTarget.RIGHT -> (cw - halfW) to curY
            AlignTarget.TOP -> curX to halfH
            AlignTarget.CENTER_V -> curX to (ch / 2f)
            AlignTarget.BOTTOM -> curX to (ch - halfH)
        }
    }

    data class RelativeSizeResult(
        val widthPx: Float,
        val heightPx: Float,
        val widthPct: Int,
        val heightPct: Int
    )

    fun resizeFromWidthPct(
        widthPct: Int,
        curHeightPx: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        maintainRatio: Boolean,
        lockedAspect: Float,
        maxPct: Int = 300
    ): RelativeSizeResult {
        val cw = canvasWidth.coerceAtLeast(1).toFloat()
        val ch = canvasHeight.coerceAtLeast(1).toFloat()
        val clampedW = widthPct.coerceIn(1, maxPct)
        val newW = (cw * (clampedW / 100f)).coerceAtLeast(1f)
        val newH = if (maintainRatio) {
            (newW / lockedAspect.coerceAtLeast(0.01f)).coerceAtLeast(1f)
        } else {
            curHeightPx.coerceAtLeast(1f)
        }
        val newHPct = ((newH / ch) * 100f).roundToInt().coerceIn(1, maxPct)
        return RelativeSizeResult(newW, newH, clampedW, newHPct)
    }

    fun resizeFromHeightPct(
        heightPct: Int,
        curWidthPx: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        maintainRatio: Boolean,
        lockedAspect: Float,
        maxPct: Int = 300
    ): RelativeSizeResult {
        val cw = canvasWidth.coerceAtLeast(1).toFloat()
        val ch = canvasHeight.coerceAtLeast(1).toFloat()
        val clampedH = heightPct.coerceIn(1, maxPct)
        val newH = (ch * (clampedH / 100f)).coerceAtLeast(1f)
        val newW = if (maintainRatio) {
            (newH * lockedAspect.coerceAtLeast(0.01f)).coerceAtLeast(1f)
        } else {
            curWidthPx.coerceAtLeast(1f)
        }
        val newWPct = ((newW / cw) * 100f).roundToInt().coerceIn(1, maxPct)
        return RelativeSizeResult(newW, newH, newWPct, clampedH)
    }

    val ROTATE_3D_SNAP_TARGETS = floatArrayOf(
        -360f, -315f, -270f, -240f, -225f, -180f, -135f, -120f, -90f, -45f,
        0f,
        45f, 90f, 120f, 135f, 180f, 225f, 240f, 270f, 315f, 360f
    )

    fun snapAngleMagnetic(
        deg: Float,
        targets: FloatArray = ROTATE_3D_SNAP_TARGETS,
        thresholdDeg: Float = 4f
    ): Float {
        var bestTarget = deg
        var bestDist = thresholdDeg
        for (target in targets) {
            val d = abs(deg - target)
            if (d <= bestDist) {
                bestDist = d
                bestTarget = target
            }
        }
        return bestTarget
    }
}
