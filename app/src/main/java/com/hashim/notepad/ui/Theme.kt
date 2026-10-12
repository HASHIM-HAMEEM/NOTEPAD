package com.hashim.notepad.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), SEPIA("Sepia") }

object Settings {
    private const val PREFS = "notepad"
    fun themeMode(context: Context): ThemeMode = runCatching { ThemeMode.valueOf(context.getSharedPreferences(PREFS, 0).getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    fun setThemeMode(context: Context, mode: ThemeMode) { context.getSharedPreferences(PREFS, 0).edit().putString("theme", mode.name).apply() }
    fun useDynamicColor(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("dynamic", true)
    fun setUseDynamicColor(context: Context, v: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean("dynamic", v).apply() }
}

val LocalThemeMode = compositionLocalOf { ThemeMode.SYSTEM }

/** Paper colours a notebook can choose; stored in notebooks.cover_color as #RRGGBB. */
val PAPER_COLORS = listOf("#FFFFFF" to "White", "#FBF6E9" to "Cream", "#F1EFE6" to "Ivory", "#E8F0F2" to "Mist", "#1F1F22" to "Black")

private val SepiaScheme = lightColorScheme(
    primary = Color(0xFF7A5230), onPrimary = Color.White, primaryContainer = Color(0xFFEBD9C4), onPrimaryContainer = Color(0xFF2E1B0A),
    secondary = Color(0xFF6B5A47), surface = Color(0xFFF6EEDD), onSurface = Color(0xFF2A2117), background = Color(0xFFF6EEDD), onBackground = Color(0xFF2A2117),
    surfaceVariant = Color(0xFFEADFCB), onSurfaceVariant = Color(0xFF4F4435), outline = Color(0xFF857562), surfaceContainer = Color(0xFFEFE5D2),
)

@Composable
fun NotePadTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; else -> false }
    val scheme = when {
        mode == ThemeMode.SEPIA -> SepiaScheme
        dynamic && android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalThemeMode provides mode) { MaterialTheme(colorScheme = scheme, content = content) }
}
