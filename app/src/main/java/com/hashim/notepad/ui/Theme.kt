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
import androidx.compose.runtime.Immutable
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

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), SEPIA("Paper") }

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

/**
 * The design tokens every screen is built from (see the redesign brief): one neutral stack,
 * three ink levels, one accent. Material's ColorScheme is derived from these so stock
 * components fall in line, but new UI should read [NotePadTheme.tokens] directly.
 */
@Immutable
data class Tokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val page: Color,
    val pageInk: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val shadow: Color,
    val isDark: Boolean,
    /** Warm the rendered PDF bitmap toward the paper colour (Paper) or dim it (Dark). */
    val pageTint: Color?,
)

/** Light — ChatGPT (Taste Engine extraction, chatgpt.com): #FCFCFC main, #FFFFFF cards, #F3F3F3 secondary, 15 % black hairlines, #0D0D0D ink, #8F8F8F tertiary, #2C67C5 blue used only for links/selection. */
val LightTokens = Tokens(
    bg = Color(0xFFFCFCFC), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF3F3F3), border = Color(0x26000000),
    page = Color(0xFFFFFFFF), pageInk = Color(0xFF0D0D0D),
    ink = Color(0xFF0D0D0D), ink2 = Color(0xFF5D5D5D), ink3 = Color(0xFF8F8F8F),
    accent = Color(0xFF2C67C5), onAccent = Color.White, accentSoft = Color(0x1A2C67C5),
    shadow = Color(0x1A000000), isDark = false, pageTint = null,
)

/** Paper — Perplexity (Taste Engine extraction, perplexity.ai): #FCFCF9 base, #FFFFFF raised, #F5F5F5 subtle, #E8E8E8 soft, #252525 body ink, #0B2833 headings, teal accent; PDF pages warmed to the base paper. */
val PaperTokens = Tokens(
    bg = Color(0xFFFCFCF9), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF5F5F5), border = Color(0xFFE8E8E8),
    page = Color(0xFFFCFCF9), pageInk = Color(0xFF0B2833),
    ink = Color(0xFF252525), ink2 = Color(0xFF5E5E5A), ink3 = Color(0xFF8F8F8A),
    accent = Color(0xFF20808D), onAccent = Color.White, accentSoft = Color(0x1A20808D),
    shadow = Color(0x14000000), isDark = false, pageTint = Color(0xFFFCFCF9),
)

/** Dark — ChatGPT dark surfaces: #212121 main, #2F2F2F raised, #424242 tertiary, 15 % white hairlines, #ECECEC / #B4B4B4 / #8F8F8F ink, soft blue for links/selection. */
val DarkTokens = Tokens(
    bg = Color(0xFF212121), surface = Color(0xFF2F2F2F), surface2 = Color(0xFF424242), border = Color(0x26FFFFFF),
    page = Color(0xFF1E1E1E), pageInk = Color(0xFFC8C8C8),
    ink = Color(0xFFECECEC), ink2 = Color(0xFFB4B4B4), ink3 = Color(0xFF8F8F8F),
    accent = Color(0xFF7AB7FF), onAccent = Color(0xFF0D0D0D), accentSoft = Color(0x337AB7FF),
    shadow = Color(0x73000000), isDark = true, pageTint = Color(0xFFD8D8D8),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

/** Shorthand used by every screen: `val t = NotePadTheme.tokens`. */
object NotePadTheme {
    val tokens: Tokens @Composable @ReadOnlyComposable get() = LocalTokens.current
}

private fun Tokens.toScheme(): ColorScheme {
    val error = if (isDark) Color(0xFFF2A49E) else Color(0xFFB3261E)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = if (isDark) Color(0xFFBFE6EA) else Color(0xFF0B3A3F),
        secondary = ink2, onSecondary = bg, secondaryContainer = surface2, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = accentSoft, onTertiaryContainer = ink,
        background = bg, onBackground = ink,
        surface = surface, onSurface = ink,
        surfaceVariant = surface2, onSurfaceVariant = ink2,
        surfaceContainerLowest = if (isDark) Color(0xFF0E0E10) else Color.White, surfaceContainerLow = bg, surfaceContainer = surface,
        surfaceContainerHigh = surface2, surfaceContainerHighest = surface2,
        surfaceTint = Color.Transparent,
        outline = ink3, outlineVariant = border,
        error = error, onError = if (isDark) Color(0xFF3B0907) else Color.White,
        errorContainer = if (isDark) Color(0xFF4A1512) else Color(0xFFF9DEDC), onErrorContainer = if (isDark) Color(0xFFF9DEDC) else Color(0xFF410E0B),
        inverseSurface = ink, inverseOnSurface = bg, inversePrimary = accent,
        scrim = Color.Black,
    )
}

/* ------------------------------------------------------------------ shape / type / motion */

val NotePadShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val googleFontProvider = Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)
private val base = FontFamily(
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.Normal),
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.Medium),
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Inter"), googleFontProvider, FontWeight.Bold),
)
/** One sans family; hierarchy comes from weight and size, not a second typeface. */
val DisplayFamily = base
private val arabicTitle = FontFamily(
    Font(GoogleFont("Noto Naskh Arabic"), googleFontProvider, FontWeight.Normal),
    Font(GoogleFont("Noto Naskh Arabic"), googleFontProvider, FontWeight.Bold),
)

fun titleFontFamily(text: String): FontFamily =
    if (text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }) arabicTitle else DisplayFamily

/** Display titles are bold and tight; body copy calm; generous line height for Arabic script. */
val NotePadTypography = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
)

/** Motion tokens shared by every transition so the whole app moves with one personality. */
object Motion {
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val short = 150
    const val medium = 200
    const val long = 300
    const val press = 120
    const val release = 150
    /** Every tappable card/tile scales to this while pressed, so the whole app answers touch the same way. */
    const val pressScale = 0.94f
    const val pressAlpha = 0.72f
    const val chrome = 200
    const val shared = 300
}

/** Returns true when the active scheme is a dark one (so paper previews can pick contrasting defaults). */
val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f
private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun NotePadTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; else -> false }
    val tokens = when {
        mode == ThemeMode.SEPIA -> PaperTokens
        dark -> DarkTokens
        else -> LightTokens
    }
    val scheme = remember(tokens, dynamic, dark) {
        val own = tokens.toScheme()
        if (dynamic && android.os.Build.VERSION.SDK_INT >= 31 && mode != ThemeMode.SEPIA) {
            val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            own.copy(primary = dyn.primary, onPrimary = dyn.onPrimary, primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer)
        } else own
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
    CompositionLocalProvider(LocalThemeMode provides mode, LocalTokens provides tokens) {
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
