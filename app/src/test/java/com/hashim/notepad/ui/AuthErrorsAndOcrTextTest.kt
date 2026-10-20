package com.hashim.notepad.ui

import com.hashim.notepad.data.OcrWord
import com.hashim.notepad.sync.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorsAndOcrTextTest {
    @Test
    fun invalidCredentialsBecomeActionableSentence() {
        val message = friendlyAuthError(HttpException(400, "Invalid login credentials"))
        assertTrue(message, message.startsWith("Wrong email or password"))
    }

    @Test
    fun existingAccountOnSignUpPointsToConnect() {
        val message = friendlyAuthError(HttpException(422, "User already registered"))
        assertTrue(message, "Connect existing account" in message)
    }

    @Test
    fun unknownServerCodeIsStillShown() {
        val message = friendlyAuthError(HttpException(400, "something odd"))
        assertEquals("Server replied: 400: something odd", message)
    }

    @Test
    fun offlineIsExplained() {
        assertTrue(friendlyAuthError(java.net.UnknownHostException("x")).startsWith("No connection"))
    }

    @Test
    fun ocrWordsJoinIntoLinesByBaseline() {
        fun w(i: Int, text: String, top: Float) = OcrWord("p", i, text, 0f, top, 10f, top + 10f, 0.9f)
        val text = ocrWordsAsText(listOf(w(0, "The", 0f), w(1, "quick", 1f), w(2, "fox", 20f), w(3, "", 20f)))
        assertEquals("The quick\nfox", text)
    }
}
