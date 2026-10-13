package com.hashim.notepad.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionCandidatePolicyTest {
    @Test
    fun prefersLatinWhenStrokeSequenceMovesMostlyLeftToRight() {
        val strokes = listOf(
            listOf(InkPoint(10f, 0f), InkPoint(42f, 0f)),
            listOf(InkPoint(15f, 10f), InkPoint(35f, 10f)),
            listOf(InkPoint(9f, 20f), InkPoint(4f, 20f)),
        )

        assertTrue(RecognitionCandidatePolicy.prefersLatin(strokes))
    }

    @Test
    fun prefersArabicWhenStrokeSequenceMovesRightToLeftOrIsTied() {
        val rightToLeft = listOf(
            listOf(InkPoint(80f, 0f), InkPoint(52f, 0f)),
            listOf(InkPoint(70f, 10f), InkPoint(45f, 10f)),
        )
        val tied = listOf(
            listOf(InkPoint(0f, 0f), InkPoint(10f, 0f)),
            listOf(InkPoint(20f, 0f), InkPoint(10f, 0f)),
        )

        assertFalse(RecognitionCandidatePolicy.prefersLatin(rightToLeft))
        assertFalse(RecognitionCandidatePolicy.prefersLatin(tied))
    }

    @Test
    fun scoresTakePrecedenceOverWritingDirection() {
        val arabic = InkRecognitionCandidate("مرحبا", 0.8f, "ar")
        val latin = InkRecognitionCandidate("hello", 0.2f, "en")

        assertSame(arabic, RecognitionCandidatePolicy.bestCandidate(listOf(latin, arabic), preferLatin = true))
    }

    @Test
    fun unscoredCandidatesFollowWritingDirectionAndSetTextDirectionFromScript() {
        val arabic = InkRecognitionCandidate("مرحبا", null, "ar")
        val latin = InkRecognitionCandidate("hello", null, "en")

        assertSame(latin, RecognitionCandidatePolicy.bestCandidate(listOf(arabic, latin), preferLatin = true))
        assertSame(arabic, RecognitionCandidatePolicy.bestCandidate(listOf(latin, arabic), preferLatin = false))
        assertEquals("rtl", RecognitionCandidatePolicy.directionFor("مرحبا"))
        assertEquals("ltr", RecognitionCandidatePolicy.directionFor("hello"))
        assertEquals("auto", RecognitionCandidatePolicy.directionFor("123"))
    }
}
