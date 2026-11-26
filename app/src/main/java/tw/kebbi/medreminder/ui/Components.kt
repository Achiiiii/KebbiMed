package tw.kebbi.medreminder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import tw.kebbi.medreminder.data.AppForeground
import tw.kebbi.medreminder.robot.Robot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 顏色：取自 Figma「凱比介面｜用藥提醒」 */
object K {
    val teal = Color(0xFF3A9D9B)
    val tealLight = Color(0xFFCFE9E7)
    val tealXL = Color(0xFFE7F4F3)
    val peach = Color(0xFFF6C98F)
    val peachDark = Color(0xFFE8A75E)
    val ink = Color(0xFF263A40)
    val ink2 = Color(0xFF5C6F74)
    val line = Color(0xFFE7EDEC)
    val bg = Color(0xFFF8F9F7)
    val amber = Color(0xFFC9781C)
    val amberLight = Color(0xFFFDF1E2)
    val red = Color(0xFFC2544A)
    val redLight = Color(0xFFFBE9E7)
}

/* 版面尺寸一律用 Figma 的數字（畫面 1280×800），KebbiRoot 會等比例縮放到凱比螢幕。 */

@Composable
fun KebbiFace(size: Int = 96) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width / 96f
        drawCircle(K.teal)
        drawRoundRect(Color.White, Offset(18 * s, 30 * s), Size(60 * s, 36 * s), CornerRadius(18 * s))
        drawRoundRect(K.teal, Offset(30 * s, 40 * s), Size(10 * s, 16 * s), CornerRadius(5 * s))
        drawRoundRect(K.teal, Offset(56 * s, 40 * s), Size(10 * s, 16 * s), CornerRadius(5 * s))
    }
}

/** 上方「凱比說的話」列。 */
@Composable
fun SayBar(text: String, onGear: (() -> Unit)? = null, onExit: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(152.dp).shadow(6.dp).background(Color.White).padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        KebbiFace()
        Text(text, Modifier.weight(1f), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = K.ink, lineHeight = 54.sp))
        if (onGear != null) {
            Box(
                Modifier.height(66.dp).clip(RoundedCornerShape(33.dp)).border(3.dp, K.teal, RoundedCornerShape(33.dp))
                    .background(Color.White).clickable(onClick = onGear).padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) { Text("設定", color = K.teal, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        }
        if (onExit != null) {
            Box(
                Modifier.height(66.dp).clip(RoundedCornerShape(33.dp)).border(3.dp, K.ink2, RoundedCornerShape(33.dp))
                    .background(Color.White).clickable(onClick = onExit).padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) { Text("離開", color = K.ink2, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * 收集這一頁有哪些按鈕，讓機器人把「可以按什麼」也唸出來（長者可能不識字）。
 * KScreen 提供一個清單，KButtonBase、GridButton 出現時把自己的字加進去。
 */
class ScreenOptions { val labels = mutableListOf<String>() }
val LocalScreenOptions = staticCompositionLocalOf<ScreenOptions?> { null }

@Composable
private fun RegisterOption(label: String, enabled: Boolean = true) {
    val opts = LocalScreenOptions.current ?: return
    if (!enabled) return
    DisposableEffect(label) {
        opts.labels.add(label)
        onDispose { opts.labels.remove(label) }
    }
}

/** 大按鈕（長者用：至少 120 高、字 40）。 */
@Composable
fun RowScope.KButton(text: String, primary: Boolean = false, sub: String? = null, onClick: () -> Unit) {
    KButtonBase(Modifier.weight(1f).height(120.dp), text, primary, sub, onClick)
}

@Composable
fun KButtonBase(modifier: Modifier, text: String, primary: Boolean = false, sub: String? = null, onClick: () -> Unit, enabled: Boolean = true) {
    RegisterOption(text, enabled)
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .then(if (primary) Modifier.shadow(10.dp, shape) else Modifier)
            .clip(shape)
            .background(if (primary) K.teal else Color.White)
            .border(BorderStroke(4.dp, if (enabled) K.teal else K.line), shape)
            .clickable(enabled = enabled, onClick = { Robot.hush(); onClick() }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = when { !enabled -> K.ink2; primary -> Color.White; else -> K.teal }, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, color = if (primary) Color.White.copy(alpha = .85f) else K.ink2, fontSize = 22.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(160.dp).padding(start = 48.dp, end = 48.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Bottom,
        content = content,
    )
}

/** 一個標準畫面：上方說話列、中間內容、下方按鈕。 */
@Composable
fun KScreen(
    say: String,
    onGear: (() -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    onExit: (() -> Unit)? = null,
    body: @Composable BoxScope.() -> Unit,
) {
    // 每換一頁（上方文字不同），機器人就把上方文字和可以按的按鈕唸出來。
    // 只有 App 在前景時才說：在背景時由提醒引擎說（CR-40），畫面不說，免得講兩次或打斷引擎的話。
    // 從背景回到前景時（例如點通知打開 App），重念一次目前的畫面（第 2.6 節）。
    val opts = remember(say) { ScreenOptions() }
    val foreground by AppForeground.state.collectAsState()
    LaunchedEffect(say, foreground) {
        if (!foreground) return@LaunchedEffect
        Robot.hush()       // 換頁了，上一頁還沒講完的話先停
        delay(350)         // 等按鈕都畫出來
        Robot.sayScreen(say, opts.labels.toList())
    }
    DisposableEffect(say) { onDispose { if (AppForeground.isForeground) Robot.hush() } }
    CompositionLocalProvider(LocalScreenOptions provides opts) {
        Column(Modifier.fillMaxSize()) {
            SayBar(say, onGear, onExit)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp), contentAlignment = Alignment.Center, content = body)
            if (buttons != null) ButtonRow(buttons)
        }
    }
}

@Composable
fun KCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color.White)
            .padding(horizontal = 40.dp, vertical = 14.dp),
        content = content,
    )
}

enum class ChipKind { TODO, NOW, OK, MISS, SKIP }

@Composable
fun KChip(text: String, kind: ChipKind) {
    val (bg, fg) = when (kind) {
        ChipKind.TODO -> K.line to K.ink2
        ChipKind.NOW -> K.amberLight to K.amber
        ChipKind.OK -> K.teal to Color.White
        ChipKind.MISS -> K.redLight to K.red
        ChipKind.SKIP -> Color(0xFFF1F1EE) to Color(0xFF9AA6A8)
    }
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(text, color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** 清單的一列：時間、時段・藥、狀態。 */
@Composable
fun TimeRow(time: String, text: String, chip: (@Composable () -> Unit)? = null, last: Boolean = false) {
    Column {
        Row(Modifier.width(820.dp).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(time, Modifier.width(130.dp), color = K.teal, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(text, Modifier.weight(1f), color = K.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
            chip?.invoke()
        }
        if (!last) Box(Modifier.width(820.dp).height(2.dp).background(K.line))
    }
}

/** 網格選擇按鈕（分類、藥名）。 */
@Composable
fun GridButton(title: String, sub: String?, ghost: Boolean = false, enabled: Boolean = true, marked: Boolean = false, onClick: () -> Unit) {
    RegisterOption(title, enabled && !ghost)
    val shape = RoundedCornerShape(24.dp)
    // marked：加藥精靈建議的答案，實心標出來
    Column(
        Modifier.width(270.dp).height(104.dp).clip(shape).background(if (marked) K.teal else Color.White)
            .border(4.dp, if (ghost || !enabled) Color(0xFFC9D6D5) else K.teal, shape)
            .clickable(enabled = enabled, onClick = { Robot.hush(); onClick() }).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val fg = when { marked -> Color.White; ghost || !enabled -> K.ink2; else -> K.teal }
        Text(title, color = fg, fontSize = if (title.length > 14) 24.sp else 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        if (sub != null) Text(sub, color = if (marked) Color.White else K.ink2, fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** 「照服員設定」要按住多久才進得去。 */
const val CAREGIVER_HOLD_MS = 3_000

/**
 * 「照服員設定 ›」的入口：按住 3 秒才會進去，免得長者誤觸「清空藥單」「離開 App」。
 * 按住時文字下面的線會慢慢填滿；太早放開就提示要按住幾秒。
 * 不登記成畫面上的選項，所以凱比不會念出來。
 */
@Composable
fun CaregiverEntry(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var hint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(hint) { if (hint) { delay(2_500); hint = false } }
    Column(
        modifier.width(IntrinsicSize.Max).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                hint = false
                val fill = scope.launch { progress.snapTo(0f); progress.animateTo(1f, tween(CAREGIVER_HOLD_MS, easing = LinearEasing)) }
                var released = false
                withTimeoutOrNull(CAREGIVER_HOLD_MS.toLong()) {
                    // 放開（up 不是 null）＝按太短；手指滑出去（null）就當作沒按
                    if (waitForUpOrCancellation() != null) hint = true
                    released = true
                }
                fill.cancel()
                scope.launch { progress.snapTo(0f) }
                if (!released) open()
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("照服員設定 ›", fontSize = 24.sp, color = K.ink2)
        Box(Modifier.fillMaxWidth().height(4.dp).background(if (progress.value > 0f) K.line else Color.Transparent)) {
            Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().background(K.teal))
        }
        if (hint) Text("請按住 ${CAREGIVER_HOLD_MS / 1000} 秒", fontSize = 20.sp, color = K.ink2)
    }
}

/** 不顯示點擊波紋的點擊（給待機畫面整面可點）。 */
fun Modifier.plainClick(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
)

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
