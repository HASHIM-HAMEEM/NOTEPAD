package com.hashim.notepad.ink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
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
import androidx.input.motionprediction.MotionEventPredictor
import com.hashim.notepad.data.PageKind
import com.hashim.notepad.data.TextBlock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pen surface. World coordinates are PDF points on the page (origin top-left). A page bitmap
 * (PDF render) or template sits at the bottom, finished strokes are drawn by [CanvasStrokeRenderer],
 * and [InProgressStrokesView] draws the wet stroke on a front buffer on top.
 * Stylus draws; fingers pan/zoom unless [fingerDraws] is on.
 */
class InkSurfaceView(context: Context) : FrameLayout(context) {

    var brushSpec: BrushSpec = BrushSpec(InkTool.PEN, 0xFF1A1A1A.toInt(), 2.5f)
    var fingerDraws: Boolean = false
    var onSample: ((PointerSample) -> Unit)? = null
    var onStrokesChanged: ((List<Stroke>) -> Unit)? = null
    var onZoomChanged: ((Int) -> Unit)? = null
    /** Text tool: tap on empty space → (xPt, yPt, null); tap on a block → (xPt, yPt, block). */
    var onTextTap: ((Float, Float, TextBlock?) -> Unit)? = null
    var textBlocks: List<TextBlock> = emptyList()
        set(value) { field = value; strokeLayer.invalidate() }
    var selectedTextBlockId: String? = null
        set(value) { field = value; strokeLayer.invalidate() }

    var pageWidthPt = 595f; private set
    var pageHeightPt = 842f; private set
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
    private val history = ArrayList<List<Stroke>>()
    private val future = ArrayList<List<Stroke>>()
    private var eraseDirty = false

    /** Call before any mutation of [strokes]; keeps the last 100 states. */
    private fun snapshot() { history.add(ArrayList(strokes)); if (history.size > 100) history.removeAt(0); future.clear() }
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
        for (s in strokes) renderer.draw(canvas, s, zoomToView)
        for (b in textBlocks) TextBlockPainter.draw(canvas, b, false)
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
    private var currentStrokeId: InProgressStrokeId? = null
    private var currentPointerId = -1
    private var erasing = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var panning = false
    private var zoomAnimator: ValueAnimator? = null

    private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            toggleDoubleTapZoom(event.x, event.y)
            return true
        }
    })

    // Lasso state (page coordinates)
    private val lassoPath = Path()
    private val lassoPoints = ArrayList<Float>()
    private var lassoActive = false
    private val selected = LinkedHashSet<Stroke>()
    private var selectionBounds: RectF? = null
    private var movingSelection = false
    private var moveLastX = 0f
    private var moveLastY = 0f
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E5EFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val selectionFill = Paint().apply { color = 0x141E5EFF; style = Paint.Style.FILL }

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
            for (s in strokes) renderer.draw(canvas, s, pageToView)
            canvas.restore()
            selectionBounds?.let { b ->
                canvas.save(); canvas.concat(pageToView)
                canvas.drawRect(b, selectionFill)
                canvas.save(); canvas.scale(1f, 1f); canvas.restore()
                canvas.restore()
                val vb = RectF(b); pageToView.mapRect(vb); canvas.drawRect(vb, lassoPaint)
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
                for (b in textBlocks) TextBlockPainter.draw(canvas, b, b.id == selectedTextBlockId)
                canvas.restore()
            }
            canvas.restore()
            drawRuler(canvas)
            drawZoomStrip(canvas)
        }

    }

    private val wetLayer = InProgressStrokesView(context).apply {
        eagerInit()
        addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                snapshot()
                this@InkSurfaceView.strokes.addAll(strokes.values)
                if (zoomEnabled && inStrip) strokes.values.lastOrNull()?.let { autoAdvance(it) }
                strokeLayer.invalidate()
                post { removeFinishedStrokes(strokes.keys) }
                notifyChanged()
            }
        })
    }

    init {
        setWillNotDraw(true)
        updateRulerPaints()
        addView(strokeLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wetLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        rulerFillPaint.setShadowLayer(8f / scale, 0f, 3f / scale, 0x44000000)

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
            val fullCentimeter = mark % 2 == 0
            val tickHeight = if (fullCentimeter) RULER_FULL_TICK_PT else RULER_HALF_TICK_PT
            canvas.drawLine(x, top, x, top + tickHeight, rulerTickPaint)
            if (fullCentimeter) {
                rulerTextPaint.textSize = RULER_LABEL_TEXT_PT
                canvas.drawText(((mark / 2)).toString(), x, top + tickHeight + RULER_LABEL_BASELINE_PT, rulerTextPaint)
            }
            x += RULER_HALF_CENTIMETER_PT
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
        val gap = 24f
        when (pageKind) {
            PageKind.LINED -> { var y = gap * 3; while (y < pageHeightPt - gap) { canvas.drawLine(gap * 2, y, pageWidthPt - gap * 2, y, templatePaint); y += gap } }
            PageKind.GRID -> {
                var y = gap; while (y < pageHeightPt) { canvas.drawLine(0f, y, pageWidthPt, y, templatePaint); y += gap }
                var x = gap; while (x < pageWidthPt) { canvas.drawLine(x, 0f, x, pageHeightPt, templatePaint); x += gap }
            }
            else -> Unit
        }
    }

    fun setPageGeometry(widthPt: Float, heightPt: Float, kind: PageKind) {
        pageWidthPt = widthPt; pageHeightPt = heightPt; pageKind = kind
        hasPageGeometry = true
        rulerInitialized = false
        fitToWidth()
        placeRulerIfNeeded()
    }

    fun setPaperColor(argb: Int) { paperPaint.color = argb; templatePaint.color = if (argb == 0xFF1F1F22.toInt()) 0xFF3A3A40.toInt() else 0xFFC9CDD6.toInt(); strokeLayer.invalidate() }
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

    fun setStrokes(list: List<Stroke>) { strokes.clear(); strokes.addAll(list); history.clear(); future.clear(); strokeLayer.invalidate(); notifyChanged() }
    fun strokes(): List<Stroke> = strokes.toList()
    fun canUndo() = history.isNotEmpty()
    fun canRedo() = future.isNotEmpty()
    fun undo() {
        if (history.isEmpty()) return
        future.add(ArrayList(strokes)); strokes.clear(); strokes.addAll(history.removeAt(history.size - 1))
        clearSelection(); strokeLayer.invalidate(); notifyChanged()
    }
    fun redo() {
        if (future.isEmpty()) return
        history.add(ArrayList(strokes)); strokes.clear(); strokes.addAll(future.removeAt(future.size - 1))
        clearSelection(); strokeLayer.invalidate(); notifyChanged()
    }
    fun clear() { if (strokes.isEmpty()) return; snapshot(); strokes.clear(); clearSelection(); strokeLayer.invalidate(); notifyChanged() }

    fun hasSelection() = selected.isNotEmpty()

    fun clearSelection() { selected.clear(); selectionBounds = null; strokeLayer.invalidate(); onSelectionChanged?.invoke(false) }

    fun deleteSelection() {
        if (selected.isEmpty()) return
        snapshot(); strokes.removeAll(selected); clearSelection(); notifyChanged()
    }

    private fun handleLasso(event: MotionEvent, idx: Int): Boolean {
        val pt = floatArrayOf(event.getX(idx), event.getY(idx)); viewToPage.mapPoints(pt)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val b = selectionBounds
                if (b != null && b.contains(pt[0], pt[1])) { snapshot(); movingSelection = true; moveLastX = pt[0]; moveLastY = pt[1] }
                else { clearSelection(); lassoActive = true; lassoPath.reset(); lassoPath.moveTo(pt[0], pt[1]); lassoPoints.clear(); lassoPoints.add(pt[0]); lassoPoints.add(pt[1]) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (movingSelection) { moveSelection(pt[0] - moveLastX, pt[1] - moveLastY); moveLastX = pt[0]; moveLastY = pt[1] }
                else if (lassoActive) { lassoPath.lineTo(pt[0], pt[1]); lassoPoints.add(pt[0]); lassoPoints.add(pt[1]); strokeLayer.invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (movingSelection) { movingSelection = false; notifyChanged() }
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
    }

    private fun moveSelection(dx: Float, dy: Float) {
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
        strokeLayer.invalidate()
    }

    private fun notifyChanged() { onStrokesChanged?.invoke(strokes.toList()) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToWidth()
        placeRulerIfNeeded()
    }

    private fun minScale(): Float = if (width > 0) (width - 2 * MARGIN_PX) / pageWidthPt else 1f

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
        rulerFillPaint.color = if (rulerDarkTheme) 0x1FFFFFFF else 0x8CFFFFFF.toInt()
        rulerOutlinePaint.color = 0xBFFFFFFF.toInt()
        rulerTickPaint.color = 0xFF5A5248.toInt()
        rulerTextPaint.color = if (rulerDarkTheme) 0xFFEDE6DA.toInt() else 0xFF4A4036.toInt()
        rulerHandlePaint.color = if (rulerDarkTheme) 0x335A5248 else 0x99FFFFFF.toInt()
        rulerHandleOutlinePaint.color = 0xBFFFFFFF.toInt()
        rulerAnglePillPaint.color = if (rulerDarkTheme) 0xCC2B2823.toInt() else 0xCCFFFFFF.toInt()
    }

    private fun clampPan() {
        val pageW = pageWidthPt * scale; val pageH = pageHeightPt * scale
        panX = if (pageW <= width) (width - pageW) / 2f else panX.coerceIn(width - pageW - MARGIN_PX, MARGIN_PX)
        panY = if (pageH <= height - 2 * MARGIN_PX) MARGIN_PX else panY.coerceIn(height - pageH - MARGIN_PX, MARGIN_PX)
    }

    private fun updateMatrices() {
        pageToView.reset(); pageToView.postScale(scale, scale); pageToView.postTranslate(panX, panY)
        pageToView.invert(viewToPage)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val idx = event.actionIndex
        val toolType = event.getToolType(idx)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (zoomEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
            inStrip = event.y >= stripTop
        }
        if (handleRulerTouch(event, isStylus)) return true
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
        if (!isStylus && !fingerDraws) return handleFingerNavigation(event)
        if (!isStylus && event.pointerCount > 1) { cancelCurrent(event); return handleFingerNavigation(event) }

        if (brushSpec.tool == InkTool.LASSO) return handleLasso(event, if (event.actionMasked == MotionEvent.ACTION_MOVE) event.findPointerIndex(currentPointerId).takeIf { it >= 0 } ?: 0 else idx).also {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) currentPointerId = event.getPointerId(idx)
        }
        if (brushSpec.tool == InkTool.TEXT) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val pt = floatArrayOf(event.x, event.y); viewToPage.mapPoints(pt)
                onTextTap?.invoke(pt[0], pt[1], textBlocks.lastOrNull { TextBlockPainter.hit(it, pt[0], pt[1]) })
            }
            return true
        }
        val eraserNow = toolType == MotionEvent.TOOL_TYPE_ERASER || brushSpec.tool == InkTool.ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (currentStrokeId != null || erasing) return true
                currentPointerId = event.getPointerId(idx)
                erasing = eraserNow
                if (erasing) { lastErase = null; snapshot(); eraseDirty = false; eraseAt(event.getX(idx), event.getY(idx)) }
                else {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) beginRulerSnap(event, idx, isStylus, eraserNow)
                    currentStrokeId = withRulerSnapEvent(event, idx) {
                        wetLayer.startStroke(it, currentPointerId, brushSpec.toBrush(), if (zoomEnabled && inStrip) viewToZoom else viewToPage, identity)
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
                } else currentStrokeId?.let { strokeId ->
                    withRulerSnapEvent(event, pi) { adjusted ->
                        wetLayer.addToStroke(adjusted, currentPointerId, strokeId, predictor.predict())
                    }
                }
                report(event, pi); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(idx) != currentPointerId) return true
                if (erasing) { eraseAt(event.getX(idx), event.getY(idx)); if (!eraseDirty && history.isNotEmpty()) history.removeAt(history.size - 1) }
                currentStrokeId?.let { strokeId ->
                    withRulerSnapEvent(event, idx) { adjusted ->
                        wetLayer.finishStroke(adjusted, currentPointerId, strokeId)
                    }
                }
                currentStrokeId = null; currentPointerId = -1; erasing = false; rulerSnapActive = false; return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelCurrent(event); rulerSnapActive = false; return true }
        }
        return false
    }

    private fun cancelCurrent(event: MotionEvent) {
        currentStrokeId?.let { wetLayer.cancelStroke(it, event) }
        currentStrokeId = null; currentPointerId = -1; erasing = false
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
        const val RULER_HALF_CENTIMETER_PT = 14.175f
        const val RULER_FULL_TICK_PT = 12f
        const val RULER_HALF_TICK_PT = 7f
        const val RULER_LABEL_TEXT_PT = 9f
        const val RULER_LABEL_BASELINE_PT = 17f
        const val RULER_ANGLE_TEXT_PT = 12f
        const val RULER_ANGLE_BASELINE_PT = 4f
        const val RULER_SNAP_DISTANCE_PX = 24f
    }
}
