package com.hashim.notepad.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.hypot

/**
 * Pen surface for the Phase-00 spike. A bitmap (PDF page) sits at the bottom, finished
 * strokes are drawn by [CanvasStrokeRenderer] in the middle, and [InProgressStrokesView]
 * draws the wet stroke on a front buffer on top. Stroke coordinates are view pixels.
 */
class InkSurfaceView(context: Context) : FrameLayout(context) {

    var brushSpec: BrushSpec = BrushSpec(InkTool.PEN, 0xFF1A1A1A.toInt(), 3f)
    var fingerDraws: Boolean = false
    var onSample: ((PointerSample) -> Unit)? = null
    var onStrokesChanged: ((List<Stroke>) -> Unit)? = null

    private val strokes = ArrayList<Stroke>()
    private val undone = ArrayList<Stroke>()
    private val identity = Matrix()
    private var pageBitmap: Bitmap? = null
    private var currentStrokeId: InProgressStrokeId? = null
    private var currentPointerId = -1
    private var erasing = false

    private val renderer = CanvasStrokeRenderer.create()
    private val predictor = MotionEventPredictor.newInstance(this)

    private val strokeLayer = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            pageBitmap?.let { canvas.drawColor(0xFFE6E6E6.toInt()); canvas.drawBitmap(it, 0f, 0f, null) }
            for (s in strokes) renderer.draw(canvas, s, identity)
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

    fun setPage(bitmap: Bitmap?) {
        pageBitmap = bitmap
        strokeLayer.invalidate()
    }

    fun setStrokes(list: List<Stroke>) {
        strokes.clear(); strokes.addAll(list); undone.clear()
        strokeLayer.invalidate(); notifyChanged()
    }

    fun strokes(): List<Stroke> = strokes.toList()

    fun undo() {
        if (strokes.isNotEmpty()) { undone.add(strokes.removeAt(strokes.size - 1)); strokeLayer.invalidate(); notifyChanged() }
    }

    fun redo() {
        if (undone.isNotEmpty()) { strokes.add(undone.removeAt(undone.size - 1)); strokeLayer.invalidate(); notifyChanged() }
    }

    fun clear() { strokes.clear(); undone.clear(); strokeLayer.invalidate(); notifyChanged() }

    private fun notifyChanged() { onStrokesChanged?.invoke(strokes.toList()) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val idx = event.actionIndex
        val toolType = event.getToolType(idx)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus && !fingerDraws) return false

        val eraserNow = toolType == MotionEvent.TOOL_TYPE_ERASER ||
            brushSpec.tool == InkTool.ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (currentStrokeId != null) return true
                currentPointerId = event.getPointerId(idx)
                erasing = eraserNow
                if (erasing) eraseAt(event.getX(idx), event.getY(idx))
                else currentStrokeId = wetLayer.startStroke(event, currentPointerId, brushSpec.toBrush())
                report(event, idx)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = event.findPointerIndex(currentPointerId)
                if (pi < 0) return true
                if (erasing) {
                    for (h in 0 until event.historySize) eraseAt(event.getHistoricalX(pi, h), event.getHistoricalY(pi, h))
                    eraseAt(event.getX(pi), event.getY(pi))
                } else {
                    currentStrokeId?.let { wetLayer.addToStroke(event, currentPointerId, it, predictor.predict()) }
                }
                report(event, pi)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(idx) != currentPointerId) return true
                currentStrokeId?.let { wetLayer.finishStroke(event, currentPointerId, it) }
                currentStrokeId = null; currentPointerId = -1; erasing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                currentStrokeId?.let { wetLayer.cancelStroke(it, event) }
                currentStrokeId = null; currentPointerId = -1; erasing = false
                return true
            }
        }
        return false
    }

    private fun eraseAt(x: Float, y: Float) {
        val radius = 18f
        val it = strokes.iterator()
        var removed = false
        while (it.hasNext()) {
            val s = it.next()
            val inputs = s.inputs
            var hit = false
            var i = 0
            while (i < inputs.size && !hit) {
                val p = inputs[i]
                if (hypot(p.x - x, p.y - y) <= radius) hit = true
                i++
            }
            if (hit) { it.remove(); removed = true }
        }
        if (removed) { strokeLayer.invalidate(); notifyChanged() }
    }

    private fun report(event: MotionEvent, idx: Int) {
        val cb = onSample ?: return
        val tool = when (event.getToolType(idx)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            else -> "other"
        }
        cb(
            PointerSample(
                toolType = tool,
                pressure = event.getPressure(idx),
                tilt = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, idx).toDouble()).toFloat(),
                orientation = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx).toDouble()).toFloat(),
                handlerLatencyMs = SystemClock.uptimeMillis() - event.eventTime,
                historySize = event.historySize,
                strokeCount = strokes.size,
            )
        )
    }
}
