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
    /** Text tool: tap on empty space → (xPt, yPt, null); tap on a block → (xPt, yPt, block). */
    var onTextTap: ((Float, Float, TextBlock?) -> Unit)? = null
    var textBlocks: List<TextBlock> = emptyList()
        set(value) { field = value; strokeLayer.invalidate() }
    var selectedTextBlockId: String? = null
        set(value) { field = value; strokeLayer.invalidate() }

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
            if (lassoActive) { canvas.save(); canvas.concat(pageToView); canvas.drawPath(lassoPath, lassoPaint); canvas.restore() }
            if (textBlocks.isNotEmpty()) {
                canvas.save(); canvas.concat(pageToView)
                for (b in textBlocks) TextBlockPainter.draw(canvas, b, b.id == selectedTextBlockId)
                canvas.restore()
            }
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

    fun setPaperColor(argb: Int) { paperPaint.color = argb; templatePaint.color = if (argb == 0xFF1F1F22.toInt()) 0xFF3A3A40.toInt() else 0xFFC9CDD6.toInt(); strokeLayer.invalidate() }

    fun setPage(bitmap: Bitmap?) { pageBitmap = bitmap; strokeLayer.invalidate() }

    fun setStrokes(list: List<Stroke>) { strokes.clear(); strokes.addAll(list); undone.clear(); strokeLayer.invalidate(); notifyChanged() }
    fun strokes(): List<Stroke> = strokes.toList()
    fun canUndo() = strokes.isNotEmpty()
    fun canRedo() = undone.isNotEmpty()
    fun undo() { if (strokes.isNotEmpty()) { undone.add(strokes.removeAt(strokes.size - 1)); strokeLayer.invalidate(); notifyChanged() } }
    fun redo() { if (undone.isNotEmpty()) { strokes.add(undone.removeAt(undone.size - 1)); strokeLayer.invalidate(); notifyChanged() } }
    fun clear() { strokes.clear(); undone.clear(); clearSelection(); strokeLayer.invalidate(); notifyChanged() }

    fun hasSelection() = selected.isNotEmpty()

    fun clearSelection() { selected.clear(); selectionBounds = null; strokeLayer.invalidate(); onSelectionChanged?.invoke(false) }

    fun deleteSelection() {
        if (selected.isEmpty()) return
        strokes.removeAll(selected); undone.clear(); clearSelection(); notifyChanged()
    }

    private fun handleLasso(event: MotionEvent, idx: Int): Boolean {
        val pt = floatArrayOf(event.getX(idx), event.getY(idx)); viewToPage.mapPoints(pt)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val b = selectionBounds
                if (b != null && b.contains(pt[0], pt[1])) { movingSelection = true; moveLastX = pt[0]; moveLastY = pt[1] }
                else { clearSelection(); lassoActive = true; lassoPath.reset(); lassoPath.moveTo(pt[0], pt[1]); lassoPoints.clear(); lassoPoints.add(pt[0]); lassoPoints.add(pt[1]) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (movingSelection) { moveSelection(pt[0] - moveLastX, pt[1] - moveLastY); moveLastX = pt[0]; moveLastY = pt[1] }
                else if (lassoActive) { lassoPath.lineTo(pt[0], pt[1]); lassoPoints.add(pt[0]); lassoPoints.add(pt[1]); strokeLayer.invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (movingSelection) { movingSelection = false; undone.clear(); notifyChanged() }
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
        for (s in strokes) {
            val inputs = s.inputs
            var inside = 0
            for (i in 0 until inputs.size) { val p = inputs[i]; if (region.contains(p.x.toInt(), p.y.toInt())) inside++ }
            if (inputs.size > 0 && inside * 2 >= inputs.size) {
                selected.add(s)
                for (i in 0 until inputs.size) { val p = inputs[i]; if (sel.isEmpty) sel.set(p.x, p.y, p.x, p.y) else sel.union(p.x, p.y) }
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
                if (erasing) { lastErase = null; eraseAt(event.getX(idx), event.getY(idx)) }
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
                if (erasing) eraseAt(event.getX(idx), event.getY(idx))
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

    /** STROKE removes whole strokes; PIXEL cuts out only the touched segment and keeps the rest as new strokes. */
    var pixelEraser = false

    private var lastErase: FloatArray? = null

    /** Erases along the segment from the previous eraser sample to this one, so fast pen moves cannot skip over strokes. */
    private fun eraseAt(viewX: Float, viewY: Float) {
        val pt = floatArrayOf(viewX, viewY); viewToPage.mapPoints(pt)
        val prev = lastErase ?: pt
        lastErase = pt
        val radius = (if (pixelEraser) PIXEL_ERASER_RADIUS_PX else ERASER_RADIUS_PX) / scale
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
        if (changed) { undone.clear(); strokeLayer.invalidate(); notifyChanged() }
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

    private companion object { const val MARGIN_PX = 12f; const val ERASER_RADIUS_PX = 14f; const val PIXEL_ERASER_RADIUS_PX = 9f }
}
