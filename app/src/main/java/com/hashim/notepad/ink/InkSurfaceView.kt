package com.hashim.notepad.ink

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import java.util.concurrent.TimeUnit
import androidx.input.motionprediction.MotionEventPredictor
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.PdfMark
import com.hashim.notepad.data.PdfMarkKind
import com.hashim.notepad.pdf.PdfMarkGeometry
import com.hashim.notepad.data.PageImage
import com.hashim.notepad.data.TextBlock
import com.hashim.notepad.data.InkTimestamp
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.InProgressStroke
import android.view.InputDevice
import android.view.KeyEvent
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.ceil
import android.view.HapticFeedbackConstants
import com.hashim.notepad.pdf.PdfInvert

private const val HIGHLIGHTER_STRAIGHTEN_DELAY_MS = 450L
private const val SHAPE_STRAIGHTEN_DELAY_MS = 450L
private const val SHAPE_HOLD_RADIUS_DP = 6f
private const val SHAPE_SAMPLE_SPACING_PT = 2f
private const val POLYGON_CORNER_REPEATS = 6
private const val POLYGON_CORNER_INTERVAL_MS = 16L
private const val LASER_FADE_DURATION_MS = 1000L
private const val POINT_STROKE_UNIT_LENGTH_CM = 2.54f / 72f
private const val STYLUS_BUTTON_PRIMARY_KEYCODE = 308
private const val STYLUS_BUTTON_SECONDARY_KEYCODE = 309
private const val STYLUS_BUTTON_TERTIARY_KEYCODE = 310
private const val STYLUS_BUTTON_TAIL_KEYCODE = 311
private const val STYLUS_CLICK_TIMEOUT_MS = 400L

/**
 * Pen surface. World coordinates are PDF points on the page (origin top-left). A page bitmap
 * (PDF render) or template sits at the bottom, finished strokes are drawn by [CanvasStrokeRenderer],
 * and [InProgressStrokesView] draws the wet stroke on a front buffer on top.
 * Stylus draws; fingers pan/zoom unless [fingerDraws] is on.
 */
class InkSurfaceView(context: Context) : FrameLayout(context) {

    var brushSpec: BrushSpec = BrushSpec(InkTool.PEN, 0xFF1A1A1A.toInt(), 2.5f)
    var highlighterAlwaysStraight: Boolean = false
    var shapeStraightening: Boolean = true
    var fingerDraws: Boolean = false
    var primaryButtonAction: StylusButtonAction = StylusButtonAction.SWITCH_TO_ERASER
    var secondaryButtonAction: StylusButtonAction = StylusButtonAction.PREVIOUS_PEN
    var onSample: ((PointerSample) -> Unit)? = null
    var onDrawingActiveChanged: ((Boolean) -> Unit)? = null
    var onStrokesChanged: ((List<Stroke>) -> Unit)? = null
    private var pdfMarks: List<PdfMark> = emptyList()
    private var audioPlaybackPageId: String? = null
    private var audioPlaybackPositionMs: Long? = null
    private var audioItemTimes = emptyMap<String, Long>()
    private var audioLatestItemKey: String? = null
    var onAudioSeekRequested: ((Long) -> Unit)? = null
    var onPageImagesChanged: ((List<PageImage>) -> Unit)? = null
    var onTextBlocksChanged: ((List<TextBlock>) -> Unit)? = null
    var onUndoRedoChanged: ((Boolean, Boolean) -> Unit)? = null
    var onZoomChanged: ((Int) -> Unit)? = null
    var onStylusToolSwitch: ((StylusButtonAction) -> Unit)? = null
    /** Text tool: tap on empty space → (xPt, yPt, null); tap on a block → (xPt, yPt, block). */
    var onTextTap: ((Float, Float, TextBlock?) -> Unit)? = null
    var textBlocks: List<TextBlock> = emptyList()
        set(value) { field = value; strokeLayer.invalidate() }
    var selectedTextBlockId: String? = null
        set(value) { field = value; strokeLayer.invalidate() }

    var pageWidthPt = 595f; private set
    var pageHeightPt = 842f; private set
    private var pageRotation = 0
    private var pageKind = PageKind.BLANK
    private var hasPageGeometry = false

    private var rulerVisible = false
    private var rulerDarkTheme = false
    private var rulerInitialized = false
    private var rulerCx = 0f
    private var rulerCy = 0f
    private var rulerAngle = 0f
    private val rulerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rulerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulerHandleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rulerAnglePillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class RulerGesture { NONE, DRAG, ROTATE, TWO_FINGER }
    private var rulerGesture = RulerGesture.NONE
    private var rulerPrimaryPointerId = -1
    private var rulerSecondaryPointerId = -1
    private var rulerLastX = 0f
    private var rulerLastY = 0f
    private var rulerStartCx = 0f
    private var rulerStartCy = 0f
    private var rulerStartAngle = 0f
    private var rulerStartCentroidX = 0f
    private var rulerStartCentroidY = 0f
    private var rulerStartPointerAngle = 0f

    private var rulerSnapActive = false
    private var rulerSnapStartX = 0f
    private var rulerSnapStartY = 0f
    private var rulerSnapEndX = 0f
    private var rulerSnapEndY = 0f

    private val strokes = ArrayList<Stroke>()
    private var revealPageId = ""
    private val revealedTapeIndicesByPage = HashMap<String, MutableSet<Int>>()
    private var tapeRevealGesture = false
    private data class Snapshot(
        val strokes: List<Stroke>,
        val images: List<PageImage>,
        val textBlocks: List<TextBlock>,
        val externalUndo: (() -> Unit)? = null,
        val externalRedo: (() -> Unit)? = null,
    )
    private val history = ArrayList<Snapshot>()
    private val future = ArrayList<Snapshot>()
    private var eraseDirty = false

    /** Call before any mutation of [strokes]; keeps the last 100 states. */
    private fun snapshot(
        externalUndo: (() -> Unit)? = null,
        externalRedo: (() -> Unit)? = null,
    ) {
        history.add(Snapshot(ArrayList(strokes), ArrayList(pageImages), ArrayList(textBlocks), externalUndo, externalRedo))
        if (history.size > 100) history.removeAt(0)
        future.clear()
        notifyUndoRedoChanged()
    }

    private fun notifyUndoRedoChanged() {
        onUndoRedoChanged?.invoke(history.isNotEmpty(), future.isNotEmpty())
    }
    private val identity = Matrix()

    // ---- zoom box: a magnified writing strip at the bottom showing one "line" window of the page
    private var zoomEnabled = false
    private var zoomRtl = false
    private val zoomWindow = RectF(0f, 0f, 200f, ZOOM_LINE_PT)
    private val zoomToView = Matrix()
    private val viewToZoom = Matrix()
    private var zoomScale = 1f
    private var stripTop = 0f
    private var inStrip = false
    private var draggingWindow = false
    private var dragLastX = 0f
    private var dragLastY = 0f
    private val zoomFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E5EFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val zoomFillPaint = Paint().apply { color = 0x141E5EFF }
    private val stripBorderPaint = Paint().apply { color = 0xFF9AA0AA.toInt(); strokeWidth = 2f }

    fun setZoomBox(enabled: Boolean, rtl: Boolean) {
        if (enabled && !zoomEnabled) { zoomRtl = rtl; zoomWindow.set(0f, 0f, 200f, ZOOM_LINE_PT); if (rtl) zoomWindow.offsetTo(pageWidthPt - 200f, 0f) }
        zoomEnabled = enabled; zoomRtl = rtl
        updateMatrices()
    }
    /** Moves the window half a width along the writing direction (dir = +1) or back (dir = -1). */
    fun zoomShift(dir: Int) {
        val step = zoomWindow.width() / 2f * (if (zoomRtl) -dir else dir)
        zoomWindow.offset(step, 0f); clampZoomWindow(); updateMatrices()
    }
    fun zoomNewLine() {
        val x = if (zoomRtl) pageWidthPt - zoomWindow.width() else 0f
        zoomWindow.offsetTo(x, zoomWindow.top + zoomWindow.height()); clampZoomWindow(); updateMatrices()
    }
    fun zoomLineHeight(pt: Float) { zoomWindow.bottom = zoomWindow.top + pt.coerceIn(20f, 200f); clampZoomWindow(); updateMatrices() }

    private fun clampZoomWindow() {
        val w = zoomWindow.width(); val h = zoomWindow.height()
        val left = zoomWindow.left.coerceIn(0f, (pageWidthPt - w).coerceAtLeast(0f))
        val top = zoomWindow.top.coerceIn(0f, (pageHeightPt - h).coerceAtLeast(0f))
        zoomWindow.set(left, top, left + w, top + h)
    }

    private fun updateZoom() {
        if (!zoomEnabled || width == 0 || height == 0) return
        val stripH = height * ZOOM_STRIP_FRACTION
        stripTop = height - stripH
        zoomScale = stripH / zoomWindow.height()
        val w = width / zoomScale
        if (zoomRtl) zoomWindow.left = zoomWindow.right - w else zoomWindow.right = zoomWindow.left + w
        clampZoomWindow()
        zoomToView.reset(); zoomToView.postTranslate(-zoomWindow.left, -zoomWindow.top); zoomToView.postScale(zoomScale, zoomScale); zoomToView.postTranslate(0f, stripTop)
        zoomToView.invert(viewToZoom)
    }

    /** After a stroke written in the strip, slide the window along the writing direction or wrap to the next line. */
    private fun autoAdvance(stroke: Stroke) {
        val b = stroke.shape.computeBoundingBox() ?: return
        val margin = zoomWindow.width() * 0.12f
        if (!zoomRtl) {
            if (b.xMax > zoomWindow.right - margin) { if (zoomWindow.right >= pageWidthPt - 1f) zoomNewLine() else zoomShift(1) }
        } else {
            if (b.xMin < zoomWindow.left + margin) { if (zoomWindow.left <= 1f) zoomNewLine() else zoomShift(1) }
        }
    }

    private fun drawZoomStrip(canvas: Canvas) {
        if (!zoomEnabled) return
        canvas.save(); canvas.concat(pageToView)
        canvas.drawRect(zoomWindow, zoomFillPaint)
        canvas.restore()
        val vw = RectF(zoomWindow); pageToView.mapRect(vw); canvas.drawRect(vw, zoomFramePaint)
        canvas.save()
        canvas.clipRect(0f, stripTop, width.toFloat(), height.toFloat())
        canvas.drawColor(paperPaint.color)
        paperTexturePaint.colorFilter = PorterDuffColorFilter(paperPaint.color, PorterDuff.Mode.MULTIPLY)
        paperTexturePaint.alpha = 110
        canvas.drawRect(0f, stripTop, width.toFloat(), height.toFloat(), paperTexturePaint)
        paperTexturePaint.alpha = 255
        canvas.save(); canvas.concat(zoomToView)
        drawTemplate(canvas)
        canvas.restore()
        pageBitmap?.let { bmp ->
            val m = Matrix(zoomToView); m.preScale(pageWidthPt / bmp.width, pageHeightPt / bmp.height); canvas.drawBitmap(bmp, m, bitmapPaint)
        }
        canvas.save(); canvas.concat(zoomToView)
        drawPageImages(canvas)
        val zoomRevealedTapes = revealedTapeIndicesByPage[revealPageId].orEmpty()
        drawStrokesWithAudio(canvas, zoomToView, highlighterWet.takeIf { highlighterStartedInStrip }, zoomRevealedTapes)
        for (b in textBlocks) drawTextBlockWithAudio(canvas, b, false)
        canvas.restore()
        canvas.restore()
        canvas.drawLine(0f, stripTop, width.toFloat(), stripTop, stripBorderPaint)
    }
    private val pageToView = Matrix()
    private val viewToPage = Matrix()
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastReportedZoomPercent = Int.MIN_VALUE
    private var pageBitmap: Bitmap? = null
    private var imagePageId: String? = null
    private var pageImages = emptyList<PageImage>()
    private val imageBitmaps = HashMap<String, Bitmap>()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var currentStrokeId: InProgressStrokeId? = null
    private data class ShapeStrokeContext(val enabled: Boolean, val pageToInput: Matrix)
    private val shapeStrokeContexts = HashMap<InProgressStrokeId, ShapeStrokeContext>()
    private var highlighterWet: InProgressStroke? = null
    private data class StylusButtonPress(val downTime: Long, var strokeIntervened: Boolean)
    private val stylusButtonPresses = HashMap<Int, StylusButtonPress>()
    private val stylusButtonStates = HashMap<Int, Int>()
    private var highlighterStoredBrush: Brush? = null
    private var highlighterRenderBrush: Brush? = null
    private var highlighterFirstInput: StrokeInput? = null
    private var highlighterLastInput: StrokeInput? = null
    private var highlighterStraight = false
    private var highlighterHoldCancelled = false
    private var highlighterStartEventTime = 0L
    private var highlighterLastElapsed = -1L
    private var highlighterStartedInStrip = false
    private val highlighterLifecycle = HighlighterStrokeLifecycle()
    private var highlighterDownX = 0f
    private var highlighterDownY = 0f
    private var currentPointerId = -1
    private var erasing = false
    private var pendingStylusErase = false
    private var drawingInputActive = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var panning = false
    private var zoomAnimator: ValueAnimator? = null
    private val highlighterStraightenRunnable = Runnable {
        if (highlighterLifecycle.acceptsInput() && highlighterWet != null &&
            !highlighterStraight && !highlighterHoldCancelled
        ) {
            straightenHighlighterForHold()
        }
    }

    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            toggleDoubleTapZoom(event.x, event.y)
            return true
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean = seekAudioToItem(event.x, event.y)
    })

    // Lasso state (page coordinates)
    private val lassoPath = Path()
    private val lassoPoints = ArrayList<Float>()
    private var lassoActive = false
    private val selected = LinkedHashSet<Stroke>()
    private var selectedPageImageId: String? = null
    private var selectionBounds: RectF? = null
    private var movingSelection = false
    private var resizingSelection = false
    private var resizeHandle = -1
    private var resizeAnchor = FloatArray(2)
    private var resizeStartBounds: RectF? = null
    private var resizeStartImage: PageImage? = null
    private var imageGestureMoved = false
    private var resizeStartStrokes: List<Stroke> = emptyList()
    private var resizeStrokeIndices: List<Int> = emptyList()
    private var moveLastX = 0f
    private var moveLastY = 0f
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    var onSelectionBoundsChanged: ((RectF?) -> Unit)? = null
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E5EFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val selectionFill = Paint().apply { color = 0x141E5EFF; style = Paint.Style.FILL }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val selectionHandleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E5EFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }

    private val renderer = CanvasStrokeRenderer.create(InkTextures.textureStore)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val templatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9CDD6.toInt(); strokeWidth = 0f; style = Paint.Style.STROKE }
    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val paperTexturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = PaperTexture.shader() }
    private val paperViewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10000000 }
    private val keyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x08000000 }
    private val pageViewRect = RectF()
    private val pageClipPath = Path()
    private var deskColor = 0xFFF3EFE7.toInt()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private data class LaserTrail(val path: Path, var alpha: Float = 1f, var fadeAnimator: ValueAnimator? = null)
    private val laserTrails = ArrayList<LaserTrail>()
    private var activeLaserTrail: LaserTrail? = null
    private var activeLaserPointerId = -1
    private val laserHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 18f * resources.displayMetrics.density
    }
    private val laserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val laserOverlay = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            laserTrails.forEach { trail ->
                if (trail.alpha <= 0f) return@forEach
                laserHaloPaint.alpha = (trail.alpha * 0.16f * 255f).toInt()
                laserCorePaint.alpha = (trail.alpha * 255f).toInt()
                canvas.drawPath(trail.path, laserHaloPaint)
                canvas.drawPath(trail.path, laserCorePaint)
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newScale = (scale * d.scaleFactor).coerceIn(minScale(), minScale() * 20f)
            val f = newScale / scale
            panX = d.focusX - (d.focusX - panX) * f
            panY = d.focusY - (d.focusY - panY) * f
            scale = newScale
            clampPan(); updateMatrices(); return true
        }
    })

    private val strokeLayer = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(deskColor)
            pageViewRect.set(0f, 0f, pageWidthPt, pageHeightPt)
            pageToView.mapRect(pageViewRect)
            val corner = 12f * resources.displayMetrics.density
            shadowPaint.setShadowLayer(18f * resources.displayMetrics.density, 0f, 7f * resources.displayMetrics.density, 0x33000000)
            keyShadowPaint.setShadowLayer(7f * resources.displayMetrics.density, 0f, 2f * resources.displayMetrics.density, 0x26000000)
            canvas.drawRoundRect(pageViewRect, corner, corner, shadowPaint)
            canvas.drawRoundRect(pageViewRect, corner, corner, keyShadowPaint)
            paperViewPaint.color = paperPaint.color
            canvas.drawRoundRect(pageViewRect, corner, corner, paperViewPaint)
            pageClipPath.reset()
            pageClipPath.addRoundRect(pageViewRect, corner, corner, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(pageClipPath)
            canvas.save()
            canvas.concat(pageToView)
            paperTexturePaint.colorFilter = PorterDuffColorFilter(paperPaint.color, PorterDuff.Mode.MULTIPLY)
            canvas.drawRect(0f, 0f, pageWidthPt, pageHeightPt, paperTexturePaint)
            drawTemplate(canvas)
            canvas.restore()
            pageBitmap?.let { bmp ->
                val m = Matrix(pageToView)
                m.preScale(pageWidthPt / bmp.width, pageHeightPt / bmp.height)
                canvas.drawBitmap(bmp, m, bitmapPaint)
            }
            canvas.save(); canvas.concat(pageToView)
            drawPdfMarks(canvas)
            drawPageImages(canvas)
            val committedRevealedTapes = revealedTapeIndicesByPage[revealPageId].orEmpty()
            drawStrokesWithAudio(canvas, pageToView, highlighterWet.takeUnless { highlighterStartedInStrip }, committedRevealedTapes)
            canvas.restore()
            selectionBounds?.let { b ->
                canvas.save(); canvas.concat(pageToView)
                canvas.drawRect(b, selectionFill)
                canvas.save(); canvas.scale(1f, 1f); canvas.restore()
                canvas.restore()
                val vb = RectF(b); pageToView.mapRect(vb); canvas.drawRect(vb, lassoPaint)
                val radius = 5f * resources.displayMetrics.density
                val points = selectionHandlePoints(vb)
                points.forEach { point ->
                    canvas.drawCircle(point[0], point[1], radius, selectionHandlePaint)
                    canvas.drawCircle(point[0], point[1], radius, selectionHandleOutlinePaint)
                }
            }
            if (lassoActive) {
                canvas.save(); canvas.concat(pageToView)
                lassoPaint.strokeWidth = 2f / scale
                lassoPaint.pathEffect = DashPathEffect(floatArrayOf(10f / scale, 8f / scale), 0f)
                canvas.drawPath(lassoPath, lassoPaint)
                lassoPaint.strokeWidth = 2f
                lassoPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                canvas.restore()
            }
            if (textBlocks.isNotEmpty()) {
                canvas.save(); canvas.concat(pageToView)
                for (b in textBlocks) drawTextBlockWithAudio(canvas, b, b.id == selectedTextBlockId)
                canvas.restore()
            }
            canvas.restore()
            drawRuler(canvas)
            drawZoomStrip(canvas)
        }

    }

    private var wetLayerGeneration = 0L
    private var replaceWetLayerOnAttach = false
    private var wetLayer: InProgressStrokesView = createWetLayer(wetLayerGeneration)

    init {
        setWillNotDraw(true)
        updateRulerPaints()
        addView(strokeLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wetLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(laserOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun createWetLayer(generation: Long): InProgressStrokesView = InProgressStrokesView(context).apply {
        eagerInit()
        addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                val finishedStrokes = strokes.map { (id, stroke) ->
                    val shapeContext = shapeStrokeContexts.remove(id)
                    val shaped = if (shapeContext?.enabled == true) straightenHeldStroke(stroke, shapeContext) else stroke
                    if (brushSpec.tool == InkTool.TAPE) straightenTapeStroke(shaped) else shaped
                }
                snapshot()
                this@InkSurfaceView.strokes.addAll(finishedStrokes)
                val rendererCurrent = generation == wetLayerGeneration && isAttachedToWindow
                if (rendererCurrent) {
                    if (zoomEnabled && inStrip) finishedStrokes.lastOrNull()?.let { autoAdvance(it) }
                    strokeLayer.invalidate()
                    post {
                        if (generation == wetLayerGeneration && isAttachedToWindow) {
                            wetLayer.removeFinishedStrokes(strokes.keys)
                        }
                    }
                }
                notifyChanged()
            }
        })
    }

    @OptIn(androidx.ink.authoring.ExperimentalInkHandoffApi::class)
    override fun onDetachedFromWindow() {
        resetHighlighterStroke()
        val detachedLayer = wetLayer
        wetLayerGeneration++
        runCatching { detachedLayer.cancelUnfinishedStrokes() }
        runCatching { detachedLayer.flush(150, TimeUnit.MILLISECONDS, false) }
        runCatching { detachedLayer.sync(150, TimeUnit.MILLISECONDS) }
        detachedLayer.clearFinishedStrokesListeners()
        replaceWetLayerOnAttach = true
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        if (replaceWetLayerOnAttach) {
            val index = indexOfChild(wetLayer).coerceAtLeast(0)
            removeView(wetLayer)
            wetLayer = createWetLayer(wetLayerGeneration)
            addView(wetLayer, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            replaceWetLayerOnAttach = false
        }
        super.onAttachedToWindow()
    }

    private fun drawRuler(canvas: Canvas) {
        if (!rulerVisible || !rulerInitialized) return
        val halfLength = rulerLength() / 2f
        val halfThickness = RULER_THICKNESS_PT / 2f
        val top = rulerCy - halfThickness
        val bottom = rulerCy + halfThickness
        val degrees = rulerAngleDegrees()

        rulerOutlinePaint.strokeWidth = 1f / scale
        rulerTickPaint.strokeWidth = 1f / scale
        rulerHandleOutlinePaint.strokeWidth = 1f / scale
        rulerOutlinePaint.isAntiAlias = false
        rulerTickPaint.isAntiAlias = false

        canvas.save()
        canvas.concat(pageToView)
        canvas.rotate(degrees, rulerCx, rulerCy)
        canvas.drawRoundRect(
            rulerCx - halfLength,
            top,
            rulerCx + halfLength,
            bottom,
            RULER_CORNER_RADIUS_PT,
            RULER_CORNER_RADIUS_PT,
            rulerFillPaint,
        )
        canvas.drawRoundRect(
            rulerCx - halfLength,
            top,
            rulerCx + halfLength,
            bottom,
            RULER_CORNER_RADIUS_PT,
            RULER_CORNER_RADIUS_PT,
            rulerOutlinePaint,
        )

        var mark = 0
        var x = rulerCx - halfLength
        while (x <= rulerCx + halfLength + 0.1f) {
            val fullCentimeter = mark % 10 == 0
            val halfCentimeter = mark % 5 == 0
            val tickHeight = when {
                fullCentimeter -> RULER_FULL_TICK_PT
                halfCentimeter -> RULER_HALF_TICK_PT
                else -> RULER_MILLIMETER_TICK_PT
            }
            canvas.drawLine(x, top, x, top + tickHeight, rulerTickPaint)
            if (fullCentimeter) {
                rulerTextPaint.textSize = RULER_LABEL_TEXT_PT
                canvas.drawText((mark / 10).toString(), x, top + tickHeight + RULER_LABEL_BASELINE_PT, rulerTextPaint)
            }
            x += RULER_MILLIMETER_PT
            mark++
        }

        val handleX = rulerCx + halfLength
        canvas.drawCircle(handleX, rulerCy, RULER_HANDLE_RADIUS_PT, rulerHandlePaint)
        canvas.drawCircle(handleX, rulerCy, RULER_HANDLE_RADIUS_PT, rulerHandleOutlinePaint)
        rulerTextPaint.textAlign = Paint.Align.CENTER
        rulerTextPaint.textSize = 18f
        canvas.drawText("↻", handleX, rulerCy + 6f, rulerTextPaint)
        canvas.restore()

        canvas.save()
        canvas.concat(pageToView)
        rulerTextPaint.textSize = RULER_ANGLE_TEXT_PT
        val angleCenter = rulerPagePoint(rulerCx, rulerCy + 16f)
        val pill = RectF(angleCenter[0] - 24f, angleCenter[1] - 12f, angleCenter[0] + 24f, angleCenter[1] + 12f)
        canvas.drawRoundRect(pill, 12f, 12f, rulerAnglePillPaint)
        canvas.drawRoundRect(pill, 12f, 12f, rulerOutlinePaint)
        canvas.drawText("${degrees.roundToInt()}°", angleCenter[0], angleCenter[1] + 4f, rulerTextPaint)
        canvas.restore()
    }

    private fun rulerAngleDegrees(): Float {
        var degrees = Math.toDegrees(rulerAngle.toDouble()).toFloat()
        while (degrees <= -180f) degrees += 360f
        while (degrees > 180f) degrees -= 360f
        return degrees
    }

    private fun rulerPagePoint(localX: Float, localY: Float): FloatArray {
        val dx = localX - rulerCx
        val dy = localY - rulerCy
        val c = cos(rulerAngle)
        val s = sin(rulerAngle)
        return floatArrayOf(
            rulerCx + dx * c - dy * s,
            rulerCy + dx * s + dy * c,
        )
    }

    private fun rulerTopEdgeInView(): FloatArray {
        val halfLength = rulerLength() / 2f
        val halfThickness = RULER_THICKNESS_PT / 2f
        val a = rulerPagePoint(rulerCx - halfLength, rulerCy - halfThickness)
        val b = rulerPagePoint(rulerCx + halfLength, rulerCy - halfThickness)
        val edge = floatArrayOf(a[0], a[1], b[0], b[1])
        pageToView.mapPoints(edge)
        return edge
    }

    private fun drawTemplate(canvas: Canvas) {
        PageTemplateRenderer.draw(canvas, templatePaint, pageWidthPt, pageHeightPt, pageKind)
    }

    private fun straightenHeldStroke(stroke: Stroke, context: ShapeStrokeContext): Stroke {
        val inputBatch = stroke.inputs
        if (inputBatch.size < 2) return stroke
        val inputs = List(inputBatch.size) { inputBatch[it] }
        val lastInput = inputs[inputs.lastIndex]
        val anchor = floatArrayOf(lastInput.x, lastInput.y)
        context.pageToInput.mapPoints(anchor)
        val holdRadius = SHAPE_HOLD_RADIUS_DP * resources.displayMetrics.density
        var holdStart = inputs.lastIndex
        while (holdStart > 0) {
            val candidate = inputs[holdStart - 1]
            val point = floatArrayOf(candidate.x, candidate.y)
            context.pageToInput.mapPoints(point)
            if (hypot(point[0] - anchor[0], point[1] - anchor[1]) > holdRadius) break
            holdStart--
        }
        if (lastInput.elapsedTimeMillis - inputs[holdStart].elapsedTimeMillis < SHAPE_STRAIGHTEN_DELAY_MS) {
            return stroke
        }

        val drawingInputs = (0..holdStart).map { inputs[it] }
        val shape = ShapeRecognizer.recognize(
            drawingInputs.map { it.x }.toFloatArray(),
            drawingInputs.map { it.y }.toFloatArray(),
        ) ?: return stroke
        val generatedPoints = shapePoints(shape)
        if (generatedPoints.size < 2) return stroke

        val pressures = inputs.map { it.pressure }.sorted()
        val pressure = if (pressures.size % 2 == 0) {
            (pressures[pressures.size / 2 - 1] + pressures[pressures.size / 2]) / 2f
        } else {
            pressures[pressures.size / 2]
        }
        val firstInput = inputs[0]
        val batch = MutableStrokeInputBatch()
        var elapsedTimeMillis = firstInput.elapsedTimeMillis
        var previousPoint: Shape.Point? = null
        generatedPoints.forEach { point ->
            elapsedTimeMillis += if (point == previousPoint) POLYGON_CORNER_INTERVAL_MS else 1L
            batch.add(
                StrokeInput.create(
                    point.x,
                    point.y,
                    elapsedTimeMillis,
                    firstInput.toolType,
                    firstInput.strokeUnitLengthCm,
                    pressure,
                    firstInput.tiltRadians,
                    firstInput.orientationRadians,
                ),
            )
            previousPoint = point
        }
        return Stroke(stroke.brush, batch)
    }

    private fun straightenTapeStroke(stroke: Stroke): Stroke {
        if (stroke.inputs.size < 2) return stroke
        val first = stroke.inputs[0]
        val last = stroke.inputs[stroke.inputs.size - 1]
        val dx = last.x - first.x
        val dy = last.y - first.y
        val angle = abs(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())))
        val endY = if (angle <= 8.0 || abs(angle - 180.0) <= 8.0) first.y else last.y
        val batch = MutableStrokeInputBatch()
        batch.add(first)
        batch.add(
            StrokeInput.create(
                last.x,
                endY,
                max(first.elapsedTimeMillis + 1L, last.elapsedTimeMillis),
                last.toolType,
                last.strokeUnitLengthCm,
                last.pressure,
                last.tiltRadians,
                last.orientationRadians,
            ),
        )
        return Stroke(stroke.brush, batch)
    }

    private fun shapePoints(shape: Shape): List<Shape.Point> = when (shape) {
        is Shape.Line -> sampleSegment(
            Shape.Point(shape.x1, shape.y1),
            Shape.Point(shape.x2, shape.y2),
        )
        is Shape.Polygon -> {
            if (shape.points.size < 3) {
                emptyList()
            } else {
                buildList {
                    shape.points.indices.forEach { index ->
                        val start = shape.points[index]
                        val end = shape.points[(index + 1) % shape.points.size]
                        add(start)
                        repeat(POLYGON_CORNER_REPEATS) { add(start) }
                        addAll(sampleSegment(start, end).drop(1).dropLast(1))
                    }
                    add(shape.points.first())
                    repeat(POLYGON_CORNER_REPEATS) { add(shape.points.first()) }
                }
            }
        }
        is Shape.Ellipse -> {
            val circumference = 2f * Math.PI.toFloat() * kotlin.math.sqrt(
                (shape.rx * shape.rx + shape.ry * shape.ry) / 2f,
            )
            val steps = ceil(circumference / SHAPE_SAMPLE_SPACING_PT).toInt().coerceAtLeast(12)
            (0..steps).map { index ->
                val angle = 2f * Math.PI.toFloat() * index / steps
                Shape.Point(
                    shape.cx + shape.rx * kotlin.math.cos(angle),
                    shape.cy + shape.ry * kotlin.math.sin(angle),
                )
            }
        }
    }

    private fun sampleSegment(start: Shape.Point, end: Shape.Point): List<Shape.Point> {
        val length = hypot(end.x - start.x, end.y - start.y)
        val steps = ceil(length / SHAPE_SAMPLE_SPACING_PT).toInt().coerceAtLeast(1)
        return (0..steps).map { index ->
            val fraction = index.toFloat() / steps
            Shape.Point(
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction,
            )
        }
    }

    fun setPageGeometry(widthPt: Float, heightPt: Float, kind: PageKind) {
        pageWidthPt = widthPt; pageHeightPt = heightPt; pageKind = kind
        hasPageGeometry = true
        rulerInitialized = false
        fitToWidth()
        placeRulerIfNeeded()
    }

    fun setPageRotation(rotation: Int) {
        pageRotation = ((rotation % 360) + 360) % 360
        fitToWidth()
        strokeLayer.invalidate()
    }

    fun setPaperColor(argb: Int) {
        paperPaint.color = argb
        bitmapPaint.colorFilter = if (android.graphics.Color.luminance(argb) < 0.5f) PdfInvert.darkPaperFilter else null
        templatePaint.color = if (argb == 0xFF1F1F22.toInt()) 0xFF3A3A40.toInt() else 0xFFC9CDD6.toInt()
        strokeLayer.invalidate()
    }
    fun setDeskColor(argb: Int) { deskColor = argb; strokeLayer.invalidate() }

    fun setRulerVisible(visible: Boolean) {
        rulerVisible = visible
        if (visible) placeRulerIfNeeded()
        strokeLayer.invalidate()
    }

    fun setRulerDarkTheme(dark: Boolean) {
        if (rulerDarkTheme == dark) return
        rulerDarkTheme = dark
        updateRulerPaints()
        strokeLayer.invalidate()
    }

    fun setPage(bitmap: Bitmap?) { pageBitmap = bitmap; strokeLayer.invalidate() }
    fun setPdfMarks(marks: List<PdfMark>) { pdfMarks = marks.filterNot(PdfMark::deleted); strokeLayer.invalidate() }
    fun setAudioPlayback(pageId: String?, positionMs: Long?, timestamps: List<InkTimestamp>) {
        audioPlaybackPageId = pageId
        audioPlaybackPositionMs = positionMs
        audioItemTimes = timestamps.filter { it.pageId == pageId }
            .associate { "${it.itemKind}:${it.itemKey}" to it.timeMs }
        audioLatestItemKey = audioItemTimes.asSequence()
            .filter { it.value <= (positionMs ?: -1L) }
            .maxByOrNull { it.value }?.key
        strokeLayer.invalidate()
    }
    fun paperColorArgb(): Int = paperPaint.color

    private fun drawPdfMarks(canvas: Canvas) {
        pdfMarks.forEach { mark ->
            drawWithAudioAlpha(canvas, "mark", mark.id) {
                val isHighlight = mark.kind == PdfMarkKind.HIGHLIGHT
                val darkPaper = android.graphics.Color.luminance(paperPaint.color) < 0.5f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isHighlight) PdfMarkGeometry.displayHighlightColor(mark.colorArgb, darkPaper) else mark.colorArgb
                    strokeWidth = 1.5f
                    style = if (isHighlight) Paint.Style.FILL else Paint.Style.STROKE
                }
                PdfMarkGeometry.quads(mark.quads).forEach { quad ->
                    val top = pageHeightPt - quad.bottom
                    val bottom = pageHeightPt - quad.top
                    if (audioLatestItemKey == "mark:${mark.id}") {
                        glowPaint.color = 0x22FFD54F
                        canvas.drawRoundRect(quad.left, top, quad.right, bottom, 2f, 2f, glowPaint)
                    }
                    when (mark.kind) {
                        PdfMarkKind.HIGHLIGHT -> canvas.drawRect(quad.left, top, quad.right, bottom, paint)
                        PdfMarkKind.UNDERLINE -> canvas.drawLine(quad.left, bottom, quad.right, bottom, paint)
                        PdfMarkKind.STRIKE -> canvas.drawLine(quad.left, (top + bottom) / 2f, quad.right, (top + bottom) / 2f, paint)
                        PdfMarkKind.SQUIGGLY -> {
                            val path = Path()
                            var x = quad.left
                            var up = true
                            path.moveTo(x, bottom)
                            while (x < quad.right) {
                                x = min(quad.right, x + 2f)
                                path.lineTo(x, bottom + if (up) -1.5f else 1.5f)
                                up = !up
                            }
                            canvas.drawPath(path, paint)
                        }
                    }
                }
            }
        }
    }

    fun setPageImages(pageId: String, images: List<PageImage>) {
        if (imagePageId != pageId) {
            imageBitmaps.clear()
            imagePageId = pageId
        }
        pageImages = images
        clearSelection()
        images.forEach { image ->
            if (image.assetId !in imageBitmaps) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.localPath, bounds)
                val target = max(image.wPt * scale, image.hPt * scale).roundToInt().coerceAtLeast(1)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > target * 2) sample *= 2
                BitmapFactory.decodeFile(image.localPath, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.let { imageBitmaps[image.assetId] = it }
            }
        }
        strokeLayer.invalidate()
    }

    fun imagePlacement(widthPx: Int, heightPx: Int): RectF {
        val center = floatArrayOf(width / 2f, height / 2f)
        viewToPage.mapPoints(center)
        val w = min(pageWidthPt * 0.6f, width / scale * 0.6f).coerceAtLeast(1f)
        val h = w * heightPx / widthPx.coerceAtLeast(1)
        return RectF(center[0] - w / 2f, center[1] - h / 2f, center[0] + w / 2f, center[1] + h / 2f)
    }

    fun addPageImage(image: PageImage) {
        snapshot()
        pageImages = pageImages + image
        setPageImages(image.pageId, pageImages)
        selectPageImage(image)
        strokeLayer.invalidate()
        notifyPageImagesChanged()
    }

    private fun drawPageImages(canvas: Canvas) {
        pageImages.forEach { image ->
            drawWithAudioAlpha(canvas, "image", image.id) {
                imageBitmaps[image.assetId]?.let {
                    val bounds = RectF(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt)
                    if (audioLatestItemKey == "image:${image.id}") {
                        glowPaint.color = 0x22FFD54F
                        canvas.drawRoundRect(bounds, 3f, 3f, glowPaint)
                    }
                    canvas.drawBitmap(it, null, bounds, imagePaint)
                }
            }
        }
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawWithAudioAlpha(canvas: Canvas, kind: String, key: String, draw: () -> Unit) {
        val position = audioPlaybackPositionMs
        val time = audioItemTimes["$kind:$key"]
        if (position == null || audioPlaybackPageId != revealPageId || time == null || time <= position) {
            draw()
        } else {
            val layer = canvas.saveLayer(null, Paint().apply { alpha = 77 })
            draw()
            canvas.restoreToCount(layer)
        }
    }

    private fun drawStrokesWithAudio(
        canvas: Canvas,
        transform: Matrix,
        wet: InProgressStroke?,
        revealedTapes: Set<Int>,
    ) {
        if (audioPlaybackPositionMs == null || audioPlaybackPageId != revealPageId) {
            drawStrokes(canvas, strokes, renderer, transform, paperPaint.color, wet, revealedTapes, canvasHasPageTransform = true)
            return
        }
        strokes.forEachIndexed { index, stroke ->
            val key = "stroke:$index"
            val bounds = stroke.shape.computeBoundingBox()
            if (audioLatestItemKey == "stroke:$key" && bounds != null) {
                glowPaint.color = 0x22FFD54F
                canvas.drawRoundRect(RectF(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax), 3f, 3f, glowPaint)
            }
            drawWithAudioAlpha(canvas, "stroke", key) {
                drawStrokes(
                    canvas,
                    listOf(stroke),
                    renderer,
                    transform,
                    paperPaint.color,
                    null,
                    if (index in revealedTapes) setOf(0) else emptySet(),
                    canvasHasPageTransform = true,
                )
            }
        }
        if (wet != null) drawStrokes(canvas, emptyList(), renderer, transform, paperPaint.color, wet, canvasHasPageTransform = true)
    }

    private fun drawTextBlockWithAudio(canvas: Canvas, block: TextBlock, selected: Boolean) {
        drawWithAudioAlpha(canvas, "text", block.id) {
            if (audioLatestItemKey == "text:${block.id}") {
                glowPaint.color = 0x22FFD54F
                canvas.drawRoundRect(
                    block.xPt,
                    block.yPt - block.fontSizePt * 1.8f,
                    block.xPt + block.wPt,
                    block.yPt + block.fontSizePt * 0.4f,
                    3f,
                    3f,
                    glowPaint,
                )
            }
            TextBlockPainter.draw(canvas, block, selected, paperPaint.color, context)
        }
    }

    private fun seekAudioToItem(viewX: Float, viewY: Float): Boolean {
        val playhead = audioPlaybackPositionMs ?: return false
        if (audioPlaybackPageId != revealPageId) return false
        val point = floatArrayOf(viewX, viewY)
        viewToPage.mapPoints(point)
        var nearestDistance = Float.MAX_VALUE
        var nearestTime: Long? = null
        fun consider(kind: String, key: String, rect: RectF) {
            val time = audioItemTimes["$kind:$key"] ?: return
            val dx = max(max(rect.left - point[0], 0f), point[0] - rect.right)
            val dy = max(max(rect.top - point[1], 0f), point[1] - rect.bottom)
            val distance = hypot(dx, dy)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestTime = time
            }
        }
        strokes.forEachIndexed { index, stroke ->
            stroke.shape.computeBoundingBox()?.let { bounds ->
                consider("stroke", "stroke:$index", RectF(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax))
            }
        }
        textBlocks.forEach { block ->
            consider(
                "text",
                block.id,
                RectF(block.xPt, block.yPt - block.fontSizePt * 1.8f, block.xPt + block.wPt, block.yPt + block.fontSizePt * 0.4f),
            )
        }
        pageImages.forEach { image ->
            consider("image", image.id, RectF(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt))
        }
        pdfMarks.forEach { mark ->
            PdfMarkGeometry.quads(mark.quads).forEach { quad ->
                consider("mark", mark.id, RectF(quad.left, pageHeightPt - quad.bottom, quad.right, pageHeightPt - quad.top))
            }
        }
        val target = nearestTime?.takeIf { nearestDistance <= 28f } ?: return false
        if (target != playhead) onAudioSeekRequested?.invoke(target)
        return true
    }

    fun setStrokes(list: List<Stroke>) {
        strokes.clear(); strokes.addAll(list); history.clear(); future.clear()
        strokeLayer.invalidate(); notifyUndoRedoChanged(); notifyChanged()
    }
    fun setRevealPage(pageId: String) {
        revealPageId = pageId
        strokeLayer.invalidate()
    }
    fun strokes(): List<Stroke> = strokes.toList()
    fun canUndo() = history.isNotEmpty()
    fun canRedo() = future.isNotEmpty()
    fun undo() {
        if (history.isEmpty()) return
        val target = history.removeAt(history.size - 1)
        future.add(
            Snapshot(
                ArrayList(strokes),
                ArrayList(pageImages),
                ArrayList(textBlocks),
                target.externalUndo,
                target.externalRedo,
            ),
        )
        if (target.externalUndo != null) {
            target.externalUndo.invoke()
            notifyUndoRedoChanged()
        } else {
            restoreSnapshot(target)
        }
    }
    fun recordExternalAction(undoAction: () -> Unit, redoAction: () -> Unit) {
        snapshot(undoAction, redoAction)
    }
    fun convertLastStrokeToExternalUndo(
        stroke: Stroke,
        undoAction: () -> Unit,
        redoAction: () -> Unit,
    ): Boolean {
        if (strokes.lastOrNull() != stroke) return false
        if (history.isNotEmpty()) history.removeAt(history.lastIndex)
        strokes.removeAt(strokes.lastIndex)
        future.clear()
        history.add(Snapshot(ArrayList(strokes), ArrayList(pageImages), ArrayList(textBlocks), undoAction, redoAction))
        strokeLayer.invalidate()
        notifyChanged()
        notifyUndoRedoChanged()
        return true
    }
    fun redo() {
        if (future.isEmpty()) return
        val target = future.removeAt(future.size - 1)
        history.add(
            Snapshot(
                ArrayList(strokes),
                ArrayList(pageImages),
                ArrayList(textBlocks),
                target.externalUndo,
                target.externalRedo,
            ),
        )
        if (target.externalRedo != null) {
            target.externalRedo.invoke()
            notifyUndoRedoChanged()
        } else {
            restoreSnapshot(target)
        }
    }
    private fun restoreSnapshot(snapshot: Snapshot) {
        strokes.clear(); strokes.addAll(snapshot.strokes)
        pageImages = snapshot.images
        textBlocks = snapshot.textBlocks
        imagePageId?.let { setPageImages(it, pageImages) }
        clearSelection()
        strokeLayer.invalidate()
        notifyUndoRedoChanged()
        notifyChanged()
        notifyPageImagesChanged()
        onTextBlocksChanged?.invoke(textBlocks)
    }
    fun clear() { if (strokes.isEmpty()) return; snapshot(); strokes.clear(); clearSelection(); strokeLayer.invalidate(); notifyChanged() }

    fun hasSelection() = selected.isNotEmpty() || selectedPageImageId != null
    fun hasImageSelection() = selectedPageImageId != null
    fun selectedStrokes(): List<Stroke> = selected.toList()
    fun selectedTextColorForPaper(paperArgb: Int): Int {
        val highlighter = StockBrushes.highlighter()
        return TextColorPolicy.textColor(
            selected.map { stroke ->
                val countsAsInk = stroke.brush.family != highlighter && stroke.brush.family != InkBrushFamilies.tape
                StrokeColorSample(stroke.brush.colorIntArgb, countsAsInk)
            },
            paperArgb,
        )
    }

    fun convertSelectionToText(block: TextBlock) {
        if (selected.isEmpty()) return
        snapshot()
        strokes.removeAll(selected)
        selected.clear()
        selectedPageImageId = null
        selectionBounds = null
        textBlocks = textBlocks + block
        onSelectionChanged?.invoke(false)
        onSelectionBoundsChanged?.invoke(null)
        onTextBlocksChanged?.invoke(textBlocks)
        strokeLayer.invalidate()
        notifyChanged()
        notifyUndoRedoChanged()
    }

    fun clearSelection() {
        selected.clear()
        selectedPageImageId = null
        selectionBounds = null
        strokeLayer.invalidate()
        onSelectionChanged?.invoke(false)
        onSelectionBoundsChanged?.invoke(null)
    }

    fun copySelection() {
        if (selected.isNotEmpty()) SelectionClipboard.copy(selected)
    }

    fun duplicateSelection() {
        selectedPageImageId?.let { id ->
            val image = pageImages.firstOrNull { it.id == id } ?: return
            snapshot()
            val copy = image.copy(id = java.util.UUID.randomUUID().toString(), xPt = image.xPt + 24f, yPt = image.yPt + 24f)
            pageImages = pageImages + copy
            selectPageImage(copy)
            strokeLayer.invalidate()
            notifyPageImagesChanged()
            return
        }
        if (selected.isEmpty()) return
        snapshot()
        val copies = selected.map { transformStroke(it, 1f, 24f, 24f) }
        strokes.addAll(copies)
        selected.clear()
        selected.addAll(copies)
        updateSelectionBounds()
        strokeLayer.invalidate()
        notifyChanged()
    }

    fun paste() {
        val copied = SelectionClipboard.read()
        if (copied.isEmpty()) return
        val bounds = strokeBounds(copied) ?: return
        val center = floatArrayOf(width / 2f, height / 2f)
        viewToPage.mapPoints(center)
        val dx = center[0] - bounds.centerX()
        val dy = center[1] - bounds.centerY()
        snapshot()
        val pasted = copied.map { transformStroke(it, 1f, dx, dy) }
        strokes.addAll(pasted)
        selected.clear()
        selected.addAll(pasted)
        updateSelectionBounds()
        strokeLayer.invalidate()
        notifyChanged()
    }

    fun recolorSelection(argb: Int) {
        if (selected.isEmpty()) return
        snapshot()
        val recolored = selected.map { stroke ->
            val color = (argb and 0x00FFFFFF) or (stroke.brush.colorIntArgb and 0xFF000000.toInt())
            val updated = Stroke(stroke.brush.copyWithColorIntArgb(color), stroke.inputs)
            val index = strokes.indexOf(stroke)
            if (index >= 0) strokes[index] = updated
            updated
        }
        selected.clear()
        selected.addAll(recolored)
        updateSelectionBounds()
        strokeLayer.invalidate()
        notifyChanged()
    }

    fun deleteSelection() {
        if (selectedPageImageId != null) {
            snapshot()
            pageImages = pageImages.filterNot { it.id == selectedPageImageId }
            clearSelection()
            strokeLayer.invalidate()
            notifyPageImagesChanged()
            return
        }
        if (selected.isEmpty()) return
        snapshot(); strokes.removeAll(selected); clearSelection(); notifyChanged()
    }

    private fun selectionHandlePoints(bounds: RectF): List<FloatArray> = listOf(
        floatArrayOf(bounds.left, bounds.top),
        floatArrayOf(bounds.right, bounds.top),
        floatArrayOf(bounds.left, bounds.bottom),
        floatArrayOf(bounds.right, bounds.bottom),
    )

    private fun hitSelectionHandle(x: Float, y: Float): Int {
        val bounds = selectionBounds ?: return -1
        val viewBounds = RectF(bounds).also(pageToView::mapRect)
        val hitRadius = 16f * resources.displayMetrics.density
        return selectionHandlePoints(viewBounds).indexOfFirst { hypot(it[0] - x, it[1] - y) <= hitRadius }
    }

    private fun updateSelectionBounds() {
        val bounds = strokeBounds(selected)
        selectionBounds = bounds?.apply { inset(-6f, -6f) }
        onSelectionBoundsChanged?.invoke(selectionBounds?.let { RectF(it).also(pageToView::mapRect) })
        onSelectionChanged?.invoke(selected.isNotEmpty())
    }

    private fun selectPageImage(image: PageImage) {
        selected.clear()
        selectedPageImageId = image.id
        selectionBounds = RectF(image.xPt, image.yPt, image.xPt + image.wPt, image.yPt + image.hPt)
        onSelectionBoundsChanged?.invoke(RectF(selectionBounds!!).also(pageToView::mapRect))
        onSelectionChanged?.invoke(true)
    }

    private fun notifyPageImagesChanged() { onPageImagesChanged?.invoke(pageImages.toList()) }

    private fun strokeBounds(strokeList: Collection<Stroke>): RectF? {
        var bounds: RectF? = null
        for (stroke in strokeList) {
            val inputs = stroke.inputs
            for (i in 0 until inputs.size) {
                val p = inputs[i]
                if (bounds == null) bounds = RectF(p.x, p.y, p.x, p.y) else bounds.union(p.x, p.y)
            }
        }
        return bounds
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun transformStroke(stroke: Stroke, factor: Float, dx: Float, dy: Float, anchorX: Float = 0f, anchorY: Float = 0f): Stroke {
        val batch = MutableStrokeInputBatch()
        val inputs = stroke.inputs
        for (i in 0 until inputs.size) {
            val p = inputs[i]
            batch.add(StrokeInput.create(
                anchorX + (p.x - anchorX) * factor + dx,
                anchorY + (p.y - anchorY) * factor + dy,
                p.elapsedTimeMillis, p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians,
            ))
        }
        val brush = Brush.createWithColorIntArgb(stroke.brush.family, stroke.brush.colorIntArgb, stroke.brush.size * factor, stroke.brush.epsilon)
        return Stroke(brush, batch)
    }

    private fun handleLasso(event: MotionEvent, idx: Int): Boolean {
        val pt = floatArrayOf(event.getX(idx), event.getY(idx)); viewToPage.mapPoints(pt)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val handle = hitSelectionHandle(event.getX(idx), event.getY(idx))
                val b = selectionBounds
                val imageHit = pageImages.firstOrNull { pt[0] in it.xPt..(it.xPt + it.wPt) && pt[1] in it.yPt..(it.yPt + it.hPt) }
                if (handle >= 0 && b != null && selectedPageImageId != null) {
                    resizeStartImage = pageImages.firstOrNull { it.id == selectedPageImageId }
                    resizingSelection = true
                    resizeHandle = handle
                    resizeStartBounds = RectF(b)
                    val anchors = arrayOf(floatArrayOf(b.right, b.bottom), floatArrayOf(b.left, b.bottom), floatArrayOf(b.right, b.top), floatArrayOf(b.left, b.top))
                    resizeAnchor = anchors[handle]
                    imageGestureMoved = false
                    moveLastX = pt[0]; moveLastY = pt[1]
                } else if (handle >= 0 && b != null) {
                    snapshot()
                    resizingSelection = true
                    resizeHandle = handle
                    resizeStartBounds = RectF(b)
                    resizeStartStrokes = selected.toList()
                    resizeStrokeIndices = resizeStartStrokes.map(strokes::indexOf)
                    val anchors = arrayOf(
                        floatArrayOf(b.right, b.bottom), floatArrayOf(b.left, b.bottom),
                        floatArrayOf(b.right, b.top), floatArrayOf(b.left, b.top),
                    )
                    resizeAnchor = anchors[handle]
                } else if (imageHit != null) {
                    selectPageImage(imageHit)
                    movingSelection = true
                    imageGestureMoved = false
                    moveLastX = pt[0]; moveLastY = pt[1]
                } else if (b != null && b.contains(pt[0], pt[1])) { snapshot(); movingSelection = true; moveLastX = pt[0]; moveLastY = pt[1] }
                else { clearSelection(); lassoActive = true; lassoPath.reset(); lassoPath.moveTo(pt[0], pt[1]); lassoPoints.clear(); lassoPoints.add(pt[0]); lassoPoints.add(pt[1]) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizingSelection) {
                    if (resizeStartImage != null) {
                        if (!imageGestureMoved && hypot(pt[0] - moveLastX, pt[1] - moveLastY) > 1f) { snapshot(); imageGestureMoved = true }
                        if (imageGestureMoved) resizeSelection(pt[0], pt[1])
                    } else resizeSelection(pt[0], pt[1])
                }
                else if (movingSelection) {
                    if (selectedPageImageId != null) {
                        if (!imageGestureMoved && hypot(pt[0] - moveLastX, pt[1] - moveLastY) > 1f) { snapshot(); imageGestureMoved = true }
                        if (imageGestureMoved) moveSelection(pt[0] - moveLastX, pt[1] - moveLastY)
                    } else moveSelection(pt[0] - moveLastX, pt[1] - moveLastY)
                    moveLastX = pt[0]; moveLastY = pt[1]
                }
                else if (lassoActive) { lassoPath.lineTo(pt[0], pt[1]); lassoPoints.add(pt[0]); lassoPoints.add(pt[1]); strokeLayer.invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizingSelection) {
                    resizingSelection = false
                    resizeStartBounds = null
                    if (resizeStartImage != null && imageGestureMoved) notifyPageImagesChanged()
                    resizeStartImage = null
                    imageGestureMoved = false
                    resizeStartStrokes = emptyList()
                    resizeStrokeIndices = emptyList()
                    notifyChanged()
                } else if (movingSelection) {
                    movingSelection = false
                    if (selectedPageImageId != null) { if (imageGestureMoved) notifyPageImagesChanged(); imageGestureMoved = false }
                    else notifyChanged()
                }
                else if (lassoActive) { lassoActive = false; lassoPath.close(); selectInside(); strokeLayer.invalidate() }
            }
        }
        return true
    }

    private fun selectInside() {
        if (lassoPoints.size < 6) return
        val bounds = RectF(); lassoPath.computeBounds(bounds, true)
        val region = Region()
        region.setPath(lassoPath, Region(bounds.left.toInt() - 1, bounds.top.toInt() - 1, bounds.right.toInt() + 1, bounds.bottom.toInt() + 1))
        selected.clear()
        val sel = RectF()
        var first = true
        for (s in strokes) {
            val inputs = s.inputs
            var inside = 0
            for (i in 0 until inputs.size) { val p = inputs[i]; if (region.contains(p.x.toInt(), p.y.toInt())) inside++ }
            if (inputs.size > 0 && inside * 2 >= inputs.size) {
                selected.add(s)
                for (i in 0 until inputs.size) { val p = inputs[i]; if (first) { sel.set(p.x, p.y, p.x, p.y); first = false } else sel.union(p.x, p.y) }
            }
        }
        selectionBounds = if (selected.isEmpty()) null else RectF(sel).apply { inset(-6f, -6f) }
        onSelectionChanged?.invoke(selected.isNotEmpty())
        onSelectionBoundsChanged?.invoke(selectionBounds?.let { RectF(it).also(pageToView::mapRect) })
    }

    private fun resizeSelection(pointerX: Float, pointerY: Float) {
        val start = resizeStartBounds ?: return
        resizeStartImage?.let { image ->
            val factor = max(abs(pointerX - resizeAnchor[0]) / image.wPt.coerceAtLeast(1f), abs(pointerY - resizeAnchor[1]) / image.hPt.coerceAtLeast(1f)).coerceIn(0.1f, 20f)
            val w = image.wPt * factor
            val h = image.hPt * factor
            val left = if (resizeHandle == 0 || resizeHandle == 2) resizeAnchor[0] - w else resizeAnchor[0]
            val top = if (resizeHandle == 0 || resizeHandle == 1) resizeAnchor[1] - h else resizeAnchor[1]
            val updated = image.copy(xPt = left, yPt = top, wPt = w, hPt = h)
            pageImages = pageImages.map { if (it.id == image.id) updated else it }
            selectionBounds = RectF(left, top, left + w, top + h)
            onSelectionBoundsChanged?.invoke(RectF(selectionBounds!!).also(pageToView::mapRect))
            strokeLayer.invalidate()
            return
        }
        val handle = when (resizeHandle) {
            0 -> floatArrayOf(start.left, start.top)
            1 -> floatArrayOf(start.right, start.top)
            2 -> floatArrayOf(start.left, start.bottom)
            else -> floatArrayOf(start.right, start.bottom)
        }
        val initialX = abs(handle[0] - resizeAnchor[0]).coerceAtLeast(1f)
        val initialY = abs(handle[1] - resizeAnchor[1]).coerceAtLeast(1f)
        val factor = max(abs(pointerX - resizeAnchor[0]) / initialX, abs(pointerY - resizeAnchor[1]) / initialY)
            .coerceIn(0.1f, 20f)
        val resized = ArrayList<Stroke>(resizeStartStrokes.size)
        resizeStartStrokes.forEachIndexed { index, stroke ->
            val updated = transformStroke(stroke, factor, 0f, 0f, resizeAnchor[0], resizeAnchor[1])
            val strokeIndex = resizeStrokeIndices.getOrNull(index) ?: -1
            if (strokeIndex in strokes.indices) strokes[strokeIndex] = updated
            resized.add(updated)
        }
        selected.clear()
        selected.addAll(resized)
        updateSelectionBounds()
        strokeLayer.invalidate()
    }

    private fun moveSelection(dx: Float, dy: Float) {
        selectedPageImageId?.let { id ->
            pageImages = pageImages.map { if (it.id == id) it.copy(xPt = it.xPt + dx, yPt = it.yPt + dy) else it }
            selectionBounds?.offset(dx, dy)
            onSelectionBoundsChanged?.invoke(selectionBounds?.let { RectF(it).also(pageToView::mapRect) })
            strokeLayer.invalidate()
            return
        }
        if (selected.isEmpty()) return
        val moved = LinkedHashSet<Stroke>()
        for (s in selected) {
            val batch = MutableStrokeInputBatch()
            val inputs = s.inputs
            for (i in 0 until inputs.size) {
                val p = inputs[i]
                batch.add(StrokeInput.create(p.x + dx, p.y + dy, p.elapsedTimeMillis, p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians))
            }
            val ns = Stroke(s.brush, batch)
            val at = strokes.indexOf(s); if (at >= 0) strokes[at] = ns
            moved.add(ns)
        }
        selected.clear(); selected.addAll(moved)
        selectionBounds?.offset(dx, dy)
        onSelectionBoundsChanged?.invoke(selectionBounds?.let { RectF(it).also(pageToView::mapRect) })
        strokeLayer.invalidate()
    }

    private fun notifyChanged() { onStrokesChanged?.invoke(strokes.toList()) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToWidth()
        placeRulerIfNeeded()
    }

    private fun displayPageWidth(): Float = if (pageRotation % 180 == 90) pageHeightPt else pageWidthPt
    private fun displayPageHeight(): Float = if (pageRotation % 180 == 90) pageWidthPt else pageHeightPt

    private fun minScale(): Float = if (width > 0) (width - 2 * MARGIN_PX) / displayPageWidth() else 1f

    private fun fitToWidth() {
        if (width == 0) return
        scale = minScale(); panX = MARGIN_PX; panY = MARGIN_PX
        updateMatrices()
    }

    private fun rulerLength() = max(pageWidthPt, pageHeightPt) * 1.3f

    private fun placeRulerIfNeeded() {
        if (!hasPageGeometry || rulerInitialized || width == 0 || height == 0) return
        val left = max(0f, panX)
        val right = min(width.toFloat(), panX + pageWidthPt * scale)
        val top = max(0f, panY)
        val bottom = min(height.toFloat(), panY + pageHeightPt * scale)
        val center = floatArrayOf((left + right) / 2f, (top + bottom) / 2f)
        viewToPage.mapPoints(center)
        rulerCx = center[0]
        rulerCy = center[1]
        rulerAngle = 0f
        rulerInitialized = true
        strokeLayer.invalidate()
    }

    private fun updateRulerPaints() {
        rulerFillPaint.color = if (rulerDarkTheme) 0xEB2A2A2D.toInt() else 0xEBF1F2F4.toInt()
        rulerOutlinePaint.color = if (rulerDarkTheme) 0x99000000.toInt() else 0xFF9AA0A6.toInt()
        rulerTickPaint.color = if (rulerDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
        rulerTextPaint.color = if (rulerDarkTheme) 0xFFE0E0E0.toInt() else 0xFF333333.toInt()
        rulerHandlePaint.color = rulerFillPaint.color
        rulerHandleOutlinePaint.color = rulerOutlinePaint.color
        rulerAnglePillPaint.color = rulerFillPaint.color
    }

    private fun clampPan() {
        val pageW = displayPageWidth() * scale; val pageH = displayPageHeight() * scale
        panX = if (pageW <= width) (width - pageW) / 2f else panX.coerceIn(width - pageW - MARGIN_PX, MARGIN_PX)
        panY = if (pageH <= height - 2 * MARGIN_PX) MARGIN_PX else panY.coerceIn(height - pageH - MARGIN_PX, MARGIN_PX)
    }

    private fun updateMatrices() {
        pageToView.setValues(when (pageRotation) {
            90 -> floatArrayOf(0f, -scale, panX + pageHeightPt * scale, scale, 0f, panY, 0f, 0f, 1f)
            180 -> floatArrayOf(-scale, 0f, panX + pageWidthPt * scale, 0f, -scale, panY + pageHeightPt * scale, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, scale, panX, -scale, 0f, panY + pageWidthPt * scale, 0f, 0f, 1f)
            else -> floatArrayOf(scale, 0f, panX, 0f, scale, panY, 0f, 0f, 1f)
        })
        pageToView.invert(viewToPage)
        onSelectionBoundsChanged?.invoke(selectionBounds?.let { RectF(it).also(pageToView::mapRect) })
        updateZoom()
        val zoomPercent = (scale / minScale() * 100f).roundToInt()
        if (zoomPercent != lastReportedZoomPercent) {
            lastReportedZoomPercent = zoomPercent
            onZoomChanged?.invoke(zoomPercent)
        }
        strokeLayer.invalidate()
    }

    private fun eventPagePoint(event: MotionEvent, index: Int): FloatArray {
        val point = floatArrayOf(event.getX(index), event.getY(index))
        viewToPage.mapPoints(point)
        return point
    }

    private fun rulerLocalPoint(pageX: Float, pageY: Float): FloatArray {
        val dx = pageX - rulerCx
        val dy = pageY - rulerCy
        val c = cos(rulerAngle)
        val s = sin(rulerAngle)
        return floatArrayOf(
            rulerCx + dx * c + dy * s,
            rulerCy - dx * s + dy * c,
        )
    }

    private fun rulerBodyContains(pageX: Float, pageY: Float): Boolean {
        val local = rulerLocalPoint(pageX, pageY)
        return abs(local[0] - rulerCx) <= rulerLength() / 2f &&
            abs(local[1] - rulerCy) <= RULER_THICKNESS_PT / 2f
    }

    private fun rulerHandleContains(pageX: Float, pageY: Float): Boolean {
        val local = rulerLocalPoint(pageX, pageY)
        return hypot(local[0] - (rulerCx + rulerLength() / 2f), local[1] - rulerCy) <= RULER_HANDLE_RADIUS_PT
    }

    private fun rulerAngleDelta(current: Float, start: Float): Float =
        atan2(sin(current - start), cos(current - start))

    private fun snapRulerAngle(angle: Float): Float {
        val step = (Math.PI / 4.0).toFloat()
        val nearest = (angle / step).roundToInt() * step
        val difference = abs(rulerAngleDelta(angle, nearest))
        return if (difference <= Math.toRadians(3.0).toFloat()) nearest else angle
    }

    private fun startRulerDrag(event: MotionEvent, index: Int) {
        val point = eventPagePoint(event, index)
        rulerGesture = RulerGesture.DRAG
        rulerPrimaryPointerId = event.getPointerId(index)
        rulerSecondaryPointerId = -1
        rulerLastX = point[0]
        rulerLastY = point[1]
    }

    private fun startRulerRotation(event: MotionEvent, index: Int) {
        rulerGesture = RulerGesture.ROTATE
        rulerPrimaryPointerId = event.getPointerId(index)
        rulerSecondaryPointerId = -1
    }

    private fun startRulerTwoFinger(event: MotionEvent, firstIndex: Int, secondIndex: Int) {
        val first = eventPagePoint(event, firstIndex)
        val second = eventPagePoint(event, secondIndex)
        rulerGesture = RulerGesture.TWO_FINGER
        rulerPrimaryPointerId = event.getPointerId(firstIndex)
        rulerSecondaryPointerId = event.getPointerId(secondIndex)
        rulerStartCx = rulerCx
        rulerStartCy = rulerCy
        rulerStartAngle = rulerAngle
        rulerStartCentroidX = (first[0] + second[0]) / 2f
        rulerStartCentroidY = (first[1] + second[1]) / 2f
        rulerStartPointerAngle = atan2(second[1] - first[1], second[0] - first[0])
    }

    private fun resetRulerGesture() {
        rulerGesture = RulerGesture.NONE
        rulerPrimaryPointerId = -1
        rulerSecondaryPointerId = -1
    }

    private fun handleRulerTouch(event: MotionEvent, isStylus: Boolean): Boolean {
        if (!rulerVisible || !rulerInitialized) return false
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = eventPagePoint(event, index)
                if (rulerHandleContains(point[0], point[1])) {
                    startRulerRotation(event, index)
                    return true
                }
                if (!isStylus && event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER &&
                    rulerBodyContains(point[0], point[1])
                ) {
                    startRulerDrag(event, index)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (rulerGesture == RulerGesture.DRAG &&
                    event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
                ) {
                    val primaryIndex = event.findPointerIndex(rulerPrimaryPointerId)
                    if (primaryIndex >= 0 &&
                        event.getToolType(primaryIndex) == MotionEvent.TOOL_TYPE_FINGER
                    ) {
                        val point = eventPagePoint(event, index)
                        if (rulerBodyContains(point[0], point[1])) {
                            startRulerTwoFinger(event, primaryIndex, index)
                        }
                    }
                }
                return rulerGesture != RulerGesture.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                when (rulerGesture) {
                    RulerGesture.DRAG -> {
                        val pointerIndex = event.findPointerIndex(rulerPrimaryPointerId)
                        if (pointerIndex >= 0) {
                            val point = eventPagePoint(event, pointerIndex)
                            rulerCx += point[0] - rulerLastX
                            rulerCy += point[1] - rulerLastY
                            rulerLastX = point[0]
                            rulerLastY = point[1]
                            strokeLayer.invalidate()
                        }
                    }
                    RulerGesture.ROTATE -> {
                        val pointerIndex = event.findPointerIndex(rulerPrimaryPointerId)
                        if (pointerIndex >= 0) {
                            val point = eventPagePoint(event, pointerIndex)
                            rulerAngle = snapRulerAngle(atan2(point[1] - rulerCy, point[0] - rulerCx))
                            strokeLayer.invalidate()
                        }
                    }
                    RulerGesture.TWO_FINGER -> {
                        val firstIndex = event.findPointerIndex(rulerPrimaryPointerId)
                        val secondIndex = event.findPointerIndex(rulerSecondaryPointerId)
                        if (firstIndex >= 0 && secondIndex >= 0) {
                            val first = eventPagePoint(event, firstIndex)
                            val second = eventPagePoint(event, secondIndex)
                            val centroidX = (first[0] + second[0]) / 2f
                            val centroidY = (first[1] + second[1]) / 2f
                            val pointerAngle = atan2(second[1] - first[1], second[0] - first[0])
                            rulerCx = rulerStartCx + centroidX - rulerStartCentroidX
                            rulerCy = rulerStartCy + centroidY - rulerStartCentroidY
                            rulerAngle = snapRulerAngle(rulerStartAngle + rulerAngleDelta(pointerAngle, rulerStartPointerAngle))
                            strokeLayer.invalidate()
                        }
                    }
                    RulerGesture.NONE -> Unit
                }
                return rulerGesture != RulerGesture.NONE
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (rulerGesture == RulerGesture.TWO_FINGER) {
                    val remainingIndex = (0 until event.pointerCount).firstOrNull { it != index }
                    if (remainingIndex != null) {
                        rulerPrimaryPointerId = event.getPointerId(remainingIndex)
                        rulerSecondaryPointerId = -1
                        val point = eventPagePoint(event, remainingIndex)
                        rulerLastX = point[0]
                        rulerLastY = point[1]
                        rulerGesture = RulerGesture.DRAG
                    } else {
                        resetRulerGesture()
                    }
                    return true
                }
                return rulerGesture != RulerGesture.NONE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = rulerGesture != RulerGesture.NONE
                resetRulerGesture()
                return handled
            }
        }
        return rulerGesture != RulerGesture.NONE
    }

    private fun projectToRulerEdge(viewX: Float, viewY: Float): FloatArray {
        val edge = rulerTopEdgeInView()
        val dx = edge[2] - edge[0]
        val dy = edge[3] - edge[1]
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (
            ((viewX - edge[0]) * dx + (viewY - edge[1]) * dy) / lengthSquared
        ).coerceIn(0f, 1f)
        return floatArrayOf(edge[0] + t * dx, edge[1] + t * dy)
    }

    private fun beginRulerSnap(event: MotionEvent, index: Int, isStylus: Boolean, eraserNow: Boolean) {
        rulerSnapActive = false
        if (!rulerVisible || !rulerInitialized || (zoomEnabled && inStrip) || eraserNow ||
            (brushSpec.tool != InkTool.PEN && brushSpec.tool != InkTool.HIGHLIGHTER) ||
            (!isStylus && !fingerDraws)
        ) return
        val pointX = event.getX(index)
        val pointY = event.getY(index)
        val projected = projectToRulerEdge(pointX, pointY)
        if (hypot(projected[0] - pointX, projected[1] - pointY) <= RULER_SNAP_DISTANCE_PX) {
            val edge = rulerTopEdgeInView()
            rulerSnapStartX = edge[0]
            rulerSnapStartY = edge[1]
            rulerSnapEndX = edge[2]
            rulerSnapEndY = edge[3]
            rulerSnapActive = true
        }
    }

    private fun projectToSnapEdge(viewX: Float, viewY: Float): FloatArray {
        val dx = rulerSnapEndX - rulerSnapStartX
        val dy = rulerSnapEndY - rulerSnapStartY
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (
            ((viewX - rulerSnapStartX) * dx + (viewY - rulerSnapStartY) * dy) / lengthSquared
        ).coerceIn(0f, 1f)
        return floatArrayOf(rulerSnapStartX + t * dx, rulerSnapStartY + t * dy)
    }

    private fun <T> withRulerSnapEvent(event: MotionEvent, index: Int, block: (MotionEvent) -> T): T {
        if (!rulerSnapActive) return block(event)
        val adjusted = MotionEvent.obtain(event)
        val projected = projectToSnapEdge(event.getX(index), event.getY(index))
        adjusted.offsetLocation(projected[0] - event.getX(index), projected[1] - event.getY(index))
        return try {
            block(adjusted)
        } finally {
            adjusted.recycle()
        }
    }

    fun handleStylusKeyEvent(event: KeyEvent) {
        if (!StylusButtonKeyMapping.shouldDispatch(event.action, event.repeatCount)) return
        val button = StylusButtonKeyMapping.buttonForKeyCode(event.keyCode) ?: return
        val action = when (button) {
            MotionEvent.BUTTON_STYLUS_PRIMARY -> primaryButtonAction
            MotionEvent.BUTTON_STYLUS_SECONDARY -> secondaryButtonAction
            else -> return
        }
        onStylusToolSwitch?.invoke(action)
    }

    private fun beginStylusButton(button: Int, eventTime: Long) {
        if (!stylusButtonPresses.containsKey(button)) {
            stylusButtonPresses[button] = StylusButtonPress(
                downTime = eventTime,
                strokeIntervened = currentStrokeId != null || highlighterWet != null || erasing,
            )
        }
    }

    private fun endStylusButton(button: Int, eventTime: Long) {
        val press = stylusButtonPresses.remove(button) ?: return
        val duration = eventTime - press.downTime
        if (press.strokeIntervened || duration !in 0 until STYLUS_CLICK_TIMEOUT_MS) return
        val action = when (button) {
            MotionEvent.BUTTON_STYLUS_PRIMARY -> primaryButtonAction
            MotionEvent.BUTTON_STYLUS_SECONDARY -> secondaryButtonAction
            else -> return
        }
        onStylusToolSwitch?.invoke(action)
    }

    private fun observeStylusButtons(event: MotionEvent) {
        if (event.pointerCount == 0) return
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(index)
        val stylusSource = event.source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
        if (!stylusSource && toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return

        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        val current = event.buttonState and mask
        val previous = stylusButtonStates[event.deviceId] ?: 0
        val actionButton = event.actionButton and mask
        val pressed = (current and previous.inv()) or
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) actionButton else 0
        val released = (previous and current.inv()) or
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) actionButton else 0

        for (button in intArrayOf(MotionEvent.BUTTON_STYLUS_PRIMARY, MotionEvent.BUTTON_STYLUS_SECONDARY)) {
            if (pressed and button != 0) beginStylusButton(button, event.eventTime)
            if (released and button != 0) endStylusButton(button, event.eventTime)
        }
        if (pressed != 0 && (currentStrokeId != null || highlighterWet != null)) {
            val pointerIndex = event.findPointerIndex(currentPointerId)
                .takeIf { it >= 0 } ?: index
            markStylusButtonStroke()
            cancelCurrent(event)
            currentPointerId = event.getPointerId(pointerIndex)
            erasing = true
            setDrawingInputActive(true)
            lastErase = null
            snapshot()
            eraseDirty = false
            eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
        }
        stylusButtonStates[event.deviceId] = current
        if (
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE ||
            pressed != 0 || released != 0
        ) {
            StylusInputDiagnostics.recordMotionEvent(event)
        }
    }

    private fun markStylusButtonStroke() {
        stylusButtonPresses.values.forEach { it.strokeIntervened = true }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        observeStylusButtons(event)
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        observeStylusButtons(event)
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        observeStylusButtons(event)
        applyPendingStylusEraser(event)
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) return true
        predictor.record(event)
        val idx = event.actionIndex
        val toolType = event.getToolType(idx)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (activeLaserTrail != null && brushSpec.tool != InkTool.LASER) finishActiveLaserTrail()
        if (tapeRevealGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) tapeRevealGesture = false
            return true
        }
        if (zoomEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
            inStrip = event.y >= stripTop
        }
        val rulerHandled = handleRulerTouch(event, isStylus)
        if (rulerHandled) return true
        if (zoomEnabled && event.actionMasked == MotionEvent.ACTION_DOWN && !inStrip && isStylus) {
            val pt = floatArrayOf(event.x, event.y); viewToPage.mapPoints(pt)
            if (zoomWindow.contains(pt[0], pt[1])) { draggingWindow = true; dragLastX = pt[0]; dragLastY = pt[1]; return true }
        }
        if (draggingWindow) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val pt = floatArrayOf(event.x, event.y); viewToPage.mapPoints(pt)
                    zoomWindow.offset(pt[0] - dragLastX, pt[1] - dragLastY); dragLastX = pt[0]; dragLastY = pt[1]
                    clampZoomWindow(); updateMatrices()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> draggingWindow = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && brushSpec.tool != InkTool.TAPE) {
            val tapeIndex = tapeStrokeAt(event.x, event.y)
            tapeIndex?.let {
                val revealed = revealedTapeIndicesByPage.getOrPut(revealPageId) { mutableSetOf() }
                if (!revealed.add(it)) revealed.remove(it)
                tapeRevealGesture = true
                strokeLayer.invalidate()
                return true
            }
        }
        if (!isStylus && !fingerDraws) return handleFingerNavigation(event)
        if (!isStylus && event.pointerCount > 1) { cancelCurrent(event); return handleFingerNavigation(event) }

        if (brushSpec.tool == InkTool.LASSO) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                setDrawingInputActive(true)
            }
            val handled = handleLasso(
                event,
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    event.findPointerIndex(currentPointerId).takeIf { it >= 0 } ?: 0
                } else {
                    idx
                },
            )
            if (event.actionMasked == MotionEvent.ACTION_DOWN) currentPointerId = event.getPointerId(idx)
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                currentPointerId = -1
                setDrawingInputActive(false)
            }
            return handled
        }
        if (brushSpec.tool == InkTool.TEXT) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val pt = floatArrayOf(event.x, event.y); viewToPage.mapPoints(pt)
                onTextTap?.invoke(pt[0], pt[1], textBlocks.lastOrNull { TextBlockPainter.hit(it, pt[0], pt[1], context) })
            }
            return true
        }
        if (brushSpec.tool == InkTool.LASER) {
            val handled = handleLaserPointer(event, idx)
            if (activeLaserTrail != null) setDrawingInputActive(true)
            else if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                setDrawingInputActive(false)
            }
            return handled
        }
        val eraserNow = toolType == MotionEvent.TOOL_TYPE_ERASER || brushSpec.tool == InkTool.ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            stylusButtonPresses.containsKey(MotionEvent.BUTTON_STYLUS_PRIMARY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (currentStrokeId != null || highlighterWet != null || erasing) return true
                setDrawingInputActive(true)
                if (isStylus) markStylusButtonStroke()
                currentPointerId = event.getPointerId(idx)
                erasing = eraserNow
                if (erasing) { lastErase = null; snapshot(); eraseDirty = false; eraseAt(event.getX(idx), event.getY(idx)) }
                else {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) beginRulerSnap(event, idx, isStylus, eraserNow)
                    if (brushSpec.tool == InkTool.HIGHLIGHTER) {
                        withRulerSnapEvent(event, idx) { startHighlighterStroke(it, idx) }
                    } else {
                        val inputToPage = if (zoomEnabled && inStrip) Matrix(viewToZoom) else Matrix(viewToPage)
                        val pageToInput = Matrix()
                        inputToPage.invert(pageToInput)
                        currentStrokeId = withRulerSnapEvent(event, idx) {
                            wetLayer.startStroke(it, currentPointerId, brushSpec.toBrush(), inputToPage, identity)
                        }
                        currentStrokeId?.takeIf { brushSpec.tool == InkTool.PEN }?.let { strokeId ->
                            shapeStrokeContexts[strokeId] = ShapeStrokeContext(shapeStraightening, pageToInput)
                        }
                    }
                }
                report(event, idx); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = event.findPointerIndex(currentPointerId)
                if (pi < 0) return true
                if (erasing) {
                    for (h in 0 until event.historySize) eraseAt(event.getHistoricalX(pi, h), event.getHistoricalY(pi, h))
                    eraseAt(event.getX(pi), event.getY(pi))
                } else if (highlighterWet != null) {
                    withRulerSnapEvent(event, pi) { adjusted -> addToHighlighterStroke(adjusted, pi) }
                } else currentStrokeId?.let { strokeId ->
                    withRulerSnapEvent(event, pi) { adjusted ->
                        wetLayer.addToStroke(adjusted, currentPointerId, strokeId, predictor.predict())
                    }
                }
                report(event, pi); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(idx) != currentPointerId) return true
                if (erasing) {
                    eraseAt(event.getX(idx), event.getY(idx))
                    if (!eraseDirty && history.isNotEmpty()) {
                        history.removeAt(history.size - 1)
                        notifyUndoRedoChanged()
                    }
                }
                if (highlighterWet != null) {
                    withRulerSnapEvent(event, idx) { adjusted -> finishHighlighterStroke(adjusted, idx) }
                } else currentStrokeId?.let { strokeId ->
                    withRulerSnapEvent(event, idx) { adjusted ->
                        wetLayer.finishStroke(adjusted, currentPointerId, strokeId)
                    }
                }
                currentStrokeId = null; currentPointerId = -1; erasing = false; rulerSnapActive = false
                setDrawingInputActive(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelCurrent(event); rulerSnapActive = false; return true }
        }
        return false
    }

    private fun applyPendingStylusEraser(event: MotionEvent) {
        if (!pendingStylusErase || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pendingStylusErase = false
            return
        }
        if (currentStrokeId == null && highlighterWet == null) {
            pendingStylusErase = false
            return
        }
        val pointerIndex = event.findPointerIndex(currentPointerId)
            .takeIf { it >= 0 }
            ?: event.actionIndex.takeIf { it in 0 until event.pointerCount }
            ?: return
        pendingStylusErase = false
        markStylusButtonStroke()
        cancelCurrent(event)
        currentPointerId = event.getPointerId(pointerIndex)
        erasing = true
        setDrawingInputActive(true)
        lastErase = null
        snapshot()
        eraseDirty = false
        eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
    }

    private fun tapeStrokeAt(viewX: Float, viewY: Float): Int? {
        val point = floatArrayOf(viewX, viewY)
        viewToPage.mapPoints(point)
        return strokes.indices.reversed().firstOrNull { index ->
            val stroke = strokes[index]
            if (stroke.brush.family != InkBrushFamilies.tape || stroke.inputs.size < 2) return@firstOrNull false
            val first = stroke.inputs[0]
            val last = stroke.inputs[stroke.inputs.size - 1]
            val dx = last.x - first.x
            val dy = last.y - first.y
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0f) 0f else (
                ((point[0] - first.x) * dx + (point[1] - first.y) * dy) / lengthSquared
            ).coerceIn(0f, 1f)
            val distance = hypot(point[0] - first.x - t * dx, point[1] - first.y - t * dy)
            val hitRadius = stroke.brush.size / 2f + 8f
            val hit = distance <= hitRadius
            hit
        }
    }

    private fun startHighlighterStroke(event: MotionEvent, index: Int) {
        if (!highlighterLifecycle.begin()) return
        highlighterStoredBrush = brushSpec.toBrush()
        highlighterRenderBrush = highlighterStoredBrush?.let { brush ->
            val paperDark = android.graphics.Color.luminance(paperPaint.color) < 0.5f
            brush.copyWithColorIntArgb(opaqueHighlighterColor(brush.colorIntArgb, paperDark))
        }
        val renderBrush = highlighterRenderBrush ?: run {
            resetHighlighterStroke()
            return
        }
        highlighterWet = InProgressStroke().also { it.start(renderBrush) }
        highlighterStartedInStrip = zoomEnabled && inStrip
        highlighterStartEventTime = event.eventTime
        highlighterLastElapsed = -1L
        highlighterDownX = event.getX(index)
        highlighterDownY = event.getY(index)
        highlighterStraight = highlighterAlwaysStraight
        highlighterHoldCancelled = highlighterAlwaysStraight
        val inputs = highlighterInputs(event, index, includeHistory = true)
        highlighterFirstInput = inputs.firstOrNull()
        highlighterLastInput = inputs.lastOrNull()
        enqueueHighlighterInputs(inputs)
        if (!highlighterStraight) postDelayed(highlighterStraightenRunnable, HIGHLIGHTER_STRAIGHTEN_DELAY_MS)
        strokeLayer.invalidate()
    }

    private fun addToHighlighterStroke(event: MotionEvent, index: Int) {
        if (!highlighterLifecycle.acceptsInput() || highlighterWet == null) return
        val inputs = highlighterInputs(event, index, includeHistory = true)
        if (inputs.isEmpty()) return
        highlighterLastInput = inputs.last()
        if (!highlighterStraight && !highlighterHoldCancelled) {
            if (!highlighterStayedNearStart(event, index)) {
                highlighterHoldCancelled = true
                removeCallbacks(highlighterStraightenRunnable)
            } else if (event.eventTime - highlighterStartEventTime >= HIGHLIGHTER_STRAIGHTEN_DELAY_MS) {
                straightenHighlighterForHold()
            }
        }
        if (highlighterStraight) highlighterLastInput?.let(::rebuildStraightHighlighter)
        else enqueueHighlighterInputs(inputs)
        strokeLayer.invalidate()
    }

    private fun highlighterStayedNearStart(event: MotionEvent, index: Int): Boolean {
        val threshold = 3f * resources.displayMetrics.density
        for (historyIndex in 0 until event.historySize) {
            if (event.getHistoricalEventTime(historyIndex) - highlighterStartEventTime >= HIGHLIGHTER_STRAIGHTEN_DELAY_MS) continue
            if (hypot(
                    event.getHistoricalX(index, historyIndex) - highlighterDownX,
                    event.getHistoricalY(index, historyIndex) - highlighterDownY,
                ) > threshold
            ) return false
        }
        return event.eventTime - highlighterStartEventTime >= HIGHLIGHTER_STRAIGHTEN_DELAY_MS ||
            hypot(event.getX(index) - highlighterDownX, event.getY(index) - highlighterDownY) <= threshold
    }

    private fun finishHighlighterStroke(event: MotionEvent, index: Int) {
        if (!highlighterLifecycle.finish()) return
        val inputs = highlighterInputs(event, index, includeHistory = true)
        if (inputs.isNotEmpty()) highlighterLastInput = inputs.last()
        if (highlighterStraight) {
            val endpoint = highlighterLastInput ?: highlighterFirstInput ?: run {
                resetHighlighterStroke()
                return
            }
            rebuildStraightHighlighter(endpoint)
        } else {
            enqueueHighlighterInputs(inputs)
        }
        removeCallbacks(highlighterStraightenRunnable)
        val wet = highlighterWet ?: run {
            resetHighlighterStroke()
            return
        }
        val finished = runCatching {
            wet.finishInput()
            wet.updateShape(SystemClock.uptimeMillis())
            wet.toImmutable().copy(highlighterStoredBrush ?: brushSpec.toBrush())
        }.getOrNull()
        highlighterWet = null
        if (finished != null) {
            snapshot()
            strokes.add(finished)
            if (zoomEnabled && highlighterStartedInStrip) autoAdvance(finished)
            notifyChanged()
        }
        resetHighlighterStroke()
        strokeLayer.invalidate()
    }

    private fun straightenHighlighterForHold() {
        if (!highlighterLifecycle.acceptsInput() || highlighterStraight || highlighterWet == null ||
            highlighterHoldCancelled
        ) return
        val endpoint = highlighterLastInput ?: highlighterFirstInput ?: return
        highlighterStraight = true
        rebuildStraightHighlighter(endpoint)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        strokeLayer.invalidate()
    }

    private fun rebuildStraightHighlighter(endpoint: StrokeInput) {
        val first = highlighterFirstInput ?: return
        val brush = highlighterRenderBrush ?: return
        val end = StrokeInput.create(
            endpoint.x,
            endpoint.y,
            maxOf(endpoint.elapsedTimeMillis, first.elapsedTimeMillis + 1L),
            first.toolType,
            first.strokeUnitLengthCm,
            first.pressure,
            first.tiltRadians,
            first.orientationRadians,
        )
        val batch = MutableStrokeInputBatch()
        batch.add(copyInput(first))
        batch.add(end)
        val wet = InProgressStroke()
        highlighterWet = wet
        wet.start(brush)
        wet.enqueueInputs(batch.toImmutable(), MutableStrokeInputBatch().toImmutable())
        wet.updateShape(SystemClock.uptimeMillis())
    }

    private fun enqueueHighlighterInputs(inputs: List<StrokeInput>) {
        if (inputs.isEmpty()) return
        val wet = highlighterWet ?: return
        val batch = MutableStrokeInputBatch()
        inputs.forEach(batch::add)
        wet.enqueueInputs(batch.toImmutable(), MutableStrokeInputBatch().toImmutable())
        wet.updateShape(SystemClock.uptimeMillis())
    }

    private fun highlighterInputs(event: MotionEvent, index: Int, includeHistory: Boolean): List<StrokeInput> {
        val transform = if (highlighterStartedInStrip) viewToZoom else viewToPage
        val toolType = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputToolType.STYLUS
            MotionEvent.TOOL_TYPE_MOUSE -> InputToolType.MOUSE
            else -> InputToolType.TOUCH
        }
        val inputs = ArrayList<StrokeInput>(event.historySize + 1)
        fun addInput(x: Float, y: Float, eventTime: Long, pressure: Float, tilt: Float, orientation: Float) {
            val point = floatArrayOf(x, y)
            transform.mapPoints(point)
            val elapsed = maxOf(eventTime - highlighterStartEventTime, highlighterLastElapsed + 1L).coerceAtLeast(0L)
            highlighterLastElapsed = elapsed
            inputs += StrokeInput.create(
                point[0],
                point[1],
                elapsed,
                toolType,
                POINT_STROKE_UNIT_LENGTH_CM,
                pressure.takeIf { it > 0f } ?: 1f,
                tilt,
                orientation,
            )
        }
        if (includeHistory) {
            for (historyIndex in 0 until event.historySize) {
                addInput(
                    event.getHistoricalX(index, historyIndex),
                    event.getHistoricalY(index, historyIndex),
                    event.getHistoricalEventTime(historyIndex),
                    event.getHistoricalPressure(index, historyIndex),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, historyIndex),
                    event.getHistoricalOrientation(index, historyIndex),
                )
            }
        }
        addInput(
            event.getX(index),
            event.getY(index),
            event.eventTime,
            event.getPressure(index),
            event.getAxisValue(MotionEvent.AXIS_TILT, index),
            event.getOrientation(index),
        )
        return inputs
    }

    private fun resetHighlighterStroke() {
        highlighterLifecycle.cancel()
        highlighterWet = null
        highlighterStoredBrush = null
        highlighterRenderBrush = null
        highlighterFirstInput = null
        highlighterLastInput = null
        highlighterStraight = false
        highlighterHoldCancelled = false
        highlighterLastElapsed = -1L
        highlighterStartedInStrip = false
        removeCallbacks(highlighterStraightenRunnable)
    }

    private fun cancelCurrent(event: MotionEvent) {
        finishActiveLaserTrail()
        currentStrokeId?.let {
            shapeStrokeContexts.remove(it)
            wetLayer.cancelStroke(it, event)
        }
        currentStrokeId = null; currentPointerId = -1; erasing = false
        resetHighlighterStroke()
        setDrawingInputActive(false)
        strokeLayer.invalidate()
    }

    private fun setDrawingInputActive(active: Boolean) {
        if (drawingInputActive == active) return
        drawingInputActive = active
        onDrawingActiveChanged?.invoke(active)
    }

    private fun handleLaserPointer(event: MotionEvent, actionIndex: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeLaserTrail == null) {
                    activeLaserPointerId = event.getPointerId(actionIndex)
                    activeLaserTrail = LaserTrail(Path().apply { moveTo(event.getX(actionIndex), event.getY(actionIndex)) })
                        .also(laserTrails::add)
                    laserOverlay.invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val trail = activeLaserTrail ?: return true
                val pointerIndex = event.findPointerIndex(activeLaserPointerId)
                if (pointerIndex < 0) return true
                for (historyIndex in 0 until event.historySize) {
                    trail.path.lineTo(
                        event.getHistoricalX(pointerIndex, historyIndex),
                        event.getHistoricalY(pointerIndex, historyIndex),
                    )
                }
                trail.path.lineTo(event.getX(pointerIndex), event.getY(pointerIndex))
                laserOverlay.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(actionIndex) == activeLaserPointerId) {
                    activeLaserTrail?.path?.lineTo(event.getX(actionIndex), event.getY(actionIndex))
                    finishActiveLaserTrail()
                }
            }
            MotionEvent.ACTION_CANCEL -> finishActiveLaserTrail()
        }
        return true
    }

    private fun finishActiveLaserTrail() {
        val trail = activeLaserTrail ?: return
        activeLaserTrail = null
        activeLaserPointerId = -1
        setDrawingInputActive(false)
        trail.fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = LASER_FADE_DURATION_MS
            addUpdateListener {
                trail.alpha = it.animatedValue as Float
                laserOverlay.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    laserTrails.remove(trail)
                    laserOverlay.invalidate()
                }
            })
            start()
        }
    }

    private fun handleFingerNavigation(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) zoomAnimator?.cancel()
        if (event.pointerCount == 1) doubleTapDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { panning = true; lastPanX = event.x; lastPanY = event.y }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { lastPanX = focusX(event); lastPanY = focusY(event) }
            MotionEvent.ACTION_MOVE -> if (panning) {
                val fx = focusX(event); val fy = focusY(event)
                panX += fx - lastPanX; panY += fy - lastPanY
                lastPanX = fx; lastPanY = fy
                clampPan(); updateMatrices()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }

    private fun toggleDoubleTapZoom(tapX: Float, tapY: Float) {
        zoomAnimator?.cancel()
        val pagePoint = floatArrayOf(tapX, tapY)
        viewToPage.mapPoints(pagePoint)
        val targetScale = if (scale < minScale() * 2f) minScale() * 4f else minScale()
        val startScale = scale
        val startPanX = panX
        val startPanY = panY
        val targetPanX = tapX - pagePoint[0] * targetScale
        val targetPanY = tapY - pagePoint[1] * targetScale
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                scale = startScale + (targetScale - startScale) * fraction
                panX = startPanX + (targetPanX - startPanX) * fraction
                panY = startPanY + (targetPanY - startPanY) * fraction
                clampPan()
                updateMatrices()
            }
            start()
        }
    }

    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    /** STROKE removes whole strokes; PIXEL cuts out only the touched segment and keeps the rest as new strokes. */
    var pixelEraser = false

    private var lastErase: FloatArray? = null

    /** Erases along the segment from the previous eraser sample to this one, so fast pen moves cannot skip over strokes. */
    private fun eraseAt(viewX: Float, viewY: Float) {
        val pt = floatArrayOf(viewX, viewY); (if (zoomEnabled && inStrip) viewToZoom else viewToPage).mapPoints(pt)
        val prev = lastErase ?: pt
        lastErase = pt
        val radius = (if (pixelEraser) PIXEL_ERASER_RADIUS_PX else ERASER_RADIUS_PX) / (if (zoomEnabled && inStrip) zoomScale else scale)
        var changed = false
        var i = 0
        while (i < strokes.size) {
            val s = strokes[i]
            if (!hitsSegment(s, prev, pt, radius)) { i++; continue }
            changed = true
            if (!pixelEraser) { strokes.removeAt(i); continue }
            val pieces = splitStroke(s, prev, pt, radius)
            strokes.removeAt(i)
            strokes.addAll(i, pieces)
            i += pieces.size
        }
        if (changed) { eraseDirty = true; strokeLayer.invalidate(); notifyChanged() }
    }

    private fun hitsSegment(s: Stroke, a: FloatArray, b: FloatArray, r: Float): Boolean {
        val inputs = s.inputs
        if (inputs.size == 0) return false
        var px = inputs[0].x; var py = inputs[0].y
        if (inputs.size == 1) return segPointDist(a[0], a[1], b[0], b[1], px, py) <= r
        for (k in 1 until inputs.size) {
            val q = inputs[k]
            if (segSegDist(a[0], a[1], b[0], b[1], px, py, q.x, q.y) <= r) return true
            px = q.x; py = q.y
        }
        return false
    }

    private fun splitStroke(s: Stroke, a: FloatArray, b: FloatArray, r: Float): List<Stroke> {
        val inputs = s.inputs
        val pts = ArrayList<StrokeInput>()
        val step = (r / 2f).coerceAtLeast(0.5f)
        for (k in 0 until inputs.size) {
            val qi = copyInput(inputs[k])
            if (pts.isNotEmpty()) {
                val p = pts.last()
                val n = (hypot(qi.x - p.x, qi.y - p.y) / step).toInt()
                for (j in 1 until n) pts.add(lerp(p, qi, j.toFloat() / n))
            }
            pts.add(qi)
        }
        val out = ArrayList<Stroke>()
        var batch: MutableStrokeInputBatch? = null
        var count = 0
        fun flush() { val bt = batch; if (bt != null && count >= 2) out.add(Stroke(s.brush, bt)); batch = null; count = 0 }
        for (p in pts) {
            if (segPointDist(a[0], a[1], b[0], b[1], p.x, p.y) <= r) { flush(); continue }
            val bt = batch ?: MutableStrokeInputBatch().also { batch = it }
            bt.add(p); count++
        }
        flush()
        return out
    }

    private fun copyInput(p: StrokeInput) =
        StrokeInput.create(p.x, p.y, p.elapsedTimeMillis, p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)

    private fun lerp(p: StrokeInput, q: StrokeInput, t: Float) = StrokeInput.create(
        p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t,
        p.elapsedTimeMillis + ((q.elapsedTimeMillis - p.elapsedTimeMillis) * t).toLong(),
        p.toolType, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians
    )

    private fun segPointDist(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float) = (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)

    private fun segSegDist(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0f
        return minOf(
            segPointDist(ax, ay, bx, by, cx, cy), segPointDist(ax, ay, bx, by, dx, dy),
            segPointDist(cx, cy, dx, dy, ax, ay), segPointDist(cx, cy, dx, dy, bx, by)
        )
    }

    private fun report(event: MotionEvent, idx: Int) {
        val cb = onSample ?: return
        val tool = when (event.getToolType(idx)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"; MotionEvent.TOOL_TYPE_ERASER -> "eraser"; MotionEvent.TOOL_TYPE_FINGER -> "finger"; else -> "other"
        }
        cb(PointerSample(
            toolType = tool, pressure = event.getPressure(idx),
            tilt = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, idx).toDouble()).toFloat(),
            orientation = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx).toDouble()).toFloat(),
            handlerLatencyMs = SystemClock.uptimeMillis() - event.eventTime, historySize = event.historySize, strokeCount = strokes.size,
        ))
    }

    private companion object {
        const val ZOOM_LINE_PT = 56f
        const val ZOOM_STRIP_FRACTION = 0.32f
        const val MARGIN_PX = 12f
        const val ERASER_RADIUS_PX = 14f
        const val PIXEL_ERASER_RADIUS_PX = 9f
        const val RULER_THICKNESS_PT = 56f
        const val RULER_CORNER_RADIUS_PT = 6f
        const val RULER_HANDLE_RADIUS_PT = 14f
        const val RULER_MILLIMETER_PT = 2.835f
        const val RULER_FULL_TICK_PT = 12f
        const val RULER_HALF_TICK_PT = 7f
        const val RULER_MILLIMETER_TICK_PT = 3f
        const val RULER_LABEL_TEXT_PT = 12f
        const val RULER_LABEL_BASELINE_PT = 17f
        const val RULER_ANGLE_TEXT_PT = 12f
        const val RULER_ANGLE_BASELINE_PT = 4f
        const val RULER_SNAP_DISTANCE_PX = 24f
    }
}
