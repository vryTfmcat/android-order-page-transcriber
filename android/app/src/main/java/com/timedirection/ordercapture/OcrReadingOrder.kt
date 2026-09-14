package com.timedirection.ordercapture

import kotlin.math.max
import kotlin.math.min

/** Rebuilds visual rows from OCR lines that ML Kit may return in separate column blocks. */
object OcrReadingOrder {
    data class Segment(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val height: Int get() = (bottom - top).coerceAtLeast(1)
        val centerY: Int get() = top + height / 2
    }

    fun assemble(segments: List<Segment>): String {
        if (segments.isEmpty()) return ""
        val rows = mutableListOf<MutableList<Segment>>()
        segments.filter { it.text.isNotBlank() }
            .sortedWith(compareBy<Segment> { it.top }.thenBy { it.left })
            .forEach { segment ->
                val row = rows.lastOrNull()?.takeIf { existing -> belongsToRow(existing, segment) }
                if (row == null) rows += mutableListOf(segment) else row += segment
            }
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString("  ") { it.text.trim() }
        }.trim()
    }

    private fun belongsToRow(row: List<Segment>, candidate: Segment): Boolean {
        val rowTop = row.minOf { it.top }
        val rowBottom = row.maxOf { it.bottom }
        val overlap = min(rowBottom, candidate.bottom) - max(rowTop, candidate.top)
        val minimumHeight = min((rowBottom - rowTop).coerceAtLeast(1), candidate.height)
        if (overlap >= minimumHeight * 0.35) return true
        val rowCenter = row.map { it.centerY }.average()
        return kotlin.math.abs(rowCenter - candidate.centerY) <= minimumHeight * 0.45
    }
}
