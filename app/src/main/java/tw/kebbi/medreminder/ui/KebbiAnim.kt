package tw.kebbi.medreminder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 這個檔案的動畫數值都取自 Figma：
 *   Kebbi/格狀背景動畫（格=1~4，停 0.6 秒 → Smart Animate 0.9 秒，cubic-bezier(0.7, 0, 0.25, 1)）
 *   Kebbi/藥丸2｜青綠膠囊、蜜桃圓錠、薄荷膠囊（待機1⇄待機2 1.4 秒；點一下：壓扁→跳起→散開→回待機）
 */

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// ---------------------------------------------------------------------------
// 兩側格狀背景：10 個方塊在 4 種排列之間變換，組成膠囊或藥錠時會出現笑臉
// ---------------------------------------------------------------------------

/** 一個方塊：位置、大小、四個角的圓角（左上、右上、右下、左下）。座標為 Figma 格狀背景元件（1440×960）。 */
private class Blk(val x: Float, val y: Float, val w: Float, val h: Float, val r: FloatArray)

private fun blk(x: Int, y: Int, kind: Char): Blk = when (kind) {
    's' -> Blk(x.f, y.f, 72f, 72f, floatArrayOf(20f, 20f, 20f, 20f))      // 方塊
    'o' -> Blk(x.f, y.f, 104f, 104f, floatArrayOf(52f, 52f, 52f, 52f))    // 藥錠（圓）
    'L' -> Blk(x.f, y.f, 76f, 72f, floatArrayOf(36f, 0f, 0f, 36f))        // 橫膠囊左半
    'R' -> Blk(x.f, y.f, 76f, 72f, floatArrayOf(0f, 36f, 36f, 0f))        // 橫膠囊右半
    'T' -> Blk(x.f, y.f, 72f, 76f, floatArrayOf(36f, 36f, 0f, 0f))        // 直膠囊上半
    else -> Blk(x.f, y.f, 72f, 76f, floatArrayOf(0f, 0f, 36f, 36f))       // 'B' 直膠囊下半
}
private val Int.f get() = this.toFloat()

private val BLOCK_COLORS = listOf(
    0xFF3A9D9B, 0xFFBFE5E3, 0xFFF6D29A, 0xFF5FB4B2, 0xFFD7EFED, // a1 a2 b c1 c2
    0xFFD7EFED, 0xFF5FB4B2, 0xFFF6D29A, 0xFFBFE5E3, 0xFF3A9D9B, // d1 d2 e f1 f2
).map { Color(it) }

/** 4 種排列（順序：a1 a2 b c1 c2 d1 d2 e f1 f2）。 */
private val GRID_STATES: List<List<Blk>> = listOf(
    listOf(blk(44, 276, 's'), blk(124, 356, 's'), blk(204, 516, 's'), blk(44, 596, 's'), blk(124, 756, 's'),
        blk(1244, 352, 'B'), blk(1244, 276, 'T'), blk(1286, 540, 'o'), blk(1164, 672, 'B'), blk(1164, 596, 'T')),
    listOf(blk(44, 356, 'L'), blk(120, 356, 'R'), blk(180, 500, 'o'), blk(88, 676, 'L'), blk(164, 676, 'R'),
        blk(1324, 436, 's'), blk(1164, 276, 's'), blk(1244, 516, 's'), blk(1324, 676, 's'), blk(1164, 756, 's')),
    listOf(blk(124, 276, 'T'), blk(124, 352, 'B'), blk(50, 540, 'o'), blk(204, 596, 'T'), blk(204, 672, 'B'),
        blk(1244, 356, 's'), blk(1324, 276, 's'), blk(1164, 516, 's'), blk(1244, 756, 's'), blk(1324, 596, 's')),
    listOf(blk(204, 276, 's'), blk(44, 436, 's'), blk(124, 516, 's'), blk(204, 756, 's'), blk(44, 676, 's'),
        blk(1244, 356, 'L'), blk(1320, 356, 'R'), blk(1156, 500, 'o'), blk(1200, 676, 'L'), blk(1276, 676, 'R')),
)

/** 6 個笑臉（40×30）：x, y, 透明度。 */
private val FACE_STATES: List<List<Triple<Int, Int, Float>>> = listOf(
    listOf(Triple(140, 377, 0f), Triple(220, 537, 0f), Triple(140, 777, 0f), Triple(1260, 375, 1f), Triple(1318, 577, 1f), Triple(1180, 695, 1f)),
    listOf(Triple(138, 377, 1f), Triple(212, 537, 1f), Triple(182, 697, 1f), Triple(1340, 457, 0f), Triple(1260, 537, 0f), Triple(1340, 697, 0f)),
    listOf(Triple(140, 375, 1f), Triple(82, 577, 1f), Triple(220, 695, 1f), Triple(1260, 377, 0f), Triple(1180, 537, 0f), Triple(1260, 777, 0f)),
    listOf(Triple(60, 457, 0f), Triple(140, 537, 0f), Triple(60, 697, 0f), Triple(1262, 377, 1f), Triple(1188, 537, 1f), Triple(1218, 697, 1f)),
)

private val GRID_EASE = CubicBezierEasing(0.7f, 0f, 0.25f, 1f)
private val LINE = Color(0xFFDCE4E3).copy(alpha = 0.7f)

/** 小笑臉：兩個眼睛 + 一條微笑（Figma 臉A~F）。 */
private fun DrawScope.smallFace(x: Float, y: Float, alpha: Float, k: Float = 1f) {
    if (alpha <= 0.01f) return
    val c = K.ink.copy(alpha = alpha)
    drawOval(c, Offset(x + 6 * k, y + 2 * k), Size(8 * k, 11 * k))
    drawOval(c, Offset(x + 26 * k, y + 2 * k), Size(8 * k, 11 * k))
    val p = Path().apply { moveTo(x + 13 * k, y + 19 * k); quadraticBezierTo(x + 20 * k, y + 25 * k, x + 27 * k, y + 19 * k) }
    drawPath(p, c, style = Stroke(3 * k, cap = StrokeCap.Round))
}

/** 畫面兩側會動的格子背景（取代原本靜態的格線）。 */
@Composable
fun GridBackground() {
    var step by remember { mutableIntStateOf(0) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            t.snapTo(0f)
            t.animateTo(1f, tween(900, easing = GRID_EASE))
            step = (step + 1) % GRID_STATES.size
            t.snapTo(0f)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val s = density                              // 1 個 Figma px = 1 dp（KebbiRoot 已換算）
        val extraW = size.width / s - 1280f          // 螢幕比 1280 寬時，右側方塊往右靠
        val extraH = (size.height / s - 800f) / 2    // 螢幕比 800 高時，垂直置中
        val ox = -80f; val oy = -80f          // 元件在畫面上的位置（Figma：x -80, y -80）
        fun X(v: Float) = (v + ox + if (v > 700f) extraW else 0f) * s
        fun Y(v: Float) = (v + oy + extraH) * s
        // 格線：從螢幕最上方一路畫到底（上方說話列消失時，兩側也不會空一塊）
        val top = 0f
        for (x in listOf(0, 80, 160, 240, 320, 1120, 1200, 1280, 1360, 1440))
            drawRect(LINE, Offset(X(x.f), top), Size(2 * s, Y(992f) - top))
        var gy = 272
        while (Y(gy.f) > top) gy -= 80
        for (y in gy..912 step 80) {
            drawRect(LINE, Offset(X(-40f), Y(y.f)), Size(320 * s, 2 * s))
            drawRect(LINE, Offset(X(1160f), Y(y.f)), Size(320 * s, 2 * s))
        }
        val p = t.value
        // 方塊與笑臉。dy 往上平移、k 換成另一組排列，讓上方也有會動的藥。
        fun blocks(k: Int, dy: Float) {
            val a = GRID_STATES[(step + k) % GRID_STATES.size]; val b = GRID_STATES[(step + k + 1) % GRID_STATES.size]
            for (i in a.indices) {
                val x = lerp(a[i].x, b[i].x, p); val y = lerp(a[i].y, b[i].y, p) + dy
                val w = lerp(a[i].w, b[i].w, p); val h = lerp(a[i].h, b[i].h, p)
                val r = FloatArray(4) { lerp(a[i].r[it], b[i].r[it], p) * s }
                val path = Path().apply {
                    addRoundRect(RoundRect(X(x), Y(y), X(x + w), Y(y + h),
                        CornerRadius(r[0]), CornerRadius(r[1]), CornerRadius(r[2]), CornerRadius(r[3])))
                }
                drawPath(path, BLOCK_COLORS[i])
            }
            val fa = FACE_STATES[(step + k) % FACE_STATES.size]; val fb = FACE_STATES[(step + k + 1) % FACE_STATES.size]
            for (i in fa.indices) {
                smallFace(X(lerp(fa[i].first.f, fb[i].first.f, p)), Y(lerp(fa[i].second.f, fb[i].second.f, p) + dy),
                    lerp(fa[i].third, fb[i].third, p), s)
            }
        }
        // 上方延伸區（說話列後面）：另一組排列往上移 320，只畫在原本區塊的上面
        clipRect(top = top, bottom = Y(268f)) { blocks(2, -320f) }
        blocks(0, 0f)
    }
}

// ---------------------------------------------------------------------------
// 會呼吸、點了會跳的藥丸（260×260）
// ---------------------------------------------------------------------------

/** 一個姿勢：藥丸外框 x, y, 寬, 高, 角度（Figma 的角度，逆時針為正），影子 x, 寬。 */
private data class Pose(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float, val sx: Float, val sw: Float)

private fun lerp(a: Pose, b: Pose, t: Float) = Pose(
    lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t),
    lerp(a.rot, b.rot, t), lerp(a.sx, b.sx, t), lerp(a.sw, b.sw, t),
)

enum class PillKind { TEAL, PEACH, MINT }

/** 姿勢順序：待機1、待機2、壓扁、跳起、散開。 */
private fun poses(k: PillKind): List<Pose> = when (k) {
    PillKind.TEAL -> listOf(
        Pose(20f, 74f, 220f, 96f, -8f, 70f, 120f), Pose(20f, 56f, 220f, 96f, -4f, 80f, 100f),
        Pose(5f, 97f, 251f, 79f, -8f, 62f, 136f), Pose(24f, -12f, 211f, 102f, -18f, 95f, 70f),
        Pose(20f, -22f, 220f, 96f, -18f, 110f, 40f))
    PillKind.PEACH -> listOf(
        Pose(50f, 30f, 160f, 140f, 0f, 70f, 120f), Pose(50f, 12f, 160f, 140f, 4f, 80f, 100f),
        Pose(39f, 61f, 182f, 115f, 0f, 62f, 136f), Pose(53f, -58f, 154f, 148f, -10f, 95f, 70f),
        Pose(22f, -79f, 216f, 189f, -10f, 110f, 40f))
    PillKind.MINT -> listOf(
        Pose(20f, 74f, 220f, 96f, 6f, 70f, 120f), Pose(20f, 56f, 220f, 96f, 10f, 80f, 100f),
        Pose(5f, 97f, 251f, 79f, 6f, 62f, 136f), Pose(24f, -12f, 211f, 102f, -4f, 95f, 70f),
        Pose(20f, -22f, 220f, 96f, -4f, 110f, 40f))
}

private val IDLE_EASE = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

@Composable
fun AnimatedPill(kind: PillKind, startOnSecond: Boolean = false, modifier: Modifier = Modifier) {
    val ps = remember(kind) { poses(kind) }
    var from by remember { mutableStateOf(ps[if (startOnSecond) 1 else 0]) }
    var to by remember { mutableStateOf(from) }
    val t = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    suspend fun moveTo(target: Pose, spec: AnimationSpec<Float>) {
        from = lerp(from, to, t.value); to = target
        t.snapTo(0f); t.animateTo(1f, spec)
    }
    suspend fun idleLoop() {
        var second = startOnSecond
        while (true) {
            second = !second
            moveTo(ps[if (second) 1 else 0], tween(1400, easing = IDLE_EASE))
        }
    }
    LaunchedEffect(kind) { job = launch { idleLoop() } }

    Canvas(
        modifier.size(260.dp).plainClick {
            // 點一下：壓扁 → 跳起 → 散開 → 回到待機（同 Figma 的 On click 流程）
            job?.cancel()
            job = scope.launch {
                moveTo(ps[2], tween(120, easing = LinearOutSlowInEasing))
                moveTo(ps[3], spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
                delay(100)
                moveTo(ps[4], tween(400, easing = FastOutSlowInEasing))
                delay(1200)
                moveTo(ps[0], spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
                idleLoop()
            }
        }
    ) {
        val s = size.width / 260f
        val p = lerp(from, to, t.value)
        // 影子
        drawOval(Color(0xFF263A40).copy(alpha = 0.08f), Offset(p.sx * s, 214 * s), Size(p.sw * s, 16 * s))
        // 藥丸（Figma 角度逆時針為正，Compose 順時針為正，所以取負號）
        val cx = (p.x + p.w / 2) * s; val cy = (p.y + p.h / 2) * s
        rotate(-p.rot, Offset(cx, cy)) {
            when (kind) {
                PillKind.PEACH -> drawTablet(p.x * s, p.y * s, p.w * s, p.h * s)
                PillKind.TEAL -> drawCapsule(p.x * s, p.y * s, p.w * s, p.h * s, K.teal, K.tealLight)
                PillKind.MINT -> drawCapsule(p.x * s, p.y * s, p.w * s, p.h * s, Color(0xFF6FB9B6), Color(0xFFD5EFED))
            }
        }
    }
}

private fun DrawScope.drawCapsule(x: Float, y: Float, w: Float, h: Float, dark: Color, light: Color) {
    val r = h / 2
    drawRoundRect(light, Offset(x, y), Size(w, h), CornerRadius(r))
    val left = Path().apply {
        addRoundRect(RoundRect(x, y, x + w / 2, y + h, CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero, CornerRadius(r)))
    }
    drawPath(left, dark)
    // 亮光
    drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(x + w * 0.1f, y + h * 0.18f), Size(w * 0.16f, h * 0.12f), CornerRadius(h * 0.06f))
    // 臉在右半邊
    val k = h / 96f
    smallFace(x + w * 0.62f, y + h * 0.36f, 1f, k)
}

private fun DrawScope.drawTablet(x: Float, y: Float, w: Float, h: Float) {
    val edge = h * 0.12f
    drawOval(K.peachDark, Offset(x, y + edge), Size(w, h - edge))
    drawOval(K.peach, Offset(x, y), Size(w, h - edge))
    drawRoundRect(K.peachDark, Offset(x + w * 0.3f, y + h * 0.16f), Size(w * 0.4f, h * 0.06f), CornerRadius(h * 0.03f))
    val k = h / 140f
    smallFace(x + w / 2 - 20 * k, y + h * 0.42f, 1f, k)
}

/** 三顆藥丸並排（對應 Figma 各畫面中央的「藥丸」群組：0、284、568）。 */
@Composable
fun PillTrio() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        AnimatedPill(PillKind.TEAL, startOnSecond = false)
        AnimatedPill(PillKind.PEACH, startOnSecond = true)
        AnimatedPill(PillKind.MINT, startOnSecond = false)
    }
}
