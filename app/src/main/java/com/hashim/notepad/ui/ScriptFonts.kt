package com.hashim.notepad.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.hashim.notepad.text.ScriptTypefaceResolver

@Composable
internal fun rememberScriptFontFamily(text: String): FontFamily {
    val context = LocalContext.current
    val typeface = remember(text) { ScriptTypefaceResolver.forText(context, text) }
    return remember(typeface) { FontFamily(typeface) }
}
