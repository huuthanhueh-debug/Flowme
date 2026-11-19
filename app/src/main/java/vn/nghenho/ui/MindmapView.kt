package vn.nghenho.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.nghenho.ai.Parse
import vn.nghenho.util.MindNode
import vn.nghenho.util.Mindmap

private class MNode(val text: TextLayoutResult, val x: Float, val y: Float, val w: Float, val h: Float, val fill: Color, val textColor: Color)
private class MEdge(val path: Path, val color: Color)
private class MLayout(val nodes: List<MNode>, val edges: List<MEdge>, val height: Float)

private class MT(val n: MindNode, val depth: Int, val branch: Int) {
    val kids = mutableListOf<MT>()
    var tl: TextLayoutResult? = null
    var w = 0f
    var h = 0f
    var sub = 0f
    var x = 0f
    var y = 0f
}

private val BRANCH = listOf(
    Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFF4511E), Color(0xFF8E24AA),
    Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF3949AB),
)

private fun mk(n: MindNode, depth: Int, branch: Int): MT {
    val t = MT(n, depth, branch)
    n.children.forEachIndexed { i, c -> t.kids += mk(c, depth + 1, if (depth == 0) i else branch) }
    return t
}

private fun collect(t: MT, out: MutableList<MT>) {
    out += t
    t.kids.forEach { collect(it, out) }
}

private fun measureSub(t: MT, vGap: Float): Float {
    t.sub = if (t.kids.isEmpty()) t.h
    else maxOf(t.h, t.kids.sumOf { measureSub(it, vGap).toDouble() }.toFloat() + vGap * (t.kids.size - 1))
    return t.sub
}

private fun place(t: MT, top: Float, colX: FloatArray, vGap: Float) {
    t.x = colX[t.depth]
    t.y = top + (t.sub - t.h) / 2f
    val total = t.kids.sumOf { it.sub.toDouble() }.toFloat() + vGap * (t.kids.size - 1).coerceAtLeast(0)
    var cy = top + (t.sub - total) / 2f
    for (k in t.kids) {
        place(k, cy, colX, vGap)
        cy += k.sub + vGap
    }
}

/** Bố cục cây nằm ngang: chủ đề trung tâm bên trái, các nhánh toả sang phải. */
private fun buildLayout(
    root: MindNode, measurer: TextMeasurer, density: Density,
    rootFill: Color, onRoot: Color, onLeaf: Color, dark: Boolean,
): MLayout = with(density) {
    val padX = 12.dp.toPx()
    val padY = 8.dp.toPx()
    val hGap = 40.dp.toPx()
    val vGap = 10.dp.toPx()
    val maxW = 210.dp.roundToPx()
    val rootT = mk(root, 0, 0)
    val all = mutableListOf<MT>()
    collect(rootT, all)
    for (t in all) {
        val style = TextStyle(
            fontSize = when (t.depth) { 0 -> 17.sp; 1 -> 14.sp; else -> 13.sp },
            fontWeight = if (t.depth <= 1) FontWeight.SemiBold else FontWeight.Normal,
        )
        val tl = measurer.measure(AnnotatedString(t.n.label), style = style, constraints = Constraints(maxWidth = maxW))
        t.tl = tl
        t.w = tl.size.width + 2 * padX
        t.h = tl.size.height + 2 * padY
    }
    val depthMax = all.maxOf { it.depth }
    val colW = FloatArray(depthMax + 1)
    for (t in all) colW[t.depth] = maxOf(colW[t.depth], t.w)
    val colX = FloatArray(depthMax + 1)
    for (i in 1..depthMax) colX[i] = colX[i - 1] + colW[i - 1] + hGap
    measureSub(rootT, vGap)
    place(rootT, 0f, colX, vGap)
    val nodes = all.map { t ->
        val base = BRANCH[t.branch % BRANCH.size]
        val (fill, txt) = when (t.depth) {
            0 -> rootFill to onRoot
            1 -> base to Color.White
            else -> base.copy(alpha = if (dark) 0.35f else 0.18f) to onLeaf
        }
        MNode(t.tl!!, t.x, t.y, t.w, t.h, fill, txt)
    }
    val edges = mutableListOf<MEdge>()
    for (t in all) for (k in t.kids) {
        val x1 = t.x + t.w
        val y1 = t.y + t.h / 2
        val x2 = k.x
        val y2 = k.y + k.h / 2
        val p = Path().apply {
            moveTo(x1, y1)
            cubicTo(x1 + hGap / 2, y1, x2 - hGap / 2, y2, x2, y2)
        }
        edges += MEdge(p, BRANCH[k.branch % BRANCH.size].copy(alpha = 0.7f))
    }
    MLayout(nodes, edges, rootT.sub)
}

@Composable
fun MindmapView(json: String, modifier: Modifier = Modifier) {
    val root = remember(json) { Mindmap.fromJson(runCatching { Parse.JSON.parseToJsonElement(json) }.getOrNull()) }
    if (root == null) {
        EmptyHint("Chưa có sơ đồ tư duy", modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val cs = MaterialTheme.colorScheme
    val layout = remember(root, density, dark) { buildLayout(root, measurer, density, cs.primary, cs.onPrimary, cs.onSurface, dark) }
    var zoom by remember(json) { mutableFloatStateOf(1f) }
    var offset by remember(json) { mutableStateOf(Offset.Zero) }
    val corner = with(density) { 12.dp.toPx() }
    val edgeW = with(density) { 2.dp.toPx() }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(json) {
                detectTransformGestures { _, pan, z, _ ->
                    zoom = (zoom * z).coerceIn(0.3f, 3f)
                    offset += pan
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val startY = ((size.height - layout.height * zoom) / 2f).coerceAtLeast(16f)
            translate(left = offset.x + 16f, top = offset.y + startY) {
                scale(zoom, pivot = Offset.Zero) {
                    layout.edges.forEach { drawPath(it.path, it.color, style = Stroke(width = edgeW)) }
                    layout.nodes.forEach { n ->
                        drawRoundRect(n.fill, topLeft = Offset(n.x, n.y), size = Size(n.w, n.h), cornerRadius = CornerRadius(corner))
                        drawText(
                            n.text, color = n.textColor,
                            topLeft = Offset(n.x + (n.w - n.text.size.width) / 2f, n.y + (n.h - n.text.size.height) / 2f),
                        )
                    }
                }
            }
        }
    }
}
