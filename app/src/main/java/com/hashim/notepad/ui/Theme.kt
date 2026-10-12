package com.hashim.notepad.ui

import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), SEPIA("Sepia") }

object Settings {
    private const val PREFS = "notepad"
    fun themeMode(context: Context): ThemeMode = runCatching { ThemeMode.valueOf(context.getSharedPreferences(PREFS, 0).getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    fun setThemeMode(context: Context, mode: ThemeMode) { context.getSharedPreferences(PREFS, 0).edit().putString("theme", mode.name).apply() }
    fun useDynamicColor(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("dynamic", false)
    fun setUseDynamicColor(context: Context, v: Boolean) { context.getSharedPreferences(PREFS, 0).edit().putBoolean("dynamic", v).apply() }
}

val LocalThemeMode = compositionLocalOf { ThemeMode.SYSTEM }

/** Paper colours a notebook can choose; stored in notebooks.cover_color as #RRGGBB. */
val PAPER_COLORS = listOf("#FFFFFF" to "White", "#FBF6E9" to "Cream", "#F1EFE6" to "Ivory", "#E8F0F2" to "Mist", "#1F1F22" to "Black")

/* ------------------------------------------------------------------ colour */

private object Ink {
    val blue = Color(0xFF2458C5)
    val blueDeep = Color(0xFF173C88)
    val blueSoft = Color(0xFFDCE6FB)
    val blueOnSoft = Color(0xFF0E2A62)
    val amber = Color(0xFFB7791F)
}

/** Light: warm paper-white surfaces, ink-blue accent, soft outlines. */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Ink.blue, onPrimary = Color.White, primaryContainer = Ink.blueSoft, onPrimaryContainer = Ink.blueOnSoft,
    secondary = Color(0xFF5B6473), onSecondary = Color.White, secondaryContainer = Color(0xFFE6E9EF), onSecondaryContainer = Color(0xFF1C2330),
    tertiary = Ink.amber, onTertiary = Color.White, tertiaryContainer = Color(0xFFFCEBCB), onTertiaryContainer = Color(0xFF3F2A00),
    background = Color(0xFFFAFAF7), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFAFAF7), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDEEEA), onSurfaceVariant = Color(0xFF585C63),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F4F1), surfaceContainer = Color(0xFFEFEFEB),
    surfaceContainerHigh = Color(0xFFE9E9E5), surfaceContainerHighest = Color(0xFFE2E3DF),
    outline = Color(0xFFB9BCC3), outlineVariant = Color(0xFFDFE1E5),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF2F3133), inverseOnSurface = Color(0xFFF1F1EE), inversePrimary = Color(0xFFADC6FF),
    scrim = Color.Black,
)

/** Dark: true neutral greys (no blue cast), slightly lifted accent for contrast. */
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF082B6A), primaryContainer = Color(0xFF1E3F85), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC0C7D4), onSecondary = Color(0xFF2A313D), secondaryContainer = Color(0xFF40475A), onSecondaryContainer = Color(0xFFDCE2F1),
    tertiary = Color(0xFFF3C77A), onTertiary = Color(0xFF402D00), tertiaryContainer = Color(0xFF5B4300), onTertiaryContainer = Color(0xFFFFE1A8),
    background = Color(0xFF121214), onBackground = Color(0xFFE5E5E3),
    surface = Color(0xFF121214), onSurface = Color(0xFFE5E5E3),
    surfaceVariant = Color(0xFF2A2B2E), onSurfaceVariant = Color(0xFFBDBFC5),
    surfaceContainerLowest = Color(0xFF0C0C0E), surfaceContainerLow = Color(0xFF1A1A1D), surfaceContainer = Color(0xFF1F1F22),
    surfaceContainerHigh = Color(0xFF29292D), surfaceContainerHighest = Color(0xFF343438),
    outline = Color(0xFF5E6168), outlineVariant = Color(0xFF34363B),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE5E5E3), inverseOnSurface = Color(0xFF2F3133), inversePrimary = Ink.blue,
    scrim = Color.Black,
)

/** Sepia: book-paper tones for long reading sessions. */
private val SepiaScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A4F2A), onPrimary = Color.White, primaryContainer = Color(0xFFEFDCC6), onPrimaryContainer = Color(0xFF2E1B0A),
    secondary = Color(0xFF6B5A47), onSecondary = Color.White, secondaryContainer = Color(0xFFEADFCB), onSecondaryContainer = Color(0xFF2A2117),
    tertiary = Color(0xFF5E6B3F), onTertiary = Color.White, tertiaryContainer = Color(0xFFE0E6C8), onTertiaryContainer = Color(0xFF1B2308),
    background = Color(0xFFF6EFE0), onBackground = Color(0xFF2A2117),
    surface = Color(0xFFF6EFE0), onSurface = Color(0xFF2A2117),
    surfaceVariant = Color(0xFFEADFCB), onSurfaceVariant = Color(0xFF5A4E3F),
    surfaceContainerLowest = Color(0xFFFFFBF2), surfaceContainerLow = Color(0xFFF1E8D6), surfaceContainer = Color(0xFFECE2CE),
    surfaceContainerHigh = Color(0xFFE6DCC6), surfaceContainerHighest = Color(0xFFE0D5BE),
    outline = Color(0xFFA2927B), outlineVariant = Color(0xFFD6CBB5),
    error = Color(0xFF9C3A2E), onError = Color.White, errorContainer = Color(0xFFF7DAD3), onErrorContainer = Color(0xFF3B0A05),
    inverseSurface = Color(0xFF3A3024), inverseOnSurface = Color(0xFFF6EFE0), inversePrimary = Color(0xFFE8BE95),
    scrim = Color.Black,
)

/* ------------------------------------------------------------------ shape / type / motion */

val NotePadShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = FontFamily.SansSerif

/** Tight, confident type: large display titles, calm body copy, generous line height for Arabic script. */
val NotePadTypography = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

/** Motion tokens shared by every transition so the whole app moves with one personality. */
object Motion {
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val short = 160
    const val medium = 280
    const val long = 420
}

/** Returns true when the active scheme is a dark one (so paper previews can pick contrasting defaults). */
val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f
private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun NotePadTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; else -> false }
    val scheme = when {
        mode == ThemeMode.SEPIA -> SepiaScheme
        dynamic && android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalThemeMode provides mode) {
        MaterialTheme(colorScheme = scheme, shapes = NotePadShapes, typography = NotePadTypography, content = content)
    }
}
