package com.hashim.notepad.pdf

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-JVM page clean-up before Tesseract, tuned on synthetic Naskh/Nastaliq/Latin pages and simulated scans
 * (see docs/ocr-bench): deskew always; global Otsu binarisation only for scan-like pages (grey paper, noise),
 * because adaptive/forced binarisation hurts clean renders and Nastaliq.
 */
object OcrPreprocess {
    data class Plan(val skewDegrees: Float, val binarize: Boolean, val threshold: Int)

    /** Luminance 0..255 per pixel, row-major. */
    fun plan(lum: IntArray, width: Int, height: Int): Plan {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.coerceAtLeast(1)
        val threshold = otsu(hist, total)
        val background = backgroundLevel(hist, total)
        val scanLike = isScanLike(hist, total, background)
        val skew = estimateSkew(lum, width, height, threshold)
        return Plan(skew, scanLike, threshold)
    }

    fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0L
        for (i in 0 until 256) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0L
        var best = 0.0
        var bestMeanInk = 0.0
        var bestMeanPaper = 255.0
        var found = false
        for (i in 0 until 256) {
            wB += hist[i]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestMeanInk = mB; bestMeanPaper = mF; found = true }
        }
        // Otsu's split index hugs the smaller (ink) class on sparse pages; the midpoint between the two class
        // means is a safer cut for both ink detection and binarisation.
        return if (found) ((bestMeanInk + bestMeanPaper) / 2).roundToInt().coerceIn(1, 254) else 128
    }

    /** Median luminance of the brightest 60 % of pixels: paper white for renders (~255), grey for scans. */
    private fun backgroundLevel(hist: IntArray, total: Int): Int {
        var acc = 0L
        val target = (total * 0.7).toLong()
        for (i in 0 until 256) { acc += hist[i]; if (acc >= target) return i }
        return 255
    }

    /**
     * Rendered pages are almost entirely pure white with a tiny ink tail; photographed/scanned pages have a grey,
     * spread-out background. Treat as scan when the background is darker than 245 or fewer than 70 % of pixels sit
     * within 6 levels of the background.
     */
    private fun isScanLike(hist: IntArray, total: Int, background: Int): Boolean {
        if (background < 245) return true
        var near = 0L
        for (i in (background - 6).coerceAtLeast(0)..(background + 6).coerceAtMost(255)) near += hist[i]
        return near.toDouble() / total < 0.70
    }

    /**
     * Projection-profile deskew: the rotation that maximises the variance of per-row ink counts is the one that
     * aligns text lines. Works on a ≤ 400 px wide downsample over ±3° in 0.25° steps; returns 0 when the page has
     * too little ink to judge.
     */
    fun estimateSkew(lum: IntArray, width: Int, height: Int, threshold: Int): Float {
        val step = (width / 400).coerceAtLeast(1)
        val w = width / step
        val h = height / step
        if (w < 40 || h < 40) return 0f
        val ink = BooleanArray(w * h)
        var inkCount = 0
        for (y in 0 until h) for (x in 0 until w) {
            val dark = lum[(y * step) * width + x * step] < threshold
            ink[y * w + x] = dark
            if (dark) inkCount++
        }
        if (inkCount < w * h / 500) return 0f
        val cx = w / 2f
        val cy = h / 2f
        var bestAngle = 0f
        var bestScore = -1.0
        var angle = -3f
        while (angle <= 3.001f) {
            val rad = Math.toRadians(angle.toDouble())
            val s = sin(rad)
            val c = cos(rad)
            val rows = IntArray(h)
            for (y in 0 until h) for (x in 0 until w) {
                if (!ink[y * w + x]) continue
                val ry = (-(x - cx) * s + (y - cy) * c + cy).roundToInt()
                if (ry in 0 until h) rows[ry]++
            }
            val mean = inkCount.toDouble() / h
            var variance = 0.0
            for (r in rows) variance += (r - mean) * (r - mean)
            if (variance > bestScore * 1.02) { bestScore = variance; bestAngle = angle }
            angle += 0.25f
        }
        return if (abs(bestAngle) < 0.25f) 0f else bestAngle
    }
}
