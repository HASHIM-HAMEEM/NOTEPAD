package com.hashim.notepad.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

class OcrPreprocessTest {
    private fun page(width: Int, height: Int, background: Int, skewDeg: Double, noise: Int = 0): IntArray {
        val lum = IntArray(width * height) { background }
        val rnd = java.util.Random(7)
        val slope = tan(Math.toRadians(skewDeg))
        // 12 text lines, each 3 px thick, 80 % of width, drawn with the given skew
        for (line in 0 until 12) {
            val baseY = 40 + line * 30
            for (x in (width * 0.1).toInt() until (width * 0.9).toInt()) {
                val y = (baseY + (x - width / 2) * slope).toInt()
                for (t in 0 until 3) {
                    val yy = y + t
                    if (yy in 0 until height) lum[yy * width + x] = 20
                }
            }
        }
        if (noise > 0) for (i in lum.indices) lum[i] = (lum[i] + rnd.nextInt(2 * noise + 1) - noise).coerceIn(0, 255)
        return lum
    }

    @Test
    fun cleanRenderIsNotBinarised() {
        val plan = OcrPreprocess.plan(page(400, 420, 255, 0.0), 400, 420)
        assertFalse(plan.binarize)
        assertEquals(0f, plan.skewDegrees)
    }

    @Test
    fun greyNoisyScanIsBinarisedAndThresholdSeparatesInkFromPaper() {
        val plan = OcrPreprocess.plan(page(400, 420, 225, 0.0, noise = 18), 400, 420)
        assertTrue(plan.binarize)
        assertTrue("threshold ${plan.threshold}", plan.threshold in 40..200)
    }

    @Test
    fun skewIsRecoveredWithinAQuarterDegree() {
        for (deg in listOf(-2.0, -1.0, 1.5, 2.5)) {
            val plan = OcrPreprocess.plan(page(400, 420, 255, deg), 400, 420)
            assertTrue("expected ≈$deg got ${plan.skewDegrees}", abs(plan.skewDegrees - deg) <= 0.26)
        }
    }

    @Test
    fun blankPageHasNoSkew() {
        val lum = IntArray(400 * 420) { 255 }
        assertEquals(0f, OcrPreprocess.plan(lum, 400, 420).skewDegrees)
    }
}
