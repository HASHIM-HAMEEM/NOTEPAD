package com.hashim.notepad.pdf

import kotlin.math.max

data class PdfUserPoint(val x: Float, val y: Float)

object PdfPageCoordinates {
    fun screenToPageTopLeft(
        screenX: Float,
        screenY: Float,
        pageLeft: Float,
        pageTop: Float,
        pageWidthPx: Float,
        pageHeightPx: Float,
        pageWidthPt: Float,
        pageHeightPt: Float,
        rotation: Int,
        zoom: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): PdfUserPoint {
        val userPoint = screenToPdfUser(
            screenX,
            screenY,
            pageLeft,
            pageTop,
            pageWidthPx,
            pageHeightPx,
            pageWidthPt,
            pageHeightPt,
            rotation,
            zoom,
            offsetX,
            offsetY,
        )
        return PdfUserPoint(userPoint.x, pageHeightPt - userPoint.y)
    }

    fun screenToPdfUser(
        screenX: Float,
        screenY: Float,
        pageLeft: Float,
        pageTop: Float,
        pageWidthPx: Float,
        pageHeightPx: Float,
        pageWidthPt: Float,
        pageHeightPt: Float,
        rotation: Int,
        zoom: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): PdfUserPoint {
        val displayWidth = if (normalizedRotation(rotation) % 180 == 0) pageWidthPt else pageHeightPt
        val displayHeight = if (normalizedRotation(rotation) % 180 == 0) pageHeightPt else pageWidthPt
        val displayX = ((screenX - pageLeft - offsetX) / max(zoom, 0.001f)) / pageWidthPx * displayWidth
        val displayY = ((screenY - pageTop - offsetY) / max(zoom, 0.001f)) / pageHeightPx * displayHeight
        val topLeft = displayToPageTopLeft(displayX, displayY, pageWidthPt, pageHeightPt, rotation)
        return PdfUserPoint(topLeft.x, pageHeightPt - topLeft.y)
    }

    fun pdfUserToScreen(
        point: PdfUserPoint,
        pageLeft: Float,
        pageTop: Float,
        pageWidthPx: Float,
        pageHeightPx: Float,
        pageWidthPt: Float,
        pageHeightPt: Float,
        rotation: Int,
        zoom: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): PdfUserPoint {
        val displayWidth = if (normalizedRotation(rotation) % 180 == 0) pageWidthPt else pageHeightPt
        val displayHeight = if (normalizedRotation(rotation) % 180 == 0) pageHeightPt else pageWidthPt
        val display = pageTopLeftToDisplay(point.x, pageHeightPt - point.y, pageWidthPt, pageHeightPt, rotation)
        return PdfUserPoint(
            pageLeft + offsetX + display.x / displayWidth * pageWidthPx * zoom,
            pageTop + offsetY + display.y / displayHeight * pageHeightPx * zoom,
        )
    }

    fun displayToPageTopLeft(x: Float, y: Float, pageWidth: Float, pageHeight: Float, rotation: Int): PdfUserPoint =
        when (normalizedRotation(rotation)) {
            90 -> PdfUserPoint(y, pageHeight - x)
            180 -> PdfUserPoint(pageWidth - x, pageHeight - y)
            270 -> PdfUserPoint(pageWidth - y, x)
            else -> PdfUserPoint(x, y)
        }

    private fun pageTopLeftToDisplay(x: Float, y: Float, pageWidth: Float, pageHeight: Float, rotation: Int): PdfUserPoint =
        when (normalizedRotation(rotation)) {
            90 -> PdfUserPoint(pageHeight - y, x)
            180 -> PdfUserPoint(pageWidth - x, pageHeight - y)
            270 -> PdfUserPoint(y, pageWidth - x)
            else -> PdfUserPoint(x, y)
        }

    private fun normalizedRotation(rotation: Int) = (rotation % 360 + 360) % 360
}
