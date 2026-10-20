package com.hashim.notepad.ui

import com.hashim.notepad.pdf.PdfSourceCache

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page
import com.hashim.notepad.data.PageImage
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.displayHeightPt
import com.hashim.notepad.data.displayWidthPt
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.PdfMarkKind
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.ink.InkTextures
import com.hashim.notepad.ink.PaperTexture
import com.hashim.notepad.ink.PageTemplateRenderer
import com.hashim.notepad.ink.StrokeStore
import com.hashim.notepad.ink.TextBlockPainter
import com.hashim.notepad.ink.drawStrokes
import com.hashim.notepad.pdf.PdfInvert
import com.hashim.notepad.pdf.PdfPageSource
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.hashim.notepad.pdf.PdfiumDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Small memory caches front the persistent WebP cache used by library covers. */
private object PreviewCache {
    val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    val composites = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    val strokes = LruCache<String, List<Stroke>>(64)
    val renderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create(InkTextures.textureStore) }
}

internal const val MAX_PDF_PREVIEW_WIDTH_PX = 3072

/**
 * Resolves a PDF page bitmap: memory cache → on-disk WebP → PDFium render. The render result is
 * returned immediately and compressed to disk in the background, so first paint never waits on
 * encoding. Timing is logged under `PdfTiming` so open latency can be measured on a device.
 */
internal suspend fun loadPdfPageBitmap(
    context: android.content.Context,
    repo: NotePadRepository,
    assetId: String,
    pageIndex: Int,
    renderWidth: Int,
): Bitmap? {
    val bitmapKey = "$assetId:$pageIndex:$renderWidth"
    PreviewCache.bitmaps.get(bitmapKey)?.let { return it }
    // The reader, its prefetch and the library cover often ask for the same rendition at once;
    // let one caller do the PDFium work and hand the result to the others.
    val mine = CompletableDeferred<Bitmap?>()
    val existing = inFlightPdfRenders.putIfAbsent(bitmapKey, mine)
    if (existing != null) return existing.await()
    try {
        val bitmap = loadPdfPageBitmapUncached(context, repo, assetId, pageIndex, renderWidth, bitmapKey)
        mine.complete(bitmap)
        return bitmap
    } catch (error: Throwable) {
        mine.completeExceptionally(error)
        throw error
    } finally {
        inFlightPdfRenders.remove(bitmapKey, mine)
    }
}

private val inFlightPdfRenders = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

private suspend fun loadPdfPageBitmapUncached(
    context: android.content.Context,
    repo: NotePadRepository,
    assetId: String,
    pageIndex: Int,
    renderWidth: Int,
    bitmapKey: String,
): Bitmap? {
    val asset = repo.asset(assetId) ?: return null
    val started = SystemClock.elapsedRealtime()
    val cacheFile = previewFile(context, assetId, pageIndex, renderWidth)
    val fromDisk = if (cacheFile.isFile) {
        withContext(Dispatchers.IO) { BitmapFactory.decodeFile(cacheFile.absolutePath) }
    } else null
    val bitmap = fromDisk ?: run {
        val rendered = withContext(PdfiumDispatcher.dispatcher) {
            PdfSourceCache.withSource(
                context,
                File(asset.localPath),
                com.hashim.notepad.pdf.PdfPasswordVault(context).read(assetId),
            ) { it.render(pageIndex, renderWidth) }
        }
        PreviewDiskWriter.persist(cacheFile, rendered)
        rendered
    }
    PreviewCache.bitmaps.put(bitmapKey, bitmap)
    Log.d(
        "PdfTiming",
        "page=$pageIndex width=$renderWidth source=${if (fromDisk != null) "disk" else "render"} ms=${SystemClock.elapsedRealtime() - started}",
    )
    return bitmap
}

/** Writes rendered previews to the disk cache off the critical path, one writer per file. */
private object PreviewDiskWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashSet<String>()

    fun persist(file: File, bitmap: Bitmap) {
        synchronized(inFlight) { if (!inFlight.add(file.path)) return }
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.part")
                FileOutputStream(temporary).use { output ->
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    bitmap.compress(format, 86, output)
                }
                if (!temporary.renameTo(file)) temporary.delete()
                Log.d("PdfTiming", "persist ${file.name} ms=${SystemClock.elapsedRealtime() - started}")
            } catch (error: Exception) {
                Log.w("PagePreview", "Unable to persist preview ${file.name}", error)
            } finally {
                synchronized(inFlight) { inFlight.remove(file.path) }
            }
        }
    }
}

/** Any already-decoded rendition of this page (e.g. the low-res prefetch) to show while the sharp one loads. */
private fun nearestCachedPdfBitmap(assetId: String, pageIndex: Int): Bitmap? {
    val prefix = "$assetId:$pageIndex:"
    return PreviewCache.bitmaps.snapshot().entries
        .filter { it.key.startsWith(prefix) }
        .maxByOrNull { it.value.width }
        ?.value
}

internal fun pdfPreviewRenderWidth(targetWidthPx: Int, cropScale: Float, renderScale: Float): Int =
    (targetWidthPx * cropScale.coerceAtLeast(1f) * renderScale.coerceIn(0.2f, 4f))
        .roundToInt()
        .coerceIn(420, MAX_PDF_PREVIEW_WIDTH_PX)

internal suspend fun precomposeReadPage(
    context: android.content.Context,
    repo: NotePadRepository,
    page: Page,
    width: Int,
    paperColor: Color,
    bookmarkColor: Color,
) = withContext(Dispatchers.Default) {
    val renderWidth = width.coerceIn(420, MAX_PDF_PREVIEW_WIDTH_PX)
    val paperArgb = paperColor.toArgbInt()
    val marks = repo.pdfMarks(page.notebookId).first().filter { it.pageId == page.id && !it.deleted }
    val key = "${page.id}:${page.updatedAt}:$renderWidth:$paperArgb:0:${marks.hashCode()}"
    if (PreviewCache.composites.get(key) != null) return@withContext

    val pageImages = repo.pageImages(page.id).first()
    val textBlocks = repo.textBlocks(page.id).first()
    val strokesKey = "${page.id}:${page.updatedAt}"
    val strokes = PreviewCache.strokes.get(strokesKey) ?: repo.loadStrokes(page.id)?.let {
        runCatching { StrokeStore.decode(it) }.getOrNull()
    }.orEmpty().also { PreviewCache.strokes.put(strokesKey, it) }
    val imageBitmaps = pageImages.associate { image ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.localPath, options)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 420) sample *= 2
        image.assetId to (BitmapFactory.decodeFile(
            image.localPath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    }
    val pdfBitmap = if (page.kind == PageKind.PDF && page.assetId != null) {
        val pageIndex = page.pdfPageIndex ?: 0
        val bitmapKey = "${page.assetId}:$pageIndex:$renderWidth"
        PreviewCache.bitmaps.get(bitmapKey) ?: try {
            loadPdfPageBitmap(context, repo, page.assetId, pageIndex, renderWidth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("PagePreview", "Unable to prefetch PDF page $pageIndex", error)
            null
        }
    } else null
    PreviewCache.composites.put(
        key,
        renderPreviewComposite(
            page,
            renderWidth,
            paperArgb,
            null,
            pdfBitmap,
            strokes,
            pageImages,
            imageBitmaps,
            textBlocks,
            marks,
            context,
            bookmarkColor.toArgbInt(),
        ),
    )
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
    renderScale: Float = 1f,
    precomposedOnly: Boolean = false,
) {
    val context = LocalContext.current
    var targetWidthPx by remember { mutableIntStateOf(0) }
    val renderWidth = pdfPreviewRenderWidth(targetWidthPx, cropScale, renderScale)
    val bitmapKey = page?.takeIf { it.kind == PageKind.PDF && it.assetId != null }?.let {
        "${it.assetId}:${it.pdfPageIndex ?: 0}:$renderWidth"
    }
    var bitmap by remember(page?.id, page?.pdfPageIndex) {
        mutableStateOf<Bitmap?>(
            bitmapKey?.let { PreviewCache.bitmaps.get(it) }
                ?: page?.takeIf { it.kind == PageKind.PDF && it.assetId != null }?.let { nearestCachedPdfBitmap(it.assetId!!, it.pdfPageIndex ?: 0) },
        )
    }
    val cropStartFraction = remember(bitmap, cropScale, cropToContent) {
        if (cropToContent) 0f else bitmap?.let { focusCropStartFraction(it, cropScale) } ?: 0f
    }
    var strokes by remember(page?.id, page?.updatedAt) { mutableStateOf<List<Stroke>>(page?.let { PreviewCache.strokes.get("${it.id}:${it.updatedAt}") } ?: emptyList()) }
    var pageImages by remember(page?.id, page?.updatedAt) { mutableStateOf(emptyList<PageImage>()) }
    val pageMarksFlow = remember(repo, page?.notebookId) {
        page?.notebookId?.let(repo::pdfMarks) ?: flowOf(emptyList())
    }
    val notebookMarks by pageMarksFlow.collectAsState(initial = emptyList())
    val marks = remember(notebookMarks, page?.id) {
        notebookMarks.filter { it.pageId == page?.id && !it.deleted }
    }
    var textBlocks by remember(page?.id, page?.updatedAt) { mutableStateOf(emptyList<TextBlock>()) }
    var imageBitmaps by remember(page?.id, page?.updatedAt) { mutableStateOf(emptyMap<String, Bitmap>()) }
    val contentBounds = remember(page?.id, page?.updatedAt, bitmap, strokes, pageImages, cropToContent) {
        if (cropToContent && page != null) pageContentBounds(page, bitmap, strokes, pageImages) else null
    }
    val compositeKey = page?.let {
        "${it.id}:${it.updatedAt}:$renderWidth:${paperColor.toArgbInt()}:${lineColor?.toArgbInt() ?: 0}:${marks.hashCode()}"
    }
    val bookmarkColor = MaterialTheme.colorScheme.primary.toArgbInt()
    var compositeBitmap by remember(compositeKey) {
        mutableStateOf(compositeKey?.let { PreviewCache.composites.get(it) })
    }
    var previewInputsLoaded by remember(page?.id, page?.updatedAt, renderWidth) { mutableStateOf(false) }

    LaunchedEffect(bitmapKey) {
        bitmapKey?.let { PreviewCache.bitmaps.get(it) }?.let { bitmap = it }
    }

    LaunchedEffect(page?.id, page?.updatedAt, renderEnabled, renderWidth, targetWidthPx) {
        if (targetWidthPx == 0) return@LaunchedEffect
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
        textBlocks = repo.textBlocks(p.id).first()
        if (PreviewCache.strokes.get(key) == null) {
            val list = withContext(Dispatchers.Default) {
                repo.loadStrokes(p.id)?.let { runCatching { StrokeStore.decode(it) }.getOrNull() } ?: emptyList()
            }
            PreviewCache.strokes.put(key, list)
            strokes = list
        }
        if (p.kind == PageKind.PDF && p.assetId != null && bitmapKey != null) {
            val cachedBitmap = PreviewCache.bitmaps.get(bitmapKey)
            if (cachedBitmap != null) {
                bitmap = cachedBitmap
            } else {
                if (bitmap == null) bitmap = nearestCachedPdfBitmap(p.assetId, p.pdfPageIndex ?: 0)
                val bmp = try {
                    loadPdfPageBitmap(context, repo, p.assetId, p.pdfPageIndex ?: 0, renderWidth)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("PagePreview", "Unable to load PDF preview for page ${p.id}", error)
                    return@LaunchedEffect
                } ?: return@LaunchedEffect
                bitmap = bmp
            }
        }
        previewInputsLoaded = true
    }

    LaunchedEffect(compositeKey, previewInputsLoaded, bitmap, strokes, pageImages, textBlocks, imageBitmaps, marks) {
        val p = page ?: return@LaunchedEffect
        val key = compositeKey ?: return@LaunchedEffect
        if (!previewInputsLoaded || !renderEnabled || targetWidthPx == 0) return@LaunchedEffect
        compositeBitmap = PreviewCache.composites.get(key)
        if (compositeBitmap != null) return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            renderPreviewComposite(
                page = p,
                width = renderWidth,
                paperArgb = paperColor.toArgbInt(),
                lineArgb = lineColor?.toArgbInt(),
                pdfBitmap = bitmap,
                strokes = strokes,
                pageImages = pageImages,
                imageBitmaps = imageBitmaps,
                textBlocks = textBlocks,
                marks = marks,
                context = context,
                bookmarkArgb = bookmarkColor,
            )
        }
        PreviewCache.composites.put(key, result)
        compositeBitmap = result
    }

    val ruling = lineColor ?: if (paperColor.luminance() < 0.5f) Color(0xFF3A3A40) else Color(0xFFCDD1D8)
    // Paper theme warms rendered PDF pages toward the paper colour so reading feels like print, not a screen.
    val pageTint = LocalTokens.current.pageTint?.takeIf { !LocalTokens.current.isDark }
    val measuredModifier = modifier.onSizeChanged { if (it.width > 0) targetWidthPx = it.width }
    if (precomposedOnly && page?.rotation == 0) {
        val previewBitmap = compositeBitmap ?: bitmap
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = measuredModifier,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
            )
        } else {
            Box(measuredModifier.background(paperColor))
        }
    } else Canvas(measuredModifier.background(paperColor)) {
        val p = page ?: return@Canvas
        val rotation = (p.rotation % 360 + 360) % 360
        val crop = if (rotation == 0) contentBounds?.let { fitCropToAspect(it, p.widthPt, p.heightPt, size.width / size.height) } else null
        val fitWholePage = cropToContent && crop == null
        val scale = when {
            crop != null -> minOf(size.width / crop.width, size.height / crop.height)
            fitWholePage -> size.height / p.heightPt
            rotation != 0 -> minOf(size.width / p.displayWidthPt, size.height / p.displayHeightPt)
            else -> size.width / p.widthPt * cropScale.coerceAtLeast(1f)
        }
        val tx = when {
            crop != null -> (size.width - crop.width * scale) / 2f - crop.left * scale
            fitWholePage -> (size.width - p.widthPt * scale) / 2f
            else -> -p.widthPt * cropStartFraction * scale
        }
        val ty = when {
            crop != null -> (size.height - crop.height * scale) / 2f - crop.top * scale
            rotation != 0 -> (size.height - p.displayHeightPt * scale) / 2f
            else -> 0f
        }
        val displayX = if (rotation == 0) tx else (size.width - p.displayWidthPt * scale) / 2f
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            val toScreen = Matrix().apply {
                setValues(when (rotation) {
                    90 -> floatArrayOf(0f, -scale, scale * p.heightPt + displayX, scale, 0f, ty, 0f, 0f, 1f)
                    180 -> floatArrayOf(-scale, 0f, scale * p.widthPt + displayX, 0f, -scale, scale * p.heightPt + ty, 0f, 0f, 1f)
                    270 -> floatArrayOf(0f, scale, displayX, -scale, 0f, scale * p.widthPt + ty, 0f, 0f, 1f)
                    else -> floatArrayOf(scale, 0f, tx, 0f, scale, ty, 0f, 0f, 1f)
                })
            }
            n.concat(toScreen)
            val composite = compositeBitmap
            if (composite != null) {
                n.drawBitmap(composite, null, RectF(0f, 0f, p.widthPt, p.heightPt), Paint(Paint.FILTER_BITMAP_FLAG))
            } else if (precomposedOnly) {
                bitmap?.let { b ->
                    val m = Matrix().apply { preScale(p.widthPt / b.width, p.heightPt / b.height) }
                    val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = when {
                            paperColor.luminance() < 0.5f -> PdfInvert.darkPaperFilter
                            pageTint != null -> PorterDuffColorFilter(pageTint.toArgbInt(), PorterDuff.Mode.MULTIPLY)
                            else -> null
                        }
                    }
                    n.drawBitmap(b, m, bitmapPaint)
                }
            } else {
                val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = PaperTexture.shader()
                    alpha = 40
                    colorFilter = PorterDuffColorFilter(paperColor.toArgbInt(), PorterDuff.Mode.MULTIPLY)
                }
                n.drawRect(0f, 0f, p.widthPt, p.heightPt, texturePaint)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ruling.toArgbInt(); strokeWidth = 0.8f }
                PageTemplateRenderer.draw(n, paint, p.widthPt, p.heightPt, p.kind)
                bitmap?.let { b ->
                    val m = Matrix().apply { preScale(p.widthPt / b.width, p.heightPt / b.height) }
                    val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = when {
                            paperColor.luminance() < 0.5f -> PdfInvert.darkPaperFilter
                            pageTint != null -> PorterDuffColorFilter(pageTint.toArgbInt(), PorterDuff.Mode.MULTIPLY)
                            else -> null
                        }
                    }
                    n.drawBitmap(b, m, bitmapPaint)
                }
                drawPreviewOverlays(n, p, strokes, pageImages, imageBitmaps, textBlocks, marks, paperColor.toArgbInt(), bookmarkColor, context)
            }
            n.restore()
        }
    }
}

private fun renderPreviewComposite(
    page: Page,
    width: Int,
    paperArgb: Int,
    lineArgb: Int?,
    pdfBitmap: Bitmap?,
    strokes: List<Stroke>,
    pageImages: List<PageImage>,
    imageBitmaps: Map<String, Bitmap>,
    textBlocks: List<TextBlock>,
    marks: List<PdfMark>,
    context: android.content.Context,
    bookmarkArgb: Int,
): Bitmap {
    val height = (width * page.heightPt / page.widthPt).roundToInt().coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bitmap ->
        val canvas = android.graphics.Canvas(bitmap)
        val scale = width / page.widthPt
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawColor(paperArgb)
        val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = PaperTexture.shader()
            alpha = 40
            colorFilter = PorterDuffColorFilter(paperArgb, PorterDuff.Mode.MULTIPLY)
        }
        canvas.drawRect(0f, 0f, page.widthPt, page.heightPt, texturePaint)
        PageTemplateRenderer.draw(
            canvas,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lineArgb ?: if (android.graphics.Color.luminance(paperArgb) < 0.5f) 0xFF3A3A40.toInt() else 0xFFCDD1D8.toInt()
                strokeWidth = 0.8f
            },
            page.widthPt,
            page.heightPt,
            page.kind,
        )
        pdfBitmap?.let {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                if (android.graphics.Color.luminance(paperArgb) < 0.5f) colorFilter = PdfInvert.darkPaperFilter
            }
            canvas.drawBitmap(it, null, RectF(0f, 0f, page.widthPt, page.heightPt), paint)
        }
        drawPreviewOverlays(canvas, page, strokes, pageImages, imageBitmaps, textBlocks, marks, paperArgb, bookmarkArgb, context)
        canvas.restore()
    }
}

private fun drawPreviewOverlays(
    canvas: android.graphics.Canvas,
    page: Page,
    strokes: List<Stroke>,
    pageImages: List<PageImage>,
    imageBitmaps: Map<String, Bitmap>,
    textBlocks: List<TextBlock>,
    marks: List<PdfMark>,
    paperArgb: Int,
    bookmarkArgb: Int,
    context: android.content.Context,
) {
    pageImages.forEach { image ->
        imageBitmaps[image.assetId]?.let {
            canvas.drawBitmap(it, null, RectF(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
    marks.forEach { mark ->
        val isHighlight = mark.kind == PdfMarkKind.HIGHLIGHT
        val darkPaper = android.graphics.Color.luminance(paperArgb) < 0.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isHighlight) PdfMarkGeometry.displayHighlightColor(mark.colorArgb, darkPaper) else mark.colorArgb
            strokeWidth = 1.5f
            style = if (isHighlight) Paint.Style.FILL else Paint.Style.STROKE
        }
        PdfMarkGeometry.quads(mark.quads).forEach { quad ->
            val left = quad.left
            val right = quad.right
            val top = page.heightPt - quad.bottom
            val bottom = page.heightPt - quad.top
            when (mark.kind) {
                PdfMarkKind.HIGHLIGHT -> canvas.drawRect(left, top, right, bottom, paint)
                PdfMarkKind.UNDERLINE -> canvas.drawLine(left, bottom, right, bottom, paint)
                PdfMarkKind.STRIKE -> canvas.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, paint)
                PdfMarkKind.SQUIGGLY -> {
                    val path = android.graphics.Path()
                    val step = 2f
                    var x = left
                    var up = true
                    path.moveTo(x, bottom)
                    while (x < right) {
                        x = minOf(right, x + step)
                        path.lineTo(x, bottom + if (up) -1.5f else 1.5f)
                        up = !up
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
    if (strokes.isNotEmpty()) {
        drawStrokes(
            canvas,
            strokes,
            PreviewCache.renderer,
            Matrix(),
            paperArgb,
            canvasHasPageTransform = true,
        )
    }
    textBlocks.forEach { TextBlockPainter.draw(canvas, it, false, paperArgb, context) }
    if (page.bookmarked) {
        val ribbonWidth = page.widthPt * 0.13f
        val ribbonHeight = page.heightPt * 0.12f
        val path = android.graphics.Path().apply {
            moveTo(page.widthPt, 0f)
            lineTo(page.widthPt - ribbonWidth, 0f)
            lineTo(page.widthPt, ribbonHeight)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bookmarkArgb })
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
