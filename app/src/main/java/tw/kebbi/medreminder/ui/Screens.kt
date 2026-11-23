package tw.kebbi.medreminder.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import tw.kebbi.medreminder.MainActivity
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.dto.RemoveReason
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import tw.kebbi.medreminder.data.AppState
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.OccStatus
import tw.kebbi.medreminder.data.Occurrence
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.engine.ReminderEngine
import tw.kebbi.medreminder.engine.Speech
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.scan.camera.ScanCamera
import tw.kebbi.medreminder.sync.SyncManager

private val PRESET_EARLY = RoutineMinutes(5 * 60 + 30, 11 * 60, 17 * 60, 20 * 60 + 30)
private val PRESET_LATE = RoutineMinutes(9 * 60, 13 * 60, 19 * 60, 22 * 60)

/** 依目前畫面畫出內容。 */
@Composable
fun Screens(vm: AppViewModel, st: AppState) {
    val ctx = LocalContext.current
    val gear = { vm.go(Screen.Settings) }
    when (val s = vm.screen) {
        Screen.Splash -> SplashAnimation("用藥提醒", "我會陪您按時吃藥") { vm.go(Screen.Enter) }
        Screen.Enter -> {
            LaunchedEffect(Unit) { delay(2000); vm.go(Screen.Home) }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FloatingPills(); VSpace(30)
                Text("用藥提醒", fontSize = 88.sp, fontWeight = FontWeight.Black, color = K.ink)
                Text("幫您看看今天的藥…", fontSize = 34.sp, color = K.ink2)
            }
        }
        Screen.Pairing -> PairingScreen(vm)
        Screen.Disabled -> KScreen("這台凱比已經停用了，請聯絡照服員。") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FloatingPills()
                Text("照服員設定 ›", fontSize = 24.sp, color = K.ink2, modifier = Modifier.padding(top = 24.dp).plainClick { vm.go(Screen.Caregiver) })
            }
        }

        // ---------------- ② 居家 ----------------
        Screen.Home -> KScreen(vm.homeSay(st), onGear = gear, buttons = {
            KButton("今天的藥", primary = true) { vm.go(Screen.Today) }
            KButton("明天的藥") { vm.go(Screen.Tomorrow) }
        }) {
            NowClock(Modifier.align(Alignment.TopEnd))
            Progress(st, vm.today(st))
        }

        Screen.Today -> KScreen(vm.homeSay(st), onGear = gear, buttons = {
            KButton("回首頁", primary = true) { vm.go(Screen.Home) }
        }) { DayList(vm, vm.today(st), withChips = true) }

        Screen.Tomorrow -> {
            val list = vm.tomorrow(st)
            KScreen(if (list.isEmpty()) "明天沒有要吃的藥。" else "明天要吃 ${list.size} 次藥，時間到我會提醒您。", onGear = gear, buttons = {
                KButton("回首頁", primary = true) { vm.go(Screen.Home) }
            }) { DayList(vm, list, withChips = false) }
        }

        is Screen.Remind -> {
            val o = vm.occ(s.occId) ?: return
            val now = Clock.nowMs()
            val snooze: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                if (ReminderEngine.canSnooze(o, now)) {
                    KButton("等一下", sub = "${ReminderEngine.snoozeMinutes(o, now)} 分鐘後再叫我") { vm.snooze(o.id) }
                }
                KButton("吃了", primary = true) { vm.take(o.id) }
            }
            if (!o.secondReminder) {
                KScreen(Speech.remind(o, Repo.othersCount(o)), buttons = snooze) { DoseCard(o) }
            } else {
                // 第二次提醒也要說話（CR-09、D16）：改用 KScreen
                KScreen(Speech.remindAgain(o), buttons = snooze) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KChipBig("已經過了 ${((now - o.scheduledAt) / 60_000).coerceAtLeast(1)} 分鐘", K.amberLight, K.amber)
                        VSpace(16)
                        DoseCard(o)
                        Text("再過 ${((o.scheduledAt + Rules.OVERDUE_AFTER_MIN * 60_000L - now) / 60_000).coerceAtLeast(0)} 分鐘沒按，就會記成逾時未服。",
                            fontSize = 24.sp, color = K.ink2, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        // 逾時未服（T+30 還沒吃）：還可以晚吃
        is Screen.Overdue -> {
            val o = vm.occ(s.occId) ?: return
            KScreen(Speech.overdue(o, SyncManager.isOnline()), buttons = {
                KButton("等一下再說") { vm.overdueLater(o.id) }
                KButton("現在吃", primary = true) { vm.askLate(o.id) }
            }) {
                KCard(Modifier.width(860.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        KChipBig("逾時未服", K.amberLight, K.amber)
                        Text("${o.slotLabel} ${Clock.hm(o.scheduledAt)}", fontSize = 56.sp, fontWeight = FontWeight.Black, color = K.ink, modifier = Modifier.padding(top = 14.dp))
                        Text(medLine(o) + "\n最晚 ${deadlineText(o)} 前吃，會記成「晚吃」。", fontSize = 28.sp, color = K.ink2, textAlign = TextAlign.Center, lineHeight = 40.sp)
                    }
                }
            }
        }

        is Screen.TakeConfirm -> {
            val o = vm.occ(s.occId) ?: return
            KScreen("確定現在要吃${o.medNames.ifEmpty { "這包藥" }}嗎？請照藥袋上的量吃，不要多吃。", buttons = {
                KButton("先不要") { vm.go(Screen.Today) }
                KButton("確定，吃好了", primary = true) { vm.take(o.id) }
            }) { DoseCard(o) }
        }

        is Screen.LateConfirm -> {
            val o = vm.occ(s.occId) ?: return
            KScreen("確定現在要吃${o.medNames.ifEmpty { "這包藥" }}嗎？請照藥袋上的量吃，不要多吃。", buttons = {
                KButton("先不要") { vm.go(Screen.Today) }
                KButton("確定，吃好了", primary = true) { vm.take(o.id) }
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KChipBig("晚吃 ${o.slotLabel} ${Clock.hm(o.scheduledAt)} 的藥", K.amberLight, K.amber)
                    VSpace(20)
                    DoseCard(o)
                }
            }
        }

        is Screen.LateNo -> {
            val o = vm.occ(s.occId)
            KScreen(s.reason, buttons = { KButton("知道了", primary = true) { vm.go(Screen.Home) } }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KChipBig("這次不要吃了", K.redLight, K.red)
                    VSpace(20)
                    if (o != null) DoseCard(o)
                }
            }
        }

        is Screen.Reward -> {
            val day = vm.today(st)
            val left = day.count { it.status != OccStatus.TAKEN && it.status.isOpen }
            KScreen(if (ReminderEngine.allDone(day)) "全部吃完了！今天的花開了，好棒！" else "很好，我記下來了！今天還有 $left 次。", buttons = {
                KButton("好", primary = true) { vm.go(Screen.Home) }
            }) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) { CheerRobot(); Progress(st, day) } }
        }

        is Screen.Snoozed -> KScreen(Speech.snoozed(s.minutes), buttons = {
            KButton("好", primary = true) { vm.go(Screen.Home) }
        }) { FloatingPills() }

        is Screen.Notice -> KScreen(s.text, buttons = {
            KButton("看今天的藥") { vm.go(Screen.Today) }
            KButton("知道了", primary = true) { vm.go(Screen.Home) }
        }) { MedListCard(vm, st) }

        Screen.Standby -> {
            val day = vm.today(st)
            val done = ReminderEngine.allDone(day)
            val now = Clock.nowMs()
            val next = ReminderEngine.nextOpen(st.occurrences, now)
            val overdue = ReminderEngine.overdueTakeable(st.occurrences, now)
            Column(Modifier.fillMaxSize().plainClick { vm.go(Screen.Home) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (done) Flower(2) else FloatingPills()
                Text("用藥提醒", fontSize = 88.sp, fontWeight = FontWeight.Black, color = K.ink, modifier = Modifier.padding(top = 20.dp))
                Text(when {
                    done -> "今天的藥都吃完了，花開了！"
                    next != null -> "下一次：${if (next.date == Clock.todayKey()) "" else "明天"}${next.slotLabel} ${Clock.hm(next.scheduledAt)}"
                    else -> "今天沒有要吃的藥了"
                }, fontSize = 34.sp, color = K.ink2)
                // 第 1.3 節第 6 點：逾時未服、還可以補吃的藥也顯示在待機畫面，點了就能補吃
                overdue.forEach { o ->
                    Box(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(32.dp)).background(K.amberLight)
                        .clickable { vm.askLate(o.id) }.padding(horizontal = 28.dp, vertical = 12.dp)) {
                        Text("還沒吃：${o.doseText()}，點這裡現在吃", fontSize = 30.sp, color = K.amber, fontWeight = FontWeight.Bold)
                    }
                }
                Text("輕觸螢幕回到首頁", fontSize = 28.sp, color = K.teal, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            }
        }

        // ---------------- ③ 設定模式 ----------------
        Screen.Settings -> KScreen("這裡是設定。要做什麼呢？") {
            Column(horizontalAlignment = Alignment.End) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        KButtonBase(Modifier.width(480.dp).height(140.dp), "新增藥品", primary = true, sub = "拍藥袋（QR Code 或藥名都可以）", onClick = { vm.startScan() })
                        KButtonBase(Modifier.width(480.dp).height(140.dp), "刪除藥品", sub = "醫師說可以停的藥", onClick = { vm.go(Screen.DeleteList) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        KButtonBase(Modifier.width(480.dp).height(140.dp), "吃藥時間", sub = "作息：吃飯、睡覺時間", onClick = { vm.go(Screen.Routine) })
                        KButtonBase(Modifier.width(480.dp).height(140.dp), "返回", onClick = { vm.go(Screen.Home) })
                    }
                }
                Text("照服員設定 ›", fontSize = 24.sp, color = K.ink2, modifier = Modifier.padding(top = 24.dp).plainClick { vm.go(Screen.Caregiver) })
            }
        }

        Screen.Routine -> KScreen("要改吃飯、睡覺的時間嗎？先選一個最接近的。", buttons = {
            KButton("早一點", sub = "5:30・11:00・17:00・睡 20:30") { vm.go(Screen.RoutineCards(PRESET_EARLY)) }
            KButton("一般", primary = true, sub = "8:00・12:30・18:30・睡 22:00") { vm.go(Screen.RoutineCards(RoutineMinutes.DEFAULT)) }
            KButton("晚一點", sub = "9:00・13:00・19:00・睡 22:00") { vm.go(Screen.RoutineCards(PRESET_LATE)) }
        }) {
            val r = st.device.routine
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                KButtonBase(Modifier.width(760.dp).height(120.dp), "從現在的時間調整",
                    sub = "早餐 ${Clock.fmt(r.breakfast)}・午餐 ${Clock.fmt(r.lunch)}・晚餐 ${Clock.fmt(r.dinner)}・睡覺 ${Clock.fmt(r.sleep)}",
                    onClick = { vm.go(Screen.RoutineCards(r)) })
                Text("返回設定", fontSize = 28.sp, color = K.ink2, modifier = Modifier.plainClick { vm.go(Screen.Settings) })
            }
        }

        is Screen.RoutineCards -> {
            var draft by remember(s) { mutableStateOf(s.draft) }
            KScreen("三餐和睡覺大概幾點？按 − ＋ 調整，每次 15 分鐘。", buttons = {
                KButton("取消") { vm.go(Screen.Settings) }
                KButton("好了", primary = true) { vm.go(Screen.RoutineConfirm(draft)) }
            }) { TimeCards(draft) { draft = it } }
        }

        is Screen.RoutineConfirm -> KScreen("好，這是新的作息時間。這樣對嗎？", buttons = {
            KButton("不對") { vm.go(Screen.RoutineCards(s.draft)) }
            KButton("對", primary = true) { vm.confirmRoutine(s.draft) }
        }) {
            KCard {
                Row(horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                    listOf("早餐" to s.draft.breakfast, "午餐" to s.draft.lunch, "晚餐" to s.draft.dinner, "睡覺" to s.draft.sleep).forEach { (l, m) ->
                        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(l, fontSize = 28.sp, color = K.ink2, fontWeight = FontWeight.Bold)
                            Text(Clock.fmt(m), fontSize = 64.sp, color = K.ink, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        // 掃藥袋（D24、D39）：看到 QR Code 就自動拍；沒有 QR Code 的倒數三秒自動拍，也可以自己按「拍照」
        Screen.Scan, Screen.ScanAgain -> KScreen(
            if (s == Screen.ScanAgain) "沒看清楚藥名。請把藥袋拿近一點，拿著不要動，我再拍一次。"
            else "請把藥袋拿到我眼睛前面，拿著不要動，我會自己拍。",
        ) {
            Box(Modifier.size(1220.dp, 600.dp)) {
                ScanCamera(
                    onCaptured = { vm.onCaptured(ctx, it) },
                    onPick = { vm.pickWithoutPhoto() },
                    onBack = { vm.go(Screen.Settings) },
                    onNoCamera = { vm.pickWithoutPhoto() },
                    onSay = { vm.say(it) },
                    onTestImage = if (vm.isDebug) ({ vm.onTestImage(ctx) }) else null,
                )
            }
        }

        Screen.Recognizing -> KScreen("我看看這是什麼藥，請等一下…") { FloatingPills() }

        is Screen.PickAmong -> {
            val items = vm.catalog(st).filter { it.catalogId in s.catalogIds }
            KScreen("找到好幾個可能的藥，是哪一個？請對照藥袋上的藥名。") {
                Grid {
                    items.forEach { m -> GridButton(m.genericName, m.brandName ?: m.displayName) { vm.startAdd(m) } }
                    GridButton("都不是", "從目錄選", ghost = true) { vm.go(Screen.PickCat) }
                }
            }
        }

        Screen.PickCat -> KScreen("這是哪一類的藥？藥袋上有寫用途。") {
            Grid {
                vm.categories(st).forEach { c -> GridButton(c, "${vm.catalog(st).count { it.category == c }} 種") { vm.go(Screen.PickMed(c)) } }
                GridButton("目錄裡沒有", "把照片傳給照服員", ghost = true) { vm.submitUnlisted() }
                GridButton("返回", "不加了", ghost = true) { vm.cancelAdd() }
            }
        }

        is Screen.PickMed -> KScreen("${s.cat}的藥裡面，是哪一個？請對照藥袋上的藥名。") {
            Grid {
                vm.catalog(st).filter { it.category == s.cat }.forEach { m ->
                    val has = st.device.medications.any { it.catalogId == m.catalogId }
                    GridButton(m.genericName, if (has) "已經有了" else (m.brandName ?: m.displayName), enabled = !has) { vm.startAdd(m) }
                }
                GridButton("目錄裡沒有", "把照片傳給照服員", ghost = true) { vm.submitUnlisted() }
                GridButton("換一類", "回上一步", ghost = true) { vm.go(Screen.PickCat) }
            }
        }

        is Screen.AddMed -> AddMedScreen(vm, st, s.draft)

        is Screen.Working -> KScreen(s.text) { FloatingPills() }

        is Screen.Added -> KScreen(vm.addedText(st, s), buttons = {
            KButton("沒有了") { vm.go(Screen.Today) }
            KButton("有，再拍一包", primary = true) { vm.startScan() }
        }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val m = st.device.medications.firstOrNull { it.medicationId == s.medicationId }
                if (m != null) {
                    KCard(Modifier.width(900.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.displayName, fontSize = 44.sp, fontWeight = FontWeight.Black, color = K.ink)
                            Text("　${m.genericName ?: ""}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = K.teal)
                        }
                        Text("每次 ${m.dose.text}・" + (if (m.slots.isEmpty()) "需要時才吃" else vm.medWhen(m)) + (vm.endText(m)?.let { "・$it" } ?: ""),
                            fontSize = 30.sp, color = K.ink2, modifier = Modifier.padding(top = 8.dp))
                    }
                } else FloatingPills()
                Text("不對，拿掉這個藥 ›", fontSize = 26.sp, color = K.ink2,
                    modifier = Modifier.padding(top = 20.dp).plainClick { vm.undoAdd(s.medicationId) })
            }
        }

        is Screen.Result -> KScreen(s.text, buttons = {
            if (s.next == ResultNext.ADD_MORE) {
                KButton("沒有了") { vm.go(Screen.Today) }
                KButton("再加一包", primary = true) { vm.startScan() }
            } else KButton("好", primary = true) { vm.resultNext(s.next) }
        }) { if (s.next == ResultNext.TODAY) MedListCard(vm, st) else FloatingPills() }

        Screen.DeleteList -> KScreen(if (st.device.medications.isEmpty()) "現在沒有記著的藥。" else "要拿掉哪一種藥？下面是現在記著的藥。") {
            Grid {
                st.device.medications.forEach { m -> GridButton(m.genericName ?: m.displayName, m.displayName) { vm.go(Screen.DeleteConfirm(m.medicationId)) } }
                GridButton("取消", "回上一步", ghost = true) { vm.go(Screen.Settings) }
            }
        }

        is Screen.DeleteConfirm -> {
            val m = st.device.medications.firstOrNull { it.medicationId == s.medicationId }
            KScreen("停藥要先問過醫師喔。醫師說「${m?.genericName ?: ""}（${m?.displayName ?: "這個藥"}）」可以不用吃了嗎？", buttons = {
                KButton("還沒問，先不要") { vm.go(Screen.DeleteList) }
                KButton("醫師說可以停", primary = true) { vm.submitRemove(s.medicationId, RemoveReason.DOCTOR_SAID_STOP) }
            }) { FloatingPills() }
        }

        Screen.Caregiver -> CaregiverScreen(vm, st)

        Screen.ClearConfirm -> KScreen("要清空藥單嗎？會停掉全部的藥。今天已經吃過的紀錄不會不見。", buttons = {
            KButton("取消") { vm.go(Screen.Caregiver) }
            KButton("清空", primary = true) { vm.clearMedications() }
        }) { MedListCard(vm, st) }

        Screen.ExitConfirm -> {
            val act = ctx as? android.app.Activity
            KScreen("要離開用藥提醒嗎？離開後，時間到我還是會發通知、說話提醒。", buttons = {
                KButton("不要，留下來") { vm.go(Screen.Home) }
                KButton("離開", primary = true) { vm.onExit(); vm.go(Screen.Home); act?.finishAndRemoveTask() }
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingPills()
                    Text("要回到用藥提醒，請點通知，或點用藥提醒的圖示。", fontSize = 30.sp, color = K.ink2, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }

        Screen.DebugBackend -> DebugScreen(vm)
    }
}

// ---------------- 配對、照服員、debug ----------------

/** 還沒綁定：顯示配對碼（數字＋QR），照服員在後台輸入或掃描（D29 (c)）。 */
@Composable
private fun PairingScreen(vm: AppViewModel) {
    val status by SyncManager.status.collectAsState()
    val p = status.pairing
    val say = when {
        p != null -> "請照服員在後台輸入這個配對碼，完成綁定後我就會開始提醒。"
        !status.online -> "網路斷了，連上網後會顯示配對碼。"
        else -> "正在取得配對碼，請等一下…"
    }
    KScreen(say) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                    KCard {
                        Text(p.code.chunked(3).joinToString(" "), fontSize = 120.sp, fontWeight = FontWeight.Black, color = K.ink,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        Text("有效到 ${p.expiresAt.substring(11, 16)}，過期會自動換新", fontSize = 24.sp, color = K.ink2, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    val bmp = remember(p.qrText) { qrBitmap(p.qrText, 360) }
                    Image(bmp.asImageBitmap(), contentDescription = "配對 QR Code", modifier = Modifier.size(320.dp))
                }
            } else FloatingPills()
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (vm.hasFakeBackend) SmallLink("模擬後台綁定（測試）") { vm.debug { SyncManager.fake!!.debugClaim() } }
                SmallLink("照服員設定", primary = false) { vm.go(Screen.Caregiver) }
            }
            if (vm.debugMessage.isNotEmpty()) Text(vm.debugMessage, fontSize = 22.sp, color = K.ink2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

@Composable
private fun CaregiverScreen(vm: AppViewModel, st: AppState) {
    val ctx = LocalContext.current
    val status by SyncManager.status.collectAsState()
    val d = st.device
    val elder = d.elder?.let { "${it.stationName ?: ""}・${it.displayName}" } ?: "還沒綁定長者"
    KScreen("照服員設定：$elder", buttons = {
        KButton("返回") { vm.go(if (d.assigned && !d.disabled) Screen.Settings else Screen.Home) }
        if (d.assigned) KButton("清空藥單") { vm.go(Screen.ClearConfirm) }
        KButton("離開 App") { vm.go(Screen.ExitConfirm) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SmallLink("測試說話") { vm.say("您好，我是用藥提醒，聽得到我說話嗎？") }
                if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled())
                    SmallLink("允許通知") { (ctx as? MainActivity)?.requestNotificationPermission() }
                if (vm.isDebug) {
                    SmallLink("快轉到下一次轉換前 10 秒") { vm.testJumpToNext() }
                    SmallLink("回到真實時間", primary = false) { vm.testResetClock() }
                }
                if (vm.isDebug && vm.hasFakeBackend) SmallLink("假後端") { vm.go(Screen.DebugBackend) }
            }
            val lines = listOfNotNull(
                "語音：${Robot.voice.status}",
                "裝置編號：${d.deviceId ?: "還沒登記"}",
                "排程：第 ${d.scheduleVersion ?: "-"} 版，${d.medications.size} 種藥；待送 ${st.pendingOutbox} 筆",
                "網路：" + (if (status.online) "正常" else "斷了（${status.lastError ?: ""}）"),
                "通知權限：" + (if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) "已允許" else "沒有允許，App 不在畫面上時橫幅出不來"),
                SyncManager.clockSkewWarning(),
                if (Clock.offsetMs != 0L) "測試：時間快轉了 ${Clock.offsetMs / 60_000} 分鐘" else null,
                "「清空藥單」會送申請給後台，停掉全部的藥；今天已吃的紀錄不會不見。",
            )
            Text(lines.joinToString("\n"), fontSize = 24.sp, color = K.ink2, textAlign = TextAlign.Center, lineHeight = 34.sp,
                modifier = Modifier.width(1000.dp).padding(top = 24.dp))
        }
    }
}

/** debug 版：模擬照服員在後台的操作和網路狀況（假後端）。 */
@Composable
private fun DebugScreen(vm: AppViewModel) {
    val fake = SyncManager.fake ?: return
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    KScreen("假後端（測試用）", buttons = {
        KButton("返回", primary = true) { vm.go(Screen.Caregiver) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.width(1180.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallLink("後台加藥") { vm.debug { fake.debugAddMed() } }
                SmallLink("2 分鐘後的測試提醒") { vm.debug { fake.debugAddMedAt(2) } }
                SmallLink("後台停藥") { vm.debug { fake.debugRemoveMed() } }
                SmallLink("作息延後 30 分") { vm.debug { fake.debugShiftRoutine(30) } }
                SmallLink("作息提早 30 分") { vm.debug { fake.debugShiftRoutine(-30) } }
                SmallLink(if (fake.offline) "恢復網路" else "模擬斷網") { fake.offline = !fake.offline; vm.debug { if (fake.offline) "已模擬斷網" else "網路恢復" } }
                SmallLink("下一次回 401") { fake.failNext = 401; vm.debug { "下一次呼叫回 401" } }
                SmallLink("下一次回 500") { fake.failNext = 500; vm.debug { "下一次呼叫回 500" } }
                SmallLink("停用／恢復凱比", primary = false) { vm.debug { fake.debugToggleDisabled() } }
                SmallLink("解除綁定", primary = false) { vm.debug { fake.debugUnassign() } }
                SmallLink("現在同步", primary = false) { vm.debug { "已同步" } }
            }
            Text(vm.debugMessage, fontSize = 24.sp, color = K.teal, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(remember(tick) { vm.debugSummary() }, fontSize = 22.sp, color = K.ink2, lineHeight = 32.sp, modifier = Modifier.width(1100.dp).padding(top = 12.dp))
        }
    }
}

// ---------------- 共用區塊 ----------------

/** 照服員設定頁的小按鈕（外框膠囊，比純文字好按）。 */
@Composable
private fun SmallLink(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = if (primary) K.teal else K.ink2
    Box(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).border(3.dp, c, RoundedCornerShape(32.dp))
            .background(Color.White).clickable(onClick = onClick).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 26.sp, color = c, fontWeight = FontWeight.Bold) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Grid(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.width(1180.dp).verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content() }
}

@Composable
private fun KChipBig(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(text, color = fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

private fun medLine(o: Occurrence) = o.items.joinToString("・") { it.name + if (it.doseText != "1 顆") "（${it.doseText}）" else "" }

/** 晚吃期限：跨日時寫「明天 10:00」。 */
private fun deadlineText(o: Occurrence): String =
    (if (Clock.today(o.lateDeadline) != Clock.today(o.scheduledAt)) "明天 " else "") + Clock.hm(o.lateDeadline)

@Composable
private fun NowClock(modifier: Modifier) {
    var now by remember { mutableStateOf(Clock.hm(Clock.nowMs())) }
    LaunchedEffect(Unit) { while (true) { now = Clock.hm(Clock.nowMs()); delay(5000) } }
    Text("現在 $now", modifier, fontSize = 28.sp, color = K.ink2, fontWeight = FontWeight.Medium)
}

/** 今天的進度和開花（D20：花的數量由後端計算）。 */
@Composable
private fun Progress(st: AppState, day: List<Occurrence>) {
    val total = day.size
    val done = ReminderEngine.takenCount(day)
    val all = ReminderEngine.allDone(day)
    val flowers = (st.device.rewards?.flowers ?: 0) + if (all) 1 else 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Flower(if (all) 2 else if (done > 0) 1 else 0)
        Column(Modifier.width(400.dp)) {
            Text("今天吃了 $done / $total 次", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = K.ink)
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(K.line)) {
                Box(Modifier.fillMaxWidth(if (total == 0) 0f else done / total.toFloat()).height(22.dp).clip(RoundedCornerShape(11.dp)).background(K.teal))
            }
            Text(if (all) "今天的花開了！" else if (total == 0) "今天沒有要吃的藥" else "再 ${total - done} 次，今天的花就會開", fontSize = 28.sp, color = K.ink2)
            Text("已經開了 $flowers 朵花", fontSize = 28.sp, color = K.ink2)
        }
    }
}

@Composable
private fun DayList(vm: AppViewModel, list: List<Occurrence>, withChips: Boolean) {
    val now = Clock.nowMs()
    KCard(Modifier.verticalScroll(rememberScrollState())) {
        if (list.isEmpty()) Text("沒有要吃的藥", fontSize = 32.sp, color = K.ink2, modifier = Modifier.padding(24.dp))
        list.forEachIndexed { i, o ->
            val tappable = withChips && ReminderEngine.canTake(o, now)
            Box(if (tappable) Modifier.plainClick { vm.listTap(o) } else Modifier) {
                TimeRow(Clock.hm(o.scheduledAt), "${o.slotLabel}・${medLine(o)}", if (withChips) ({ StatusChip(o, tappable) }) else null, last = i == list.lastIndex)
            }
        }
        if (withChips && list.any { ReminderEngine.canTake(it, now) })
            Text("可以吃的藥，點一下那一行就能按「吃了」（逾時的會記成晚吃）", fontSize = 24.sp, color = K.amber, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    }
}

@Composable
private fun StatusChip(o: Occurrence, tappable: Boolean) = when (o.status) {
    OccStatus.TAKEN -> KChip(if (o.late) "晚吃 ✓" else "吃了 ✓", ChipKind.OK)
    OccStatus.REMINDING -> KChip("提醒中・點我", ChipKind.NOW)
    OccStatus.SNOOZED -> KChip("稍後提醒・點我", ChipKind.NOW)
    OccStatus.OVERDUE -> KChip("還沒吃・點我", ChipKind.NOW)
    OccStatus.MISSED -> KChip("漏服", ChipKind.MISS)
    OccStatus.NOT_REMINDED -> KChip("關機沒提醒到", ChipKind.SKIP)
    OccStatus.PENDING -> KChip(if (tappable) "可以吃了・點我" else "還沒到", ChipKind.TODO)
}

/**
 * 加藥精靈（D38）。按一個答案就到下一步；建議的答案（藥袋上寫的、目錄預設、或剛剛選過的）用實心標出來，
 * 長者照著按就好。最後一頁確認才送申請。
 */
@Composable
private fun AddMedScreen(vm: AppViewModel, st: AppState, d: AddDraft) {
    val today = vm.todayDate()
    val routine = st.device.routine
    val say = d.question(today, routine)
    if (d.step == AddStep.CONFIRM) {
        KScreen(say, buttons = {
            KButton("重新回答") { vm.addAnswer(d.restart()) }
            KButton("對，加上去", primary = true) { vm.submitAdd(d) }
        }) { AddSummary(d, today, routine) }
        return
    }
    val hint = d.markHint(d.step)
    KScreen(say, buttons = {
        KButton(if (d.step == AddStep.TIMES && d.fromScan) "不是這個藥" else "上一步") { vm.addBack(d) }
        KButton("不加了") { vm.cancelAdd() }
    }) {
        Grid {
            when (d.step) {
                AddStep.TIMES -> d.timesOptions.forEach { n ->
                    val marked = n == d.markedTimes
                    GridButton(d.timesLabel(n), if (marked) hint else if (n == 0) "不會定時提醒" else null, marked = marked) { vm.addAnswer(d.answerTimes(n)) }
                }
                AddStep.MEAL -> d.mealOptions(d.markedTimes).forEach { m ->
                    val marked = m == d.markedMeal
                    GridButton(d.mealLabel(m), if (marked) hint else null, marked = marked) { vm.addAnswer(d.answerMeal(m)) }
                }
                AddStep.DOSE -> d.doseOptions.forEach { n ->
                    val marked = n == d.markedDose
                    GridButton(d.doseLabel(n), if (marked) hint else null, marked = marked) { vm.addAnswer(d.answerDose(n)) }
                }
                AddStep.DAYS -> d.daysOptions.forEach { n ->
                    val marked = d.isMarkedDays(n)
                    GridButton(d.daysLabel(n), if (marked) hint else if (n == null) "直到醫師說停" else null, marked = marked) { vm.addAnswer(d.answerDays(n)) }
                }
                AddStep.CONFIRM -> {}
            }
        }
    }
}

/** 加藥精靈的確認頁：藥名、什麼時候吃、每次幾顆、吃幾天。 */
@Composable
private fun AddSummary(d: AddDraft, today: java.time.LocalDate, routine: tw.kebbi.medreminder.api.reference.RoutineMinutes) {
    KCard(Modifier.width(900.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(d.entry.displayName, fontSize = 44.sp, fontWeight = FontWeight.Black, color = K.ink)
            Text("　${d.entry.genericName}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = K.teal)
        }
        val rows = buildList {
            add("什麼時候吃" to d.whenText(routine))
            add("每次" to d.doseLabel(d.markedDose))
            if (d.markedTimes != 0) add("吃幾天" to d.daysText(today))
        }
        rows.forEach { (k, v) ->
            Box(Modifier.fillMaxWidth().height(2.dp).background(K.line))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, Modifier.width(200.dp), fontSize = 28.sp, color = K.ink2, fontWeight = FontWeight.Bold)
                Text(v, Modifier.weight(1f), fontSize = 32.sp, color = K.ink, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
            }
        }
    }
}

@Composable
private fun DoseCard(o: Occurrence) {
    KCard(Modifier.width(820.dp)) {
        o.items.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, Modifier.weight(1f), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = K.ink)
                Text(listOfNotNull(m.genericName, m.doseText).joinToString("・"), fontSize = 28.sp, color = K.ink2)
            }
            if (i < o.items.lastIndex) Box(Modifier.fillMaxWidth().height(2.dp).background(K.line))
        }
    }
}

@Composable
private fun MedListCard(vm: AppViewModel, st: AppState) {
    val meds = st.device.medications
    KCard(Modifier.width(900.dp).verticalScroll(rememberScrollState())) {
        if (meds.isEmpty()) Text("現在沒有記著的藥", fontSize = 32.sp, color = K.ink2, modifier = Modifier.padding(24.dp))
        meds.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(m.displayName, Modifier.width(200.dp), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = K.teal)
                Text((if (m.slots.isEmpty()) "需要時才吃" else vm.medWhen(m)) +
                    (if (m.dose.text != "1 顆") "（${m.dose.text}）" else "") + (vm.endText(m)?.let { "，$it" } ?: "") + "・${m.genericName ?: ""}",
                    Modifier.weight(1f), fontSize = 26.sp, color = K.ink, lineHeight = 36.sp)
            }
            if (i < meds.lastIndex) Box(Modifier.fillMaxWidth().height(2.dp).background(K.line))
        }
    }
}

/** 四張時間卡（早餐、午餐、晚餐、睡覺），每次 ±15 分鐘。 */
@Composable
private fun TimeCards(s: RoutineMinutes, onChange: (RoutineMinutes) -> Unit) {
    @Composable
    fun card(label: String, v: Int, range: IntRange, set: (Int) -> RoutineMinutes) {
        KCard(Modifier.width(520.dp)) {
            Text(label, fontSize = 28.sp, color = K.ink2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                KButtonBase(Modifier.size(96.dp), "−", enabled = v - 15 >= range.first, onClick = { onChange(set(v - 15)) })
                Text(ApiConventions.formatHm(v), fontSize = 64.sp, fontWeight = FontWeight.Black, color = K.ink)
                KButtonBase(Modifier.size(96.dp), "＋", enabled = v + 15 <= range.last, onClick = { onChange(set(v + 15)) })
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            card("早餐", s.breakfast, Rules.RANGE_BREAKFAST) { s.copy(breakfast = it) }
            card("午餐", s.lunch, Rules.RANGE_LUNCH) { s.copy(lunch = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            card("晚餐", s.dinner, Rules.RANGE_DINNER) { s.copy(dinner = it) }
            card("睡覺", s.sleep, Rules.RANGE_SLEEP) { s.copy(sleep = it) }
        }
    }
}
