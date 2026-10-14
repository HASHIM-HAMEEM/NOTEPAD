package com.hashim.notepad.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.GoogleFont.Provider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hashim.notepad.R
import androidx.core.view.WindowCompat

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

/** Light: warm paper-white surfaces and terracotta ink. */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB4532A), onPrimary = Color.White, primaryContainer = Color(0xFFF6DCCD), onPrimaryContainer = Color(0xFF4A1C08),
    secondary = Color(0xFF6E655A), onSecondary = Color.White, secondaryContainer = Color(0xFFE8E1D7), onSecondaryContainer = Color(0xFF2B2620),
    tertiary = Color(0xFF8B6B40), onTertiary = Color.White, tertiaryContainer = Color(0xFFF1E2CB), onTertiaryContainer = Color(0xFF34230C),
    background = Color(0xFFF3EFE7), onBackground = Color(0xFF2B2620),
    surface = Color(0xFFFBF8F2), onSurface = Color(0xFF2B2620),
    surfaceVariant = Color(0xFFE8E1D4), onSurfaceVariant = Color(0xFF6B6258),
    surfaceContainerLowest = Color(0xFFFFFCF8), surfaceContainerLow = Color(0xFFF7F2E9), surfaceContainer = Color(0xFFEFE9DE),
    surfaceContainerHigh = Color(0xFFE8E1D4), surfaceContainerHighest = Color(0xFFDED6C9),
    outline = Color(0xFFB7AA99), outlineVariant = Color(0xFFE0D8CA),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF342F29), inverseOnSurface = Color(0xFFF7F2E9), inversePrimary = Color(0xFFE8AE90),
    scrim = Color.Black,
)

/** Dark: warm charcoal surfaces and a lifted terracotta accent. */
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFF0A47C), onPrimary = Color(0xFF4A1C08), primaryContainer = Color(0xFF68351F), onPrimaryContainer = Color(0xFFFFDBCA),
    secondary = Color(0xFFB8AE9F), onSecondary = Color(0xFF302B25), secondaryContainer = Color(0xFF4A4238), onSecondaryContainer = Color(0xFFE9DFD1),
    tertiary = Color(0xFFD5B37C), onTertiary = Color(0xFF39280F), tertiaryContainer = Color(0xFF55401F), onTertiaryContainer = Color(0xFFF2D8A8),
    background = Color(0xFF1B1916), onBackground = Color(0xFFEDE6DA),
    surface = Color(0xFF23201C), onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF34302A), onSurfaceVariant = Color(0xFFB8AE9F),
    surfaceContainerLowest = Color(0xFF171512), surfaceContainerLow = Color(0xFF25221E), surfaceContainer = Color(0xFF2B2823),
    surfaceContainerHigh = Color(0xFF34302A), surfaceContainerHighest = Color(0xFF3D3831),
    outline = Color(0xFF81776B), outlineVariant = Color(0xFF4B453D),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFEDE6DA), inverseOnSurface = Color(0xFF2B2620), inversePrimary = Color(0xFFB4532A),
    scrim = Color.Black,
)

/** Sepia: book-paper tones for long reading sessions. */
private val SepiaScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB4532A), onPrimary = Color.White, primaryContainer = Color(0xFFF0D9C8), onPrimaryContainer = Color(0xFF4A1C08),
    secondary = Color(0xFF6B5A47), onSecondary = Color.White, secondaryContainer = Color(0xFFEADFCB), onSecondaryContainer = Color(0xFF2A2117),
    tertiary = Color(0xFF5E6B3F), onTertiary = Color.White, tertiaryContainer = Color(0xFFE0E6C8), onTertiaryContainer = Color(0xFF1B2308),
    background = Color(0xFFF1E8D8), onBackground = Color(0xFF30261C),
    surface = Color(0xFFFAF3E6), onSurface = Color(0xFF30261C),
    surfaceVariant = Color(0xFFEADFCB), onSurfaceVariant = Color(0xFF6C5742),
    surfaceContainerLowest = Color(0xFFFFFAEF), surfaceContainerLow = Color(0xFFF5ECDC), surfaceContainer = Color(0xFFEDE2CF),
    surfaceContainerHigh = Color(0xFFE6DAC5), surfaceContainerHighest = Color(0xFFDED0B8),
    outline = Color(0xFFA2927B), outlineVariant = Color(0xFFD6C8B0),
    error = Color(0xFF9C3A2E), onError = Color.White, errorContainer = Color(0xFFF7DAD3), onErrorContainer = Color(0xFF3B0A05),
    inverseSurface = Color(0xFF3A3024), inverseOnSurface = Color(0xFFF6EFE0), inversePrimary = Color(0xFFE8BE95),
    scrim = Color.Black,
)

/* ------------------------------------------------------------------ shape / type / motion */

val NotePadShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val googleFontProvider = Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)
private val base = FontFamily(
    Font(GoogleFont("Nunito"), googleFontProvider, FontWeight.Normal),
    Font(GoogleFont("Nunito"), googleFontProvider, FontWeight.Medium),
    Font(GoogleFont("Nunito"), googleFontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Nunito"), googleFontProvider, FontWeight.Bold),
)
private val arabicTitle = FontFamily(
    Font(GoogleFont("Noto Naskh Arabic"), googleFontProvider, FontWeight.Normal),
    Font(GoogleFont("Noto Naskh Arabic"), googleFontProvider, FontWeight.Bold),
)

fun titleFontFamily(text: String): FontFamily =
    if (text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }) arabicTitle else base

/** Tight, confident type: large display titles, calm body copy, generous line height for Arabic script. */
val NotePadTypography = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.2).sp),
    headlineLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
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
    val view = LocalView.current
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; else -> false }
    val scheme = when {
        mode == ThemeMode.SEPIA -> SepiaScheme
        dynamic && android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    SideEffect {
        val activity = view.context.findActivity()
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val lightBars = !scheme.isDark
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
        }
    }
    CompositionLocalProvider(LocalThemeMode provides mode) {
        MaterialTheme(colorScheme = scheme, shapes = NotePadShapes, typography = NotePadTypography, content = content)
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
