package com.hashim.notepad.text

import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

object ScriptTypefaceResolver {
    private const val NASKH = "fonts/NotoNaskhArabic.ttf"
    private const val NASTALIQ = "fonts/NotoNastaliqUrdu.ttf"
    private val urduSpecificCharacters = setOf(
        0x0679, 0x067E, 0x0686, 0x0688, 0x0691, 0x0698, 0x06A9,
        0x06AF, 0x06BA, 0x06BE, 0x06C1, 0x06D2, 0x06D3,
    )
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun forText(context: Context, text: String): Typeface {
        val urdu = text.any { it.code in urduSpecificCharacters }
        val arabic = text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }
        val asset = when {
            urdu -> NASTALIQ
            arabic -> NASKH
            else -> return Typeface.DEFAULT
        }
        return cache.getOrPut(asset) { Typeface.createFromAsset(context.assets, asset) }
    }
}
