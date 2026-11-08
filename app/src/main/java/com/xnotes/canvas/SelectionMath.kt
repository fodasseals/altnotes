package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Outline
import com.xnotes.core.model.Page
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeItem
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.vector.PathFlattener

/** A selected item plus the index of the page it lives on. */
data class Selected(val pageIndex: Int, val item: CanvasItem)

object SelectionMath {

    var includePartial: Boolean = true

    fun bandMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        band: Rect,
        toPage: (Int, Pt) -> Pt = { i, p -> Pt(p.x - pageRects[i].left, p.y - pageRects[i].top) },
    ): List<Selected> = members(pages, pageRects, corners(band), toPage, partial = includePartial)

    fun lassoMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        polygon: List<Pt>,
        toPage: (Int, Pt) -> Pt = { i, p -> Pt(p.x - pageRects[i].left, p.y - pageRects[i].top) },
    ): List<Selected> {
        if (polygon.size < 3) return emptyList()
        return members(pages, pageRects, polygon, toPage, partial = includePartial)
    }

    private fun members(pages: List<Page>, pageRects: List<Rect>, ring: List<Pt>, toPage: (Int, Pt) -> Pt, partial: Boolean): List<Selected> {
        val reach = Rect.bounding(ring)
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.outset(OFF_PAGE_SLACK).intersects(reach)) continue
            val local = Ring(ring.map { toPage(i, it) })
            for (item in pages[i].items) {
                if (!item.locked && (local.meets(item) || (partial && local.overlaps(item.bounds())))) out.add(Selected(i, item))
            }
        }
        return out
    }

    const val OFF_PAGE_SLACK = 64.0

    private fun corners(r: Rect): List<Pt> = listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom))

    private class Line(val pts: List<Pt>, val closed: Boolean)
    private class Drawn(val lines: List<Line>, val reach: Double)

    private fun drawnOf(item: CanvasItem): Drawn = when (item) {
        is Stroke -> Drawn(
            if (item.cuts.isEmpty()) listOf(Line(List(item.sampleCount) { Pt(item.xAt(it), item.yAt(it)) }, false))
            else item.visibleRuns().map { Line(it, false) },
            item.config.baseWidth / 2.0,
        )
        is ShapeItem -> if (item.isFill) fillEdge(item) else outlines(item.paths(), item.strokeWidth)
        is ConnectorItem -> outlines(item.paths(), item.strokeWidth)
        is ImageItem -> Drawn(listOf(Line(item.corners(), true)), 0.0)
        is TapeItem -> Drawn(item.shape.outline.map { Line(it.pts, it.closed) }, 0.0)
        else -> Drawn(listOf(Line(corners(item.bounds()), true)), 0.0)
    }

    private fun fillEdge(fill: ShapeItem): Drawn {
        val rings = fill.fillRings(Outline.HIT_FLATTEN)
        val outer = rings.filter { ring -> rings.count { it !== ring && Geometry.pointInPolygon(it, ring[0]) } % 2 == 0 }
        return Drawn(outer.map { Line(it, true) }, 0.0)
    }

    private fun outlines(paths: List<ShapePath>, width: Double) = Drawn(
        paths.flatMap { path -> path.contours.map { Line(PathFlattener.flatten(it, Outline.HIT_FLATTEN), it.closed) } },
        width / 2.0,
    )

    private class Ring(private val pts: List<Pt>) {
        private val box = Rect.bounding(pts)

        fun meets(item: CanvasItem): Boolean {
            val drawn = drawnOf(item)
            val reach = drawn.reach
            val area = boundsOf(drawn)?.outset(reach) ?: return false
            if (!area.intersects(box)) return false
            for (line in drawn.lines) if (line.pts.isNotEmpty() && Geometry.pointInPolygon(pts, line.pts[0])) return true
            val n = pts.size
            val near = (0 until n).filter { i ->
                val a = pts[i]
                val b = pts[(i + 1) % n]
                minOf(a.x, b.x) <= area.right && maxOf(a.x, b.x) >= area.left && minOf(a.y, b.y) <= area.bottom && maxOf(a.y, b.y) >= area.top
            }
            if (near.isEmpty()) return false
            for (line in drawn.lines) {
                val v = line.pts
                if (v.size == 1) {
                    if (near.any { Geometry.distancePointToSegment(v[0], pts[it], pts[(it + 1) % n]) <= reach }) return true
                    continue
                }
                val segments = if (line.closed) v.size else v.size - 1
                for (k in 0 until segments) {
                    val p = v[k]
                    val q = v[(k + 1) % v.size]
                    for (e in near) if (Geometry.distanceSegmentToSegment(p, q, pts[e], pts[(e + 1) % n]) <= reach) return true
                }
            }
            return false
        }

        fun overlaps(itemBounds: Rect): Boolean = box.intersects(itemBounds)

        private fun boundsOf(drawn: Drawn): Rect? {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (line in drawn.lines) for (p in line.pts) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return if (minX > maxX) null else Rect(minX, minY, maxX - minX, maxY - minY)
        }
    }
}
