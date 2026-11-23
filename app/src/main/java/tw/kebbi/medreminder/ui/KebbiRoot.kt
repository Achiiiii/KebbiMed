package tw.kebbi.medreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 最外層：以 Figma 1280×800 為基準等比例縮放，並且「填滿整個螢幕」（不留黑邊）。
 * 螢幕比 16:10 寬（例如手機）時，高度對齊 800，左右多出來的空間給版面延伸；
 * 螢幕比較高時，寬度對齊 1280，上下延伸。凱比 AIR-H300 是 1024×600（約縮成 0.75 倍，左右多出一點）。
 * 之後所有尺寸都可以直接用 Figma 上的數字（dp、sp）。
 */
@Composable
fun KebbiRoot(vm: AppViewModel) {
    val d by vm.data.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(K.bg)) {
        val dens = LocalDensity.current
        val wPx = with(dens) { maxWidth.toPx() }
        val hPx = with(dens) { maxHeight.toPx() }
        val scale = min(wPx / 1280f, hPx / 800f)
        val designW = wPx / scale   // ≥ 1280
        val designH = hPx / scale   // ≥ 800
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            Box(
                Modifier.size(designW.dp, designH.dp).align(Alignment.Center).clipToBounds().background(K.bg)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) { awaitPointerEvent(PointerEventPass.Initial); vm.touch() }
                        }
                    }
            ) {
                GridBackground()
                Screens(vm, d)
            }
        }
    }
}
