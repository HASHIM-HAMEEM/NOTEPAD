package com.hashim.notepad.recognition

data class InkPoint(val x: Float, val y: Float)
data class InkRecognitionCandidate(val text: String, val score: Float?, val language: String)

enum class RecognitionScript { LATIN, ARABIC }

object RecognitionCandidatePolicy {
    fun prefersLatin(strokes: List<List<InkPoint>>): Boolean {
        var rightward = 0f
        var leftward = 0f
        for (stroke in strokes) {
            if (stroke.size < 2) continue
            val movement = stroke.last().x - stroke.first().x
            if (movement > 0f) rightward += movement else leftward -= movement
        }
        return rightward > leftward
    }

    fun scriptOf(text: String): RecognitionScript? {
        for (character in text) {
            when (character.code) {
                in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F, in 0x1E00..0x1EFF ->
                    return RecognitionScript.LATIN
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0x08A0..0x08FF,
                in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> return RecognitionScript.ARABIC
            }
        }
        return null
    }

    fun bestCandidate(candidates: List<InkRecognitionCandidate>, preferLatin: Boolean): InkRecognitionCandidate? {
        if (candidates.isEmpty()) return null
        val scored = candidates.filter { it.score != null }
        if (scored.isNotEmpty()) return scored.maxByOrNull { it.score!! }
        val preferredScript = if (preferLatin) RecognitionScript.LATIN else RecognitionScript.ARABIC
        return candidates.firstOrNull { scriptOf(it.text) == preferredScript } ?: candidates.first()
    }

    fun directionFor(text: String): String = when (scriptOf(text)) {
        RecognitionScript.ARABIC -> "rtl"
        RecognitionScript.LATIN -> "ltr"
        null -> "auto"
    }
}
