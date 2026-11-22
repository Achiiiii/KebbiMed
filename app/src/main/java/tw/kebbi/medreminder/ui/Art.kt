package tw.kebbi.medreminder.ui

import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 花：0 發芽、1 長大、2 開花。畫在 360×300 的範圍內。 */
@Composable
fun Flower(stage: Int) {
    Canvas(Modifier.size(360.dp, 300.dp)) {
        val s = size.width / 360f
        fun leaf(x: Float, y: Float, deg: Float, k: Float = 1f) = rotate(deg, Offset(x * s, y * s)) {
            drawOval(Color(0xFF7BC67E), Offset((x - 28 * k) * s, (y - 12 * k) * s), Size(56 * k * s, 24 * k * s))
        }
        // 盆子
        val pot = Path().apply { moveTo(110 * s, 230 * s); lineTo(250 * s, 230 * s); lineTo(234 * s, 296 * s); lineTo(126 * s, 296 * s); close() }
        drawPath(pot, Color(0xFFC97B52))
        drawRoundRect(Color(0xFFD98C62), Offset(100 * s, 218 * s), Size(160 * s, 24 * s), CornerRadius(8 * s))
        val stem = Color(0xFF43A047)
        when (stage) {
            0 -> {
                drawRoundRect(stem, Offset(176 * s, 190 * s), Size(8 * s, 32 * s), CornerRadius(4 * s))
                leaf(158f, 190f, -25f); leaf(202f, 190f, 25f)
            }
            1 -> {
                drawRoundRect(stem, Offset(176 * s, 110 * s), Size(9 * s, 112 * s), CornerRadius(4 * s))
                leaf(150f, 190f, -25f, 1.3f); leaf(210f, 186f, 25f, 1.3f); leaf(156f, 146f, -30f); leaf(204f, 136f, 30f)
                drawCircle(Color(0xFFA5D6A7), 12 * s, Offset(180 * s, 104 * s))
            }
            else -> {
                drawRoundRect(stem, Offset(176 * s, 96 * s), Size(9 * s, 126 * s), CornerRadius(4 * s))
                leaf(148f, 192f, -25f, 1.3f); leaf(212f, 186f, 25f, 1.3f); leaf(154f, 150f, -30f); leaf(206f, 140f, 30f)
                for (a in 0 until 6) rotate(a * 60f, Offset(180 * s, 96 * s)) {
                    drawOval(Color(0xFFF48FB1), Offset(160 * s, 28 * s), Size(40 * s, 68 * s))
                }
                drawCircle(Color(0xFFFFD54F), 22 * s, Offset(180 * s, 96 * s))
            }
        }
    }
}

private fun DrawScope.face(cx: Float, cy: Float, s: Float) {
    drawCircle(K.ink, 4 * s, Offset(cx - 10 * s, cy))
    drawCircle(K.ink, 4 * s, Offset(cx + 10 * s, cy))
    val p = Path().apply { moveTo(cx - 8 * s, cy + 14 * s); quadraticBezierTo(cx, cy + 21 * s, cx + 8 * s, cy + 14 * s) }
    drawPath(p, K.ink, style = Stroke(4 * s, cap = StrokeCap.Round))
}

@Composable
private fun Capsule(dark: Color, light: Color) {
    Canvas(Modifier.size(180.dp, 90.dp)) {
        val s = size.width / 180f
        drawRoundRect(light, Offset(2 * s, 2 * s), Size(176 * s, 86 * s), CornerRadius(43 * s))
        val left = Path().apply {
            moveTo(90 * s, 2 * s); lineTo(45 * s, 2 * s)
            arcTo(androidx.compose.ui.geometry.Rect(2 * s, 2 * s, 88 * s, 88 * s), 270f, -180f, false)
            lineTo(90 * s, 88 * s); close()
        }
        drawPath(left, dark)
        face(122f * s, 40f * s, s)
    }
}

@Composable
private fun Tablet() {
    Canvas(Modifier.size(120.dp)) {
        val s = size.width / 120f
        drawCircle(K.peachDark, 54 * s, Offset(60 * s, 64 * s))
        drawCircle(K.peach, 54 * s, Offset(60 * s, 58 * s))
        drawRoundRect(K.peachDark, Offset(36 * s, 26 * s), Size(48 * s, 8 * s), CornerRadius(4 * s))
        face(60f * s, 62f * s, s)
    }
}

/** 三顆會呼吸、點了會跳的藥丸（待機、提醒畫面用）。動畫數值取自 Figma，見 KebbiAnim.kt。 */
@Composable
fun FloatingPills() = PillTrio()

/** 吃完藥歡呼的小凱比。 */
@Composable
fun CheerRobot() {
    val t = rememberInfiniteTransition(label = "cheer")
    val a by t.animateFloat(-8f, 8f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "arm")
    Canvas(Modifier.size(180.dp, 200.dp)) {
        val s = size.width / 180f
        val stroke = Stroke(8 * s)
        drawRoundRect(Color.White, Offset(30 * s, 20 * s), Size(120 * s, 96 * s), CornerRadius(40 * s))
        drawRoundRect(K.teal, Offset(30 * s, 20 * s), Size(120 * s, 96 * s), CornerRadius(40 * s), style = stroke)
        drawRoundRect(K.teal, Offset(48 * s, 40 * s), Size(84 * s, 54 * s), CornerRadius(26 * s))
        for (x in listOf(74f, 106f)) {
            val p = Path().apply { moveTo((x - 8) * s, 70 * s); quadraticBezierTo(x * s, 60 * s, (x + 8) * s, 70 * s) }
            drawPath(p, Color.White, style = Stroke(6 * s, cap = StrokeCap.Round))
        }
        drawRoundRect(Color.White, Offset(56 * s, 122 * s), Size(68 * s, 60 * s), CornerRadius(24 * s))
        drawRoundRect(K.teal, Offset(56 * s, 122 * s), Size(68 * s, 60 * s), CornerRadius(24 * s), style = stroke)
        drawLine(K.teal, Offset(34 * s, 140 * s), Offset(8 * s, (104 + a) * s), 10 * s, StrokeCap.Round)
        drawLine(K.teal, Offset(146 * s, 140 * s), Offset(172 * s, (104 - a) * s), 10 * s, StrokeCap.Round)
    }
}

/**
 * 啟動動畫：完全照 Figma S0 → S0-2 → S0-3 → S0-4。
 * 四個方塊從畫面外飛進來 → 合在一起 → 組成膠囊和藥錠 → 出現亮光、笑臉和標題。
 * 座標都是 Figma 1280×800 畫面上的數字；螢幕比較寬時整組置中。
 */
private class SEl(val x: Float, val y: Float, val w: Float, val h: Float, val r: FloatArray, val o: Float)
private fun sel(x: Int, y: Int, w: Int, h: Int, r: List<Int>, o: Float = 1f) =
    SEl(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), (if (r.size == 1) List(4) { r[0] } else r).map { it.toFloat() }.toFloatArray(), o)

// 順序：a1（青綠）、a2（淺綠）、b（藥錠）、c（小方塊）、亮光、臉
private val SPLASH: List<List<SEl>> = listOf(
    listOf(sel(-60, 150, 120, 120, listOf(32)), sel(1220, 520, 120, 120, listOf(32)), sel(320, -60, 120, 120, listOf(32)),
        sel(880, 740, 120, 120, listOf(32)), sel(-24, 178, 44, 12, listOf(6), 0f), sel(1250, 558, 60, 44, listOf(0), 0f)),
    listOf(sel(500, 190, 120, 120, listOf(32)), sel(640, 190, 120, 120, listOf(32)), sel(500, 330, 120, 120, listOf(32)),
        sel(640, 330, 120, 120, listOf(32)), sel(536, 218, 44, 12, listOf(6), 0f), sel(670, 228, 60, 44, listOf(0), 0f)),
    listOf(sel(490, 220, 150, 140, listOf(70, 0, 0, 70)), sel(640, 220, 150, 140, listOf(0, 70, 70, 0)), sel(800, 180, 64, 64, listOf(32)),
        sel(430, 400, 40, 40, listOf(12), 0f), sel(526, 248, 44, 12, listOf(6), 0f), sel(685, 268, 60, 44, listOf(0), 0f)),
    listOf(sel(470, 170, 170, 160, listOf(80, 0, 0, 80)), sel(640, 170, 170, 160, listOf(0, 80, 80, 0)), sel(834, 140, 72, 72, listOf(36)),
        sel(420, 330, 44, 44, listOf(22)), sel(506, 198, 44, 12, listOf(6)), sel(695, 228, 60, 44, listOf(0))),
)
private val SPLASH_COLORS = listOf(Color(0xFF3A9D9B), Color(0xFFBFE5E3), Color(0xFFF6D29A), Color(0xFF5FB4B2), Color.White.copy(alpha = 0.9f))

@Composable
fun SplashAnimation(title: String, subtitle: String, onDone: () -> Unit) {
    var stage by remember { mutableIntStateOf(0) }   // 目前從第幾個狀態往下一個走
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Figma：S0 停 0.3 秒 → 0.7 秒；S0-2 停 0.1 秒 → 0.3 秒；S0-3 → 0.6 秒；S0-4 停 1.2 秒
        val steps = listOf(Triple(300L, 700, FastOutSlowInEasing), Triple(100L, 300, FastOutLinearInEasing), Triple(0L, 600, LinearOutSlowInEasing))
        for ((i, st) in steps.withIndex()) {
            stage = i; t.snapTo(0f)
            delay(st.first)
            t.animateTo(1f, tween(st.second, easing = st.third))
        }
        delay(1200)
        onDone()
    }
    fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
    val a = SPLASH[stage]; val b = SPLASH[stage + 1]; val k = t.value
    val atEnd = stage == 2
    val textA = if (atEnd) k else 0f
    BoxWithConstraints(Modifier.fillMaxSize().background(K.bg)) {
        val ox = (maxWidth.value - 1280f) / 2; val oy = (maxHeight.value - 800f) / 2
        Canvas(Modifier.fillMaxSize()) {
            val s = density
            fun X(v: Float) = (v + ox) * s
            fun Y(v: Float) = (v + oy) * s
            // 整面格線（最後淡到 40%）
            val gridAlpha = if (atEnd) lerp(1f, 0.4f, k) else 1f
            val line = Color(0xFFDCE4E3).copy(alpha = 0.7f * gridAlpha)
            for (x in 0..1280 step 80) drawRect(line, Offset(X(x.toFloat()), 0f), Size(2 * s, size.height))
            for (y in 0..800 step 80) drawRect(line, Offset(0f, Y(y.toFloat())), Size(size.width, 2 * s))
            for (i in 0 until 6) {
                val x = lerp(a[i].x, b[i].x, k); val y = lerp(a[i].y, b[i].y, k)
                val w = lerp(a[i].w, b[i].w, k); val h = lerp(a[i].h, b[i].h, k)
                val o = lerp(a[i].o, b[i].o, k)
                if (o <= 0.01f) continue
                if (i == 5) { // 臉：兩個眼睛 + 微笑（Figma 60×44）
                    val c = K.ink.copy(alpha = o)
                    drawOval(c, Offset(X(x + 8), Y(y + 2)), Size(11 * s, 15 * s))
                    drawOval(c, Offset(X(x + 41), Y(y + 2)), Size(11 * s, 15 * s))
                    val p = Path().apply { moveTo(X(x + 20), Y(y + 28)); quadraticBezierTo(X(x + 30), Y(y + 36), X(x + 40), Y(y + 28)) }
                    drawPath(p, c, style = Stroke(4 * s, cap = StrokeCap.Round))
                    continue
                }
                val r = FloatArray(4) { lerp(a[i].r[it], b[i].r[it], k) * s }
                val path = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(X(x), Y(y), X(x + w), Y(y + h),
                        CornerRadius(r[0]), CornerRadius(r[1]), CornerRadius(r[2]), CornerRadius(r[3])))
                }
                drawPath(path, SPLASH_COLORS[i].copy(alpha = SPLASH_COLORS[i].alpha * o))
            }
        }
        // 標題（Figma：88 Black，y 410 → 370；副標 36，y 540 → 500）
        Text(title, Modifier.fillMaxWidth().offset(y = (oy + lerp(410f, 370f, textA) + 4).dp).alpha(textA),
            fontSize = 88.sp, fontWeight = FontWeight.Black, color = K.ink, textAlign = TextAlign.Center)
        Text(subtitle, Modifier.fillMaxWidth().offset(y = (oy + lerp(540f, 500f, textA)).dp).alpha(textA),
            fontSize = 36.sp, color = K.ink2, textAlign = TextAlign.Center)
    }
}
