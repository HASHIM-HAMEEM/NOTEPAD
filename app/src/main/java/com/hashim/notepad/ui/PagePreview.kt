package com.hashim.notepad.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageImage
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.ink.InkTextures
import com.hashim.notepad.ink.PaperTexture
import com.hashim.notepad.ink.PageTemplateRenderer
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.ink.drawStrokes
import com.hashim.notepad.pdf.PdfInvert
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfiumDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Small memory caches front the persistent WebP cache used by library covers. */
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
fun PagePreview(
    repo: NotePadRepository,
    page: Page?,
    paperColor: Color,
    modifier: Modifier = Modifier,
    lineColor: Color? = null,
    renderEnabled: Boolean = true,
    cropScale: Float = 1f,
    cropToContent: Boolean = false,
) {
    val context = LocalContext.current
    var targetWidthPx by remember { mutableIntStateOf(0) }
    val renderWidth = (targetWidthPx * cropScale.coerceAtLeast(1f)).roundToInt().coerceIn(420, 1800)
    val bitmapKey = page?.takeIf { it.kind == PageKind.PDF && it.assetId != null }?.let {
        "${it.assetId}:${it.pdfPageIndex ?: 0}:$renderWidth"
    }
    var bitmap by remember(bitmapKey) { mutableStateOf<Bitmap?>(bitmapKey?.let { PreviewCache.bitmaps.get(it) }) }
    val cropStartFraction = remember(bitmap, cropScale, cropToContent) {
        if (cropToContent) 0f else bitmap?.let { focusCropStartFraction(it, cropScale) } ?: 0f
    }
    var strokes by remember(page?.id, page?.updatedAt) { mutableStateOf<List<Stroke>>(page?.let { PreviewCache.strokes.get("${it.id}:${it.updatedAt}") } ?: emptyList()) }
    var pageImages by remember(page?.id, page?.updatedAt) { mutableStateOf(emptyList<PageImage>()) }
    var imageBitmaps by remember(page?.id, page?.updatedAt) { mutableStateOf(emptyMap<String, Bitmap>()) }
    val contentBounds = remember(page?.id, page?.updatedAt, bitmap, strokes, pageImages, cropToContent) {
        if (cropToContent && page != null) pageContentBounds(page, bitmap, strokes, pageImages) else null
    }

    LaunchedEffect(page?.id, page?.updatedAt, renderEnabled, renderWidth) {
        if (!renderEnabled || targetWidthPx == 0) return@LaunchedEffect
        val p = page ?: return@LaunchedEffect
        val key = "${p.id}:${p.updatedAt}"
        val imageRows = repo.pageImages(p.id).first()
        val decodedImages = withContext(Dispatchers.Default) {
            imageRows.associate { image ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.localPath, options)
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 420) sample *= 2
                image.assetId to (BitmapFactory.decodeFile(image.localPath, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            }
        }
        pageImages = imageRows
        imageBitmaps = decodedImages
        if (PreviewCache.strokes.get(key) == null) {
            val list = withContext(Dispatchers.Default) {
                repo.loadStrokes(p.id)?.let { runCatching { StrokeStore.decode(it) }.getOrNull() } ?: emptyList()
            }
            PreviewCache.strokes.put(key, list)
            strokes = list
        }
        if (p.kind == PageKind.PDF && p.assetId != null && bitmapKey != null && PreviewCache.bitmaps.get(bitmapKey) == null) {
            val asset = repo.asset(p.assetId) ?: return@LaunchedEffect
            val bmp = withContext(Dispatchers.Default) {
                runCatching {
                    val file = previewFile(context, p.assetId, p.pdfPageIndex ?: 0, renderWidth)
                    val cached = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                    cached ?: run {
                        val rendered = withContext(PdfiumDispatcher.dispatcher) {
                            PdfPageSource(context, File(asset.localPath), com.hashim.notepad.pdf.PdfPasswordVault(context).read(asset.id))
                                .use { it.render(p.pdfPageIndex ?: 0, renderWidth) }
                        }
                        file.parentFile?.mkdirs()
                        val temporary = File(file.parentFile, "${file.name}.part")
                        FileOutputStream(temporary).use { output ->
                            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                            rendered.compress(format, 86, output)
                        }
                        if (!temporary.renameTo(file)) temporary.delete()
                        rendered
                    }
                }.getOrNull()
            } ?: return@LaunchedEffect
            PreviewCache.bitmaps.put(bitmapKey, bmp)
            bitmap = bmp
        }
    }

    val ruling = lineColor ?: if (paperColor.luminance() < 0.5f) Color(0xFF3A3A40) else Color(0xFFCDD1D8)
    val bookmarkColor = MaterialTheme.colorScheme.primary.toArgbInt()
    Canvas(modifier.onSizeChanged { if (it.width > 0) targetWidthPx = it.width }.background(paperColor)) {
        val p = page ?: return@Canvas
        val crop = contentBounds?.let { fitCropToAspect(it, p.widthPt, p.heightPt, size.width / size.height) }
        val fitWholePage = cropToContent && crop == null
        val scale = when {
            crop != null -> minOf(size.width / crop.width, size.height / crop.height)
            fitWholePage -> size.height / p.heightPt
            else -> size.width / p.widthPt * cropScale.coerceAtLeast(1f)
        }
        val tx = when {
            crop != null -> (size.width - crop.width * scale) / 2f - crop.left * scale
            fitWholePage -> (size.width - p.widthPt * scale) / 2f
            else -> -p.widthPt * cropStartFraction * scale
        }
        val ty = if (crop != null) (size.height - crop.height * scale) / 2f - crop.top * scale else 0f
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = PaperTexture.shader()
                alpha = 40
                colorFilter = PorterDuffColorFilter(paperColor.toArgbInt(), PorterDuff.Mode.MULTIPLY)
            }
            n.drawRect(0f, 0f, size.width, size.height, texturePaint)
            n.save()
            val toScreen = Matrix().apply {
                setValues(floatArrayOf(scale, 0f, tx, 0f, scale, ty, 0f, 0f, 1f))
            }
            n.concat(toScreen)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ruling.toArgbInt(); strokeWidth = 0.8f }
            PageTemplateRenderer.draw(n, paint, p.widthPt, p.heightPt, p.kind)
            bitmap?.let { b ->
                val m = Matrix().apply { preScale(p.widthPt / b.width, p.heightPt / b.height) }
                val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    if (paperColor.luminance() < 0.5f) colorFilter = PdfInvert.darkPaperFilter
                }
                n.drawBitmap(b, m, bitmapPaint)
            }
            pageImages.forEach { image ->
                imageBitmaps[image.assetId]?.let {
                    n.drawBitmap(it, null, RectF(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            drawStrokes(
                n,
                strokes,
                PreviewCache.renderer,
                toScreen,
                paperColor.toArgbInt(),
                canvasHasPageTransform = true,
            )
            if (p.bookmarked) {
                val ribbonWidth = p.widthPt * 0.13f
                val ribbonHeight = p.heightPt * 0.12f
                val path = android.graphics.Path().apply {
                    moveTo(p.widthPt, 0f)
                    lineTo(p.widthPt - ribbonWidth, 0f)
                    lineTo(p.widthPt, ribbonHeight)
                    close()
                }
                n.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bookmarkColor })
            }
            n.restore()
        }
    }
}

private data class PreviewBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun pageContentBounds(page: Page, bitmap: Bitmap?, strokes: List<Stroke>, images: List<PageImage>): PreviewBounds? {
    var result = bitmap?.let { source ->
        val columns = 96
        val rows = 48
        var minX = columns
        var maxX = -1
        var minY = rows
        var maxY = -1
        val scanFraction = 0.20f
        val scanHeight = (source.height * scanFraction).toInt().coerceAtLeast(1)
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = ((column + 0.5f) * source.width / columns).toInt().coerceIn(0, source.width - 1)
            val y = ((row + 0.5f) * scanHeight / rows).toInt().coerceIn(0, source.height - 1)
            val color = source.getPixel(x, y)
            if (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color) < 690) {
                minX = minOf(minX, column)
                maxX = maxOf(maxX, column)
                minY = minOf(minY, row)
                maxY = maxOf(maxY, row)
            }
        }
        if (maxX < minX || maxY < minY) null else PreviewBounds(
            minX.toFloat() / columns * page.widthPt,
            minY.toFloat() / rows * page.heightPt * scanFraction,
            (maxX + 1f) / columns * page.widthPt,
            (maxY + 1f) / rows * page.heightPt * scanFraction,
        )
    }
    fun include(bounds: PreviewBounds) {
        result = PreviewBounds(
            minOf(result?.left ?: bounds.left, bounds.left),
            minOf(result?.top ?: bounds.top, bounds.top),
            maxOf(result?.right ?: bounds.right, bounds.right),
            maxOf(result?.bottom ?: bounds.bottom, bounds.bottom),
        )
    }
    strokes.forEach { stroke ->
        stroke.shape.computeBoundingBox()?.let { box -> include(PreviewBounds(box.xMin, box.yMin, box.xMax, box.yMax)) }
    }
    images.forEach { image -> include(PreviewBounds(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt)) }
    return result
}

private fun fitCropToAspect(bounds: PreviewBounds, pageWidth: Float, pageHeight: Float, aspect: Float): PreviewBounds? {
    if (bounds.width <= 0f || bounds.height <= 0f || aspect <= 0f) return null
    val marginX = maxOf(12f, bounds.width * 0.12f)
    val marginY = maxOf(12f, bounds.height * 0.18f)
    val maxWidth = minOf(pageWidth, pageHeight * aspect)
    var width = maxOf(bounds.width + marginX * 2f, (bounds.height + marginY * 2f) * aspect).coerceAtMost(maxWidth)
    var height = width / aspect
    if (height > pageHeight) {
        height = pageHeight
        width = height * aspect
    }
    val left = ((bounds.left + bounds.right - width) / 2f).coerceIn(0f, pageWidth - width)
    val top = ((bounds.top + bounds.bottom - height) / 2f).coerceIn(0f, pageHeight - height)
    return PreviewBounds(left, top, left + width, top + height)
}

private fun previewFile(context: android.content.Context, assetId: String, pageIndex: Int, width: Int): File {
    val key = "$assetId:$pageIndex:$width"
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(context.cacheDir, "page-previews/$digest.webp")
}

private fun focusCropStartFraction(bitmap: Bitmap, cropScale: Float): Float {
    val columns = 64
    val rows = 32
    val pixelCounts = IntArray(columns)
    val scanHeight = (bitmap.height * 0.45f).toInt().coerceAtLeast(1)
    for (column in 0 until columns) {
        val x = ((column + 0.5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
        for (row in 0 until rows) {
            val y = ((row + 0.5f) * scanHeight / rows).toInt().coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color) < 680) {
                pixelCounts[column]++
            }
        }
    }
    val visibleColumns = (columns / cropScale.coerceAtLeast(1f)).toInt().coerceIn(1, columns)
    val prefix = IntArray(columns + 1)
    pixelCounts.forEachIndexed { index, count -> prefix[index + 1] = prefix[index] + count }
    var bestStart = 0
    var bestCount = -1
    for (start in 0..columns - visibleColumns) {
        val count = prefix[start + visibleColumns] - prefix[start]
        if (count > bestCount) {
            bestCount = count
            bestStart = start
        }
    }
    return bestStart.toFloat() / columns
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
private fun Color.toArgbInt(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
