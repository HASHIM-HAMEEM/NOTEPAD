package com.hashim.notepad.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.hashim.notepad.data.PageKind
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

    var pageWidthPt = 595f; private set
    var pageHeightPt = 842f; private set
    private var pageKind = PageKind.BLANK

    private val strokes = ArrayList<Stroke>()
    private val undone = ArrayList<Stroke>()
    private val identity = Matrix()
    private val pageToView = Matrix()
    private val viewToPage = Matrix()
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var pageBitmap: Bitmap? = null
    private var currentStrokeId: InProgressStrokeId? = null
    private var currentPointerId = -1
    private var erasing = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var panning = false

    private val renderer = CanvasStrokeRenderer.create()
    private val predictor = MotionEventPredictor.newInstance(this)
    private val templatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9CDD6.toInt(); strokeWidth = 0f; style = Paint.Style.STROKE }
    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newScale = (scale * d.scaleFactor).coerceIn(minScale(), minScale() * 6f)
            val f = newScale / scale
            panX = d.focusX - (d.focusX - panX) * f
            panY = d.focusY - (d.focusY - panY) * f
            scale = newScale
            clampPan(); updateMatrices(); return true
        }
    })

    private val strokeLayer = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFFE9EAEE.toInt())
            canvas.save()
            canvas.concat(pageToView)
            canvas.drawRect(2f, 3f, pageWidthPt + 2f, pageHeightPt + 3f, shadowPaint)
            canvas.drawRect(0f, 0f, pageWidthPt, pageHeightPt, paperPaint)
            drawTemplate(canvas)
            canvas.restore()
            pageBitmap?.let { bmp ->
                val m = Matrix(pageToView)
                m.preScale(pageWidthPt / bmp.width, pageHeightPt / bmp.height)
                canvas.drawBitmap(bmp, m, bitmapPaint)
            }
            for (s in strokes) renderer.draw(canvas, s, pageToView)
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
    }

    private val wetLayer = InProgressStrokesView(context).apply {
        eagerInit()
        addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                this@InkSurfaceView.strokes.addAll(strokes.values)
                undone.clear()
                strokeLayer.invalidate()
                post { removeFinishedStrokes(strokes.keys) }
                notifyChanged()
            }
        })
    }

    init {
        setWillNotDraw(true)
        addView(strokeLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wetLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setPageGeometry(widthPt: Float, heightPt: Float, kind: PageKind) {
        pageWidthPt = widthPt; pageHeightPt = heightPt; pageKind = kind
        fitToWidth()
    }

    fun setPage(bitmap: Bitmap?) { pageBitmap = bitmap; strokeLayer.invalidate() }

    fun setStrokes(list: List<Stroke>) { strokes.clear(); strokes.addAll(list); undone.clear(); strokeLayer.invalidate(); notifyChanged() }
    fun strokes(): List<Stroke> = strokes.toList()
    fun canUndo() = strokes.isNotEmpty()
    fun canRedo() = undone.isNotEmpty()
    fun undo() { if (strokes.isNotEmpty()) { undone.add(strokes.removeAt(strokes.size - 1)); strokeLayer.invalidate(); notifyChanged() } }
    fun redo() { if (undone.isNotEmpty()) { strokes.add(undone.removeAt(undone.size - 1)); strokeLayer.invalidate(); notifyChanged() } }
    fun clear() { strokes.clear(); undone.clear(); strokeLayer.invalidate(); notifyChanged() }

    private fun notifyChanged() { onStrokesChanged?.invoke(strokes.toList()) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fitToWidth() }

    private fun minScale(): Float = if (width > 0) (width - 2 * MARGIN_PX) / pageWidthPt else 1f

    private fun fitToWidth() {
        if (width == 0) return
        scale = minScale(); panX = MARGIN_PX; panY = MARGIN_PX
        updateMatrices()
    }

    private fun clampPan() {
        val pageW = pageWidthPt * scale; val pageH = pageHeightPt * scale
        panX = if (pageW <= width) (width - pageW) / 2f else panX.coerceIn(width - pageW - MARGIN_PX, MARGIN_PX)
        panY = if (pageH <= height - 2 * MARGIN_PX) MARGIN_PX else panY.coerceIn(height - pageH - MARGIN_PX, MARGIN_PX)
    }

    private fun updateMatrices() {
        pageToView.reset(); pageToView.postScale(scale, scale); pageToView.postTranslate(panX, panY)
        pageToView.invert(viewToPage)
        strokeLayer.invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val idx = event.actionIndex
        val toolType = event.getToolType(idx)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus && !fingerDraws) return handleFingerNavigation(event)
        if (!isStylus && event.pointerCount > 1) { cancelCurrent(event); return handleFingerNavigation(event) }

        val eraserNow = toolType == MotionEvent.TOOL_TYPE_ERASER || brushSpec.tool == InkTool.ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (currentStrokeId != null || erasing) return true
                currentPointerId = event.getPointerId(idx)
                erasing = eraserNow
                if (erasing) eraseAt(event.getX(idx), event.getY(idx))
                else currentStrokeId = wetLayer.startStroke(event, currentPointerId, brushSpec.toBrush(), viewToPage, identity)
                report(event, idx); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = event.findPointerIndex(currentPointerId)
                if (pi < 0) return true
                if (erasing) {
                    for (h in 0 until event.historySize) eraseAt(event.getHistoricalX(pi, h), event.getHistoricalY(pi, h))
                    eraseAt(event.getX(pi), event.getY(pi))
                } else currentStrokeId?.let { wetLayer.addToStroke(event, currentPointerId, it, predictor.predict()) }
                report(event, pi); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(idx) != currentPointerId) return true
                currentStrokeId?.let { wetLayer.finishStroke(event, currentPointerId, it) }
                currentStrokeId = null; currentPointerId = -1; erasing = false; return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelCurrent(event); return true }
        }
        return false
    }

    private fun cancelCurrent(event: MotionEvent) {
        currentStrokeId?.let { wetLayer.cancelStroke(it, event) }
        currentStrokeId = null; currentPointerId = -1; erasing = false
    }

    private fun handleFingerNavigation(event: MotionEvent): Boolean {
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

    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun eraseAt(viewX: Float, viewY: Float) {
        val pt = floatArrayOf(viewX, viewY); viewToPage.mapPoints(pt)
        val radius = ERASER_RADIUS_PX / scale
        val it = strokes.iterator()
        var removed = false
        while (it.hasNext()) {
            val inputs = it.next().inputs
            var hit = false; var i = 0
            while (i < inputs.size && !hit) { val p = inputs[i]; if (hypot(p.x - pt[0], p.y - pt[1]) <= radius) hit = true; i++ }
            if (hit) { it.remove(); removed = true }
        }
        if (removed) { strokeLayer.invalidate(); notifyChanged() }
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

    private companion object { const val MARGIN_PX = 12f; const val ERASER_RADIUS_PX = 14f }
}
