package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.ink.InkTextures
import com.hashim.notepad.ink.PaperTexture
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.ink.drawStrokes
import com.hashim.notepad.pdf.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small in-memory caches so grids scroll without re-decoding. Keyed by page id + updated_at so edits refresh. */
private object PreviewCache {
    val bitmaps = LruCache<String, Bitmap>(24)
    val strokes = LruCache<String, List<Stroke>>(64)
    val renderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create(InkTextures.textureStore) }
}

/**
 * A faithful miniature of a page: paper colour, ruling, the PDF underlay and the user's ink.
 * Used for notebook covers and the page grid, so what you see in the library is what is on the page.
 */
@Composable
fun PagePreview(repo: NotePadRepository, page: Page?, paperColor: Color, modifier: Modifier = Modifier, lineColor: Color? = null) {
    val context = LocalContext.current
    var bitmap by remember(page?.id) { mutableStateOf<Bitmap?>(page?.let { PreviewCache.bitmaps.get(it.id) }) }
    var strokes by remember(page?.id, page?.updatedAt) { mutableStateOf<List<Stroke>>(page?.let { PreviewCache.strokes.get("${it.id}:${it.updatedAt}") } ?: emptyList()) }

    LaunchedEffect(page?.id, page?.updatedAt) {
        val p = page ?: return@LaunchedEffect
        val key = "${p.id}:${p.updatedAt}"
        if (PreviewCache.strokes.get(key) == null) {
            val list = withContext(Dispatchers.Default) {
                repo.loadStrokes(p.id)?.let { runCatching { StrokeStore.decode(it) }.getOrNull() } ?: emptyList()
            }
            PreviewCache.strokes.put(key, list)
            strokes = list
        }
        if (p.kind == PageKind.PDF && p.assetId != null && PreviewCache.bitmaps.get(p.id) == null) {
            val asset = repo.asset(p.assetId) ?: return@LaunchedEffect
            val bmp = withContext(Dispatchers.Default) {
                runCatching { PdfPageSource(context, File(asset.localPath)).use { it.render(p.pdfPageIndex ?: 0, 420) } }.getOrNull()
            } ?: return@LaunchedEffect
            PreviewCache.bitmaps.put(p.id, bmp)
            bitmap = bmp
        }
    }

    val ruling = lineColor ?: if (paperColor.luminance() < 0.5f) Color(0xFF3A3A40) else Color(0xFFCDD1D8)
    Canvas(modifier.background(paperColor)) {
        val p = page ?: return@Canvas
        val scale = size.width / p.widthPt
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = PaperTexture.shader()
                alpha = 40
                colorFilter = PorterDuffColorFilter(paperColor.toArgbInt(), PorterDuff.Mode.MULTIPLY)
            }
            n.drawRect(0f, 0f, size.width, size.height, texturePaint)
            n.save()
            n.scale(scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ruling.toArgbInt(); strokeWidth = 0.8f }
            val gap = 24f
            when (p.kind) {
                PageKind.LINED -> { var y = gap * 3; while (y < p.heightPt - gap) { n.drawLine(gap * 2, y, p.widthPt - gap * 2, y, paint); y += gap } }
                PageKind.GRID -> {
                    var y = gap; while (y < p.heightPt) { n.drawLine(0f, y, p.widthPt, y, paint); y += gap }
                    var x = gap; while (x < p.widthPt) { n.drawLine(x, 0f, x, p.heightPt, paint); x += gap }
                }
                else -> Unit
            }
            bitmap?.let { b ->
                val m = Matrix().apply { preScale(p.widthPt / b.width, p.heightPt / b.height) }
                n.drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val toScreen = Matrix().apply { setScale(scale, scale) }
            drawStrokes(n, strokes, PreviewCache.renderer, toScreen, paperColor.toArgbInt())
            n.restore()
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
private fun Color.toArgbInt(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
