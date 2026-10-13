package com.hashim.notepad.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

sealed interface Shape {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Shape
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : Shape
    data class Polygon(val points: List<Point>) : Shape

    data class Point(val x: Float, val y: Float)
}

object ShapeRecognizer {
    private const val LINE_STRAIGHTNESS = 0.93f
    private const val CLOSED_GAP_FRACTION = 0.2f
    private const val SIMPLIFY_FRACTION = 0.06f
    private const val MIN_CORNER_ANGLE_DEGREES = 155f
    private const val RECTANGLE_EDGE_ANGLE_DEGREES = 12f
    private const val LINE_SNAP_ANGLE_DEGREES = 6f
    private const val ELLIPSE_RADIAL_VARIATION = 0.25f

    fun recognize(xs: FloatArray, ys: FloatArray): Shape? {
        if (xs.size != ys.size || xs.size < 2) return null
        if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) return null

        val points = trimTrailingStationarySamples(compact(xs.indices.map { Shape.Point(xs[it], ys[it]) }))
        if (points.size < 2) return null

        val bounds = bounds(points)
        val diagonal = distance(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (diagonal <= 0f) return null

        val pathLength = points.zipWithNext().sumOf { (a, b) -> distance(a.x, a.y, b.x, b.y).toDouble() }.toFloat()
        val endpointGap = distance(points.first().x, points.first().y, points.last().x, points.last().y)
        if (pathLength > 0f && endpointGap / pathLength >= LINE_STRAIGHTNESS) {
            return snapLine(points.first(), points.last())
        }
        if (endpointGap > diagonal * CLOSED_GAP_FRACTION) return null

        val simplified = mergeNearlyCollinear(simplifyClosed(points, diagonal * SIMPLIFY_FRACTION))
        when (simplified.size) {
            3 -> return Shape.Polygon(simplified)
            4 -> return Shape.Polygon(snapRectangle(simplified) ?: simplified)
        }

        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        val radii = points.map { distance(it.x, it.y, centerX, centerY) }
        val mean = radii.average().toFloat()
        if (mean <= 0f) return null
        val variance = radii.sumOf { ((it - mean) * (it - mean)).toDouble() } / radii.size
        val coefficientOfVariation = sqrt(variance).toFloat() / mean
        if (coefficientOfVariation >= ELLIPSE_RADIAL_VARIATION) return null

        var rx = (bounds.right - bounds.left) / 2f
        var ry = (bounds.bottom - bounds.top) / 2f
        val aspectRatio = maxOf(rx, ry) / minOf(rx, ry).coerceAtLeast(Float.MIN_VALUE)
        if (aspectRatio in 0.9f..1.1f) {
            val radius = (rx + ry) / 2f
            rx = radius
            ry = radius
        }
        return Shape.Ellipse(cx = (bounds.left + bounds.right) / 2f, cy = (bounds.top + bounds.bottom) / 2f, rx = rx, ry = ry)
    }

    private fun compact(points: List<Shape.Point>): List<Shape.Point> {
        val result = ArrayList<Shape.Point>(points.size)
        for (point in points) {
            val previous = result.lastOrNull()
            if (previous == null || distance(previous.x, previous.y, point.x, point.y) > 0.001f) {
                result.add(point)
            }
        }
        return result
    }

    private fun trimTrailingStationarySamples(points: List<Shape.Point>): List<Shape.Point> {
        if (points.size < 3) return points
        val box = bounds(points)
        val diagonal = distance(box.left, box.top, box.right, box.bottom)
        val closedGap = distance(points.first().x, points.first().y, points.last().x, points.last().y)
        if (diagonal > 0f && closedGap <= diagonal * CLOSED_GAP_FRACTION) return points

        val tolerance = maxOf(0.01f, diagonal * 0.005f)
        var stationaryStart = points.lastIndex
        val end = points.last()
        while (
            stationaryStart > 1 &&
            distance(points[stationaryStart - 1].x, points[stationaryStart - 1].y, end.x, end.y) <= tolerance
        ) {
            stationaryStart--
        }
        return if (stationaryStart < points.lastIndex) points.take(stationaryStart + 1) else points
    }

    private fun simplifyClosed(points: List<Shape.Point>, epsilon: Float): List<Shape.Point> {
        val open = points.toMutableList()
        if (open.size > 2 && distance(open.first().x, open.first().y, open.last().x, open.last().y) <= epsilon) {
            open.removeAt(open.lastIndex)
        }
        if (open.size < 3) return emptyList()

        val closed = open + open.first()
        val pivot = (1 until closed.lastIndex).maxByOrNull { index ->
            distance(closed.first().x, closed.first().y, closed[index].x, closed[index].y)
        } ?: return emptyList()
        val firstArc = simplifyOpen(closed.subList(0, pivot + 1), epsilon)
        val secondArc = simplifyOpen(closed.subList(pivot, closed.lastIndex + 1), epsilon)
        return compact(firstArc + secondArc.drop(1).dropLast(1))
    }

    private fun simplifyOpen(points: List<Shape.Point>, epsilon: Float): List<Shape.Point> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        val farthest = (1 until points.lastIndex).maxByOrNull { index ->
            perpendicularDistance(points[index], first, last)
        } ?: return points
        val maxDistance = perpendicularDistance(points[farthest], first, last)
        if (maxDistance <= epsilon) return listOf(first, last)
        return compact(
            simplifyOpen(points.subList(0, farthest + 1), epsilon) +
                simplifyOpen(points.subList(farthest, points.size), epsilon).drop(1),
        )
    }

    private fun mergeNearlyCollinear(points: List<Shape.Point>): List<Shape.Point> {
        val vertices = points.toMutableList()
        var changed = true
        while (changed && vertices.size > 3) {
            changed = false
            for (index in vertices.indices) {
                val previous = vertices[(index - 1 + vertices.size) % vertices.size]
                val current = vertices[index]
                val next = vertices[(index + 1) % vertices.size]
                if (cornerAngle(previous, current, next) > MIN_CORNER_ANGLE_DEGREES) {
                    vertices.removeAt(index)
                    changed = true
                    break
                }
            }
        }
        return vertices
    }

    private fun cornerAngle(previous: Shape.Point, current: Shape.Point, next: Shape.Point): Float {
        val ax = previous.x - current.x
        val ay = previous.y - current.y
        val bx = next.x - current.x
        val by = next.y - current.y
        val denominator = hypot(ax, ay) * hypot(bx, by)
        if (denominator <= 0f) return 180f
        val cosine = ((ax * bx + ay * by) / denominator).coerceIn(-1f, 1f)
        return (acos(cosine) * 180f / PI).toFloat()
    }

    private fun snapRectangle(points: List<Shape.Point>): List<Shape.Point>? {
        val edges = points.indices.map { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val angle = Math.toDegrees(atan2(abs(b.y - a.y).toDouble(), abs(b.x - a.x).toDouble())).toFloat()
            angle <= RECTANGLE_EDGE_ANGLE_DEGREES || 90f - angle <= RECTANGLE_EDGE_ANGLE_DEGREES
        }
        if (edges.any { !it }) return null

        val box = bounds(points)
        return points.map { point ->
            Shape.Point(
                if (abs(point.x - box.left) <= abs(point.x - box.right)) box.left else box.right,
                if (abs(point.y - box.top) <= abs(point.y - box.bottom)) box.top else box.bottom,
            )
        }
    }

    private fun snapLine(start: Shape.Point, end: Shape.Point): Shape.Line {
        val angle = Math.toDegrees(atan2(abs(end.y - start.y).toDouble(), abs(end.x - start.x).toDouble())).toFloat()
        return when {
            angle <= LINE_SNAP_ANGLE_DEGREES -> {
                val y = (start.y + end.y) / 2f
                Shape.Line(start.x, y, end.x, y)
            }
            90f - angle <= LINE_SNAP_ANGLE_DEGREES -> {
                val x = (start.x + end.x) / 2f
                Shape.Line(x, start.y, x, end.y)
            }
            else -> Shape.Line(start.x, start.y, end.x, end.y)
        }
    }

    private fun perpendicularDistance(point: Shape.Point, start: Shape.Point, end: Shape.Point): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length <= 0f) return distance(point.x, point.y, start.x, start.y)
        return abs(dy * point.x - dx * point.y + end.x * start.y - end.y * start.x) / length
    }

    private fun bounds(points: List<Shape.Point>): Bounds {
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        return Bounds(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) = hypot(x2 - x1, y2 - y1)

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
