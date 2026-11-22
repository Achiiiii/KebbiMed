package tw.kebbi.medreminder.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tw.kebbi.medreminder.data.AppData
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.DoseStatus
import tw.kebbi.medreminder.data.MedDb
import tw.kebbi.medreminder.data.Schedule
import tw.kebbi.medreminder.data.Slot
import tw.kebbi.medreminder.engine.ReminderEngine
import tw.kebbi.medreminder.engine.Rules

/** 依目前畫面畫出內容。 */
@Composable
fun Screens(vm: AppViewModel, d: AppData) {
    val gear = { vm.go(Screen.Settings) }
    val exit = { vm.go(Screen.ExitConfirm) }
    when (val s = vm.screen) {
        is Screen.Splash -> SplashAnimation("用藥提醒", "我會陪您按時吃藥") {
            if (s.home) vm.go(Screen.Enter) else vm.startStationSetup()
        }
        Screen.Enter -> {
            LaunchedEffect(Unit) { delay(2000); vm.go(Screen.Home) }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FloatingPills(); VSpace(30)
                Text("用藥提醒", fontSize = 88.sp, fontWeight = FontWeight.Black, color = K.ink)
                Text("幫您看看今天的藥…", fontSize = 34.sp, color = K.ink2)
                Text("點點看藥丸", fontSize = 28.sp, color = K.teal, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            }
        }

        // ---------------- ① 站點設定 ----------------
        Screen.Routine -> KScreen("要改吃飯、睡覺的時間嗎？先選一個最接近的。", buttons = {
            KButton("早一點", sub = "5:30・11:00・17:00・睡 20:30") { vm.choosePreset(Schedule.EARLY) }
            KButton("一般", primary = true, sub = "8:00・12:30・18:30・睡 22:00") { vm.choosePreset(Schedule.NORMAL) }
            KButton("晚一點", sub = "9:00・13:00・19:00・睡 22:00") { vm.choosePreset(Schedule.LATE) }
        }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                KButtonBase(Modifier.width(760.dp).height(120.dp), "從現在的時間調整",
                    sub = "早餐 ${Clock.fmt(d.schedule.breakfast)}・午餐 ${Clock.fmt(d.schedule.lunch)}・晚餐 ${Clock.fmt(d.schedule.dinner)}・睡覺 ${Clock.fmt(d.schedule.sleep)}",
                    onClick = { vm.go(Screen.RoutineCards(d.schedule)) })
                Text("返回設定", fontSize = 28.sp, color = K.ink2, modifier = Modifier.plainClick { vm.go(Screen.Settings) })
            }
        }

        is Screen.RoutineCards -> {
            var draft by remember(s) { mutableStateOf(s.draft) }
            KScreen("三餐和睡覺大概幾點？按 − ＋ 調整，每次 15 分鐘。", buttons = {
                KButton("好了", primary = true) { vm.go(Screen.RoutineConfirm(draft)) }
            }) { TimeCards(draft) { draft = it } }
        }

        is Screen.RoutineConfirm -> KScreen("好，這是長者的作息時間。這樣對嗎？", buttons = {
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

        Screen.TakeOut -> KScreen("請拿出長者的一包藥，準備好了嗎？", buttons = {
            KButton("等一下") { vm.go(Screen.Wait) }
            KButton("沒有藥了") { vm.go(Screen.SetupDone) }
            KButton("好了", primary = true) { vm.go(Screen.Scan()) }
        }) { FloatingPills() }

        Screen.Wait -> KScreen("好，準備好再按我。", buttons = {
            KButton("沒有藥了") { vm.go(Screen.SetupDone) }
            KButton("好了", primary = true) { vm.go(Screen.Scan()) }
        }) { FloatingPills() }

        // 不用按按鈕：掃到 QR Code 就直接加；幾秒內看不到 QR Code，就自動拍下藥袋給照服員確認。
        // 拿掉下方按鈕列，讓相機畫面盡量大；「返回」「改用選的」改成右上角的小按鈕。
        is Screen.Scan -> KScreen("請把藥袋拿到我眼睛前面，不用按，我會自己拍。") {
            Box(Modifier.size(1220.dp, 600.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF263A40)), contentAlignment = Alignment.Center) {
                QrScanner(
                    onResult = { vm.onScanned(it, s.mode) },
                    onPhoto = { vm.onPhoto(it, s.mode) },
                    onNoCamera = { vm.go(Screen.PickCat(s.mode)) },
                )
                Row(Modifier.align(Alignment.TopEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OverlayPill("改用選的") { vm.go(Screen.PickCat(s.mode)) }
                    OverlayPill("返回") { vm.go(if (s.mode == PickMode.HOME) Screen.Settings else Screen.TakeOut) }
                }
            }
        }

        is Screen.ScanFail -> KScreen("我看不清楚這個 QR Code。要再試一次，還是改用問的？", buttons = {
            KButton("改用問的") { vm.go(Screen.PickCat(s.mode)) }
            KButton("再試一次", primary = true) { vm.go(Screen.Scan(s.mode)) }
        }) { FloatingPills() }

        is Screen.PickCat -> KScreen("這是哪一類的藥？藥袋上有寫用途。") {
            Grid {
                MedDb.categories.forEach { c -> GridButton(c, "${MedDb.inCategory(c).size} 種") { vm.go(Screen.PickMed(s.mode, c)) } }
                if (s.mode == PickMode.STATION) GridButton("其他・看不懂", "拍照存起來", ghost = true) { vm.go(Screen.Photo) }
                GridButton("返回", "回上一步", ghost = true) { vm.go(Screen.Scan(s.mode)) }
            }
        }

        is Screen.PickMed -> KScreen("${s.cat}的藥裡面，是哪一個？請對照藥袋上的藥名。") {
            Grid {
                MedDb.inCategory(s.cat).forEach { m ->
                    val has = m.id in d.meds
                    GridButton(m.generic, if (has) "已經有了" else m.brand, enabled = !has) { vm.go(Screen.ConfirmMed(s.mode, m.id, false)) }
                }
                if (s.mode == PickMode.STATION) GridButton("不在這裡", "拍照存起來", ghost = true) { vm.go(Screen.Photo) }
                GridButton("換一類", "回上一步", ghost = true) { vm.go(Screen.PickCat(s.mode)) }
            }
        }

        is Screen.ConfirmMed -> {
            val m = MedDb.get(s.id)!!
            KScreen(vm.confirmSay(s.id), buttons = {
                KButton("不對") { vm.go(if (s.scanned) Screen.PickCat(s.mode) else Screen.PickMed(s.mode, m.category)) }
                KButton("對", primary = true) { vm.addMed(s.mode, s.id) }
            }) {
                KCard(Modifier.width(900.dp)) {
                    VSpace(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.generic, fontSize = 40.sp, fontWeight = FontWeight.Black, color = K.ink)
                        Text("　${m.name}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = K.teal)
                    }
                    Text("${m.brand}・每次 ${m.dose}" + if (m.weeklyOn != null) "・每週一次（週一）" else "", fontSize = 28.sp, color = K.ink2)
                    Text(if (m.slots.isEmpty()) "需要時才吃，不會定時提醒" else m.slots.joinToString("　") { vm.slotLabel(d, it) },
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = K.ink, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }

        Screen.Photo -> KScreen("好，請照服員之後在後台補登這包藥。我們先看下一包。", buttons = {
            KButton("下一包", primary = true) { vm.go(Screen.TakeOut) }
        }) { FloatingPills() }

        Screen.More -> KScreen("好，記好了，已經有 ${d.meds.size} 種藥。還有其他藥嗎？", buttons = {
            KButton("沒有了") { vm.go(Screen.SetupDone) }
            KButton("有", primary = true) { vm.go(Screen.TakeOut) }
        }) { FloatingPills() }

        Screen.SetupDone -> if (d.meds.isEmpty()) KScreen("還沒加任何藥喔。要先拿出一包藥嗎？", buttons = {
            KButton("完成，帶回家") { vm.finishSetup() }
            KButton("拿出一包藥", primary = true) { vm.go(Screen.TakeOut) }
        }) { FloatingPills() } else KScreen("設定完成！共 ${d.meds.size} 種藥。請跟長者確認一次提醒時間。", buttons = {
            KButton("再加藥") { vm.go(Screen.TakeOut) }
            KButton("完成，帶回家", primary = true) { vm.finishSetup() }
        }) { MedListCard(vm, d) }

        // ---------------- ② 居家 ----------------
        Screen.Home -> KScreen(vm.homeSay(d), onGear = gear, onExit = exit, buttons = {
            KButton("今天的藥", primary = true) { vm.go(Screen.Today) }
            KButton("明天的藥") { vm.go(Screen.Tomorrow) }
        }) {
            NowClock(Modifier.align(Alignment.TopEnd))
            Progress(d)
        }

        Screen.Today -> KScreen(vm.homeSay(d), onGear = gear, onExit = exit, buttons = {
            KButton("回首頁", primary = true) { vm.go(Screen.Home) }
        }) { TodayList(vm, d, withChips = true) }

        Screen.Tomorrow -> KScreen("明天的藥跟今天一樣，時間到我會提醒您。", onGear = gear, onExit = exit, buttons = {
            KButton("回首頁", primary = true) { vm.go(Screen.Home) }
        }) { TodayList(vm, d, withChips = false) }

        is Screen.Remind -> KScreen(vm.remindText(d, s.slot), buttons = {
            KButton("等一下", sub = "${Rules.SNOOZE_MIN} 分鐘後再叫我") { vm.snooze(s.slot) }
            KButton("吃了", primary = true) { vm.take(s.slot) }
        }) { DoseCard(d, s.slot) }

        is Screen.RemindAgain -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KCard(Modifier.width(940.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    KChipBig("已經過了 ${Rules.REMIND_AGAIN_MIN} 分鐘", K.amberLight, K.amber)
                    Text("${ReminderEngine.doseText(d, s.slot, withTime = true)}還沒吃喔", fontSize = 56.sp, fontWeight = FontWeight.Black, color = K.ink, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center)
                    Text(medLine(d, s.slot) + "\n現在吃也可以，吃完請按「吃了」", fontSize = 30.sp, color = K.ink2, textAlign = TextAlign.Center, lineHeight = 42.sp, modifier = Modifier.padding(vertical = 16.dp))
                    Row(Modifier.width(800.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        KButton("等一下") { vm.snooze(s.slot) }
                        KButton("吃了", primary = true) { vm.take(s.slot) }
                    }
                    Text("再過 ${Rules.MISS_AFTER_MIN - Rules.REMIND_AGAIN_MIN} 分鐘沒按，我會通知站點照服員。", fontSize = 24.sp, color = K.ink2, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }

        is Screen.Reward -> {
            val left = ReminderEngine.counted(d).size - ReminderEngine.takenCount(d)
            KScreen(if (ReminderEngine.allDone(d)) "全部吃完了！今天的花開了，好棒！" else "很好，我記下來了！今天還有 $left 次。", buttons = {
                KButton("好", primary = true) { vm.go(Screen.Home) }
            }) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) { CheerRobot(); Progress(d) } }
        }

        Screen.Snoozed -> KScreen("好，${Rules.SNOOZE_MIN} 分鐘後再提醒您。", buttons = {
            KButton("好", primary = true) { vm.go(Screen.Home) }
        }) { FloatingPills() }

        // 逾時未服（時間到 30 分鐘還沒吃）：通知照服員，但還可以晚吃
        is Screen.Missed -> KScreen("${ReminderEngine.doseText(d, s.slot)}還沒吃，已經過了 ${Rules.MISS_AFTER_MIN} 分鐘，我通知照服員了。現在吃也可以。", buttons = {
            KButton("等一下再說") { vm.go(Screen.Home) }
            KButton("現在吃", primary = true) { vm.askLate(s.slot) }
        }) {
            KCard(Modifier.width(860.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    KChipBig("逾時未服", K.amberLight, K.amber)
                    Text(vm.slotLabel(d, s.slot), fontSize = 56.sp, fontWeight = FontWeight.Black, color = K.ink, modifier = Modifier.padding(top = 14.dp))
                    Text(medLine(d, s.slot) + "\n最晚 ${Clock.fmt(kotlin.math.floor(ReminderEngine.lateCutoffMin(d, s.slot)).toInt())} 前吃，會記成「晚吃」。", fontSize = 28.sp, color = K.ink2, textAlign = TextAlign.Center, lineHeight = 40.sp)
                }
            }
        }

        // 漏吃 → 補吃（先問要不要補，再確認一次；太接近下一次就請長者不要補）
        is Screen.LateAsk -> KScreen("${ReminderEngine.doseText(d, s.slot)}沒吃到。現在要補吃嗎？", buttons = {
            KButton("不吃了") { vm.go(Screen.Today) }
            KButton("要補吃", primary = true) { vm.confirmLate(s.slot) }
        }) { DoseCard(d, s.slot) }

        is Screen.LateConfirm -> KScreen("確定現在要吃${ReminderEngine.medNames(d, s.slot).ifEmpty { "這包藥" }}嗎？請照藥袋上的量吃，不要多吃。", buttons = {
            KButton("先不要") { vm.go(Screen.Today) }
            KButton("確定，吃好了", primary = true) { vm.lateTake(s.slot) }
        }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                KChipBig("晚吃 ${vm.slotLabel(d, s.slot)} 的藥", K.amberLight, K.amber)
                VSpace(20)
                DoseCard(d, s.slot)
            }
        }

        is Screen.LateNo -> KScreen(s.reason, buttons = {
            KButton("知道了", primary = true) { vm.go(Screen.Home) }
        }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                KChipBig("這次不要吃了", K.redLight, K.red)
                VSpace(20)
                DoseCard(d, s.slot)
            }
        }

        Screen.Standby -> {
            val done = ReminderEngine.allDone(d)
            val next = ReminderEngine.nextSlot(d)
            Column(Modifier.fillMaxSize().plainClick { vm.go(Screen.Home) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (done) Flower(2) else FloatingPills()
                Text("用藥提醒", fontSize = 88.sp, fontWeight = FontWeight.Black, color = K.ink, modifier = Modifier.padding(top = 20.dp))
                Text(if (done) "今天的藥都吃完了，花開了！" else if (next != null) "下一次：${vm.slotLabel(d, next)}" else "今天沒有要吃的藥了", fontSize = 34.sp, color = K.ink2)
                Text("先休息一下，時間到會再叫您", fontSize = 34.sp, color = K.ink2)
                Text("輕觸螢幕回到首頁", fontSize = 28.sp, color = K.teal, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            }
        }

        is Screen.RemoteUpdate -> KScreen(s.text, buttons = {
            KButton("看今天的藥") { vm.go(Screen.Today) }
            KButton("知道了", primary = true) { vm.go(Screen.Home) }
        }) { MedListCard(vm, d) }

        // ---------------- ③ 設定模式 ----------------
        Screen.Settings -> KScreen("這裡是設定。要做什麼呢？") {
            Column(horizontalAlignment = Alignment.End) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        KButtonBase(Modifier.width(480.dp).height(140.dp), "新增藥品", primary = true, sub = "拍藥袋（QR Code 或藥名都可以）", onClick = { vm.go(Screen.Scan(PickMode.HOME)) })
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

        is Screen.AdjustTime -> {
            var draft by remember(s) { mutableStateOf(s.draft) }
            KScreen("要改幾點吃飯、睡覺？按 − ＋ 調整，每次 15 分鐘。", buttons = {
                KButton("取消") { vm.go(Screen.Settings) }
                KButton("改好了", primary = true) { vm.saveTime(draft) }
            }) { TimeCards(draft) { draft = it } }
        }

        Screen.TimeSaved -> KScreen("時間改好了！我會照新的時間提醒您，也會通知站點照服員。", buttons = {
            KButton("回首頁", primary = true) { vm.go(Screen.Home) }
        }) { TodayList(vm, d, withChips = false) }

        Screen.DeleteList -> KScreen(if (d.meds.isEmpty()) "現在沒有記著的藥。" else "要拿掉哪一種藥？下面是現在記著的藥。") {
            Grid {
                d.meds.mapNotNull { MedDb.get(it) }.forEach { m -> GridButton(m.generic, m.name) { vm.go(Screen.DeleteConfirm(m.id)) } }
                GridButton("取消", "回上一步", ghost = true) { vm.go(Screen.Settings) }
            }
        }

        is Screen.DeleteConfirm -> {
            val m = MedDb.get(s.id)!!
            KScreen("停藥要先問過醫師喔。醫師說「${m.generic}（${m.name}）」可以不用吃了嗎？", buttons = {
                KButton("還沒問，先不要") { vm.go(Screen.DeleteList) }
                KButton("醫師說可以停", primary = true) { vm.deleteMed(s.id) }
            }) { FloatingPills() }
        }

        Screen.Deleted -> KScreen("好，已經拿掉了。我也會通知站點照服員再確認一次。", buttons = {
            KButton("回首頁") { vm.go(Screen.Home) }
            KButton("看今天的藥", primary = true) { vm.go(Screen.Today) }
        }) { MedListCard(vm, d) }

        is Screen.Added -> {
            val m = s.id?.let { MedDb.get(it) }
            val head = when {
                s.photo -> "拍好了！照服員會在後台幫您確認這包藥。還有其他藥嗎？"
                m != null -> "記好了：${m.name}，" + (if (m.slots.isEmpty()) "需要時才吃。" else m.slots.joinToString("、") { it.label } + "吃。") +
                    (if (d.slots.values.any { it.status == DoseStatus.SKIP }) "今天的時間已經過了，明天開始提醒。" else "") + "還有其他藥嗎？"
                else -> "記好了！現在共 ${d.meds.size} 種藥。還有其他藥嗎？"
            }
            KScreen(head, buttons = {
                KButton("沒有了") { vm.go(Screen.Today) }
                KButton("有，再拍一包", primary = true) { vm.go(Screen.Scan(PickMode.HOME)) }
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m != null) {
                        KCard(Modifier.width(900.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, fontSize = 44.sp, fontWeight = FontWeight.Black, color = K.ink)
                                Text("　${m.generic}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = K.teal)
                            }
                            Text("每次 ${m.dose}・" + (if (m.slots.isEmpty()) "需要時才吃" else m.slots.joinToString("　") { vm.slotLabel(d, it) }),
                                fontSize = 30.sp, color = K.ink2, modifier = Modifier.padding(top = 8.dp))
                        }
                        Text("不對，拿掉這個藥 ›", fontSize = 26.sp, color = K.ink2,
                            modifier = Modifier.padding(top = 20.dp).plainClick { vm.undoAdd(m.id) })
                    } else FloatingPills()
                }
            }
        }

        Screen.ExitConfirm -> {
            val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            KScreen("要離開用藥提醒嗎？時間到了，我還是會叫您吃藥。", buttons = {
                KButton("不要，留下來") { vm.go(Screen.Home) }
                KButton("離開", primary = true) { vm.onExit(); vm.go(Screen.Home); act?.finishAndRemoveTask() }
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingPills()
                    Text("再見！下次吃藥時間到了，我會自己出現提醒您。", fontSize = 30.sp, color = K.ink2, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }

        Screen.Caregiver -> KScreen("照服員設定：${d.stationId}站・${d.elderName}（${d.elderId}）", buttons = {
            KButton("返回") { vm.go(Screen.Settings) }
            KButton("清空藥單重新設定", primary = true) { vm.startStationSetup() }
        }) {
            // 測試用的小按鈕放最上面，下面的說明可以捲動，避免字太多時按鈕被擠出畫面
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SmallLink("測試說話") { vm.say("您好，我是用藥提醒，聽得到我說話嗎？") }
                    SmallLink("快轉到下一次提醒前 10 秒") { vm.testJumpToNext() }
                    SmallLink("回到真實時間", primary = false) { vm.testResetClock() }
                }
                val voiceStatus = tw.kebbi.medreminder.robot.Robot.voice.status.ifEmpty { "（沒有狀態）" }
                Text("語音：$voiceStatus", fontSize = 24.sp, color = K.ink2, textAlign = TextAlign.Center, lineHeight = 34.sp,
                    modifier = Modifier.width(1000.dp).padding(top = 24.dp))
                Text("裝置編號：${tw.kebbi.medreminder.sync.CloudSync.deviceId}（要換長者，請在後台改這台的 elderId）\n「清空藥單重新設定」會拿掉所有的藥，從掃第一包藥開始。",
                    fontSize = 24.sp, color = K.ink2, textAlign = TextAlign.Center, lineHeight = 34.sp,
                    modifier = Modifier.width(1000.dp).padding(top = 16.dp))
            }
        }
    }
}

// ---------------- 共用區塊 ----------------

/** 疊在相機畫面上的小按鈕。 */
@Composable
private fun OverlayPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = .92f))
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 28.sp, color = K.teal, fontWeight = FontWeight.Bold) }
}

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

private fun medLine(d: AppData, s: Slot) =
    ReminderEngine.medsIn(d, s).joinToString("・") { it.name + if (it.dose != "1 顆") "（${it.dose}）" else "" }

@Composable
private fun NowClock(modifier: Modifier) {
    var now by remember { mutableStateOf(Clock.fmt(Clock.minuteOfDay().toInt())) }
    LaunchedEffect(Unit) { while (true) { now = Clock.fmt(Clock.minuteOfDay().toInt()); delay(5000) } }
    Text("現在 $now", modifier, fontSize = 28.sp, color = K.ink2, fontWeight = FontWeight.Medium)
}

@Composable
private fun Progress(d: AppData) {
    val total = ReminderEngine.counted(d).size
    val done = ReminderEngine.takenCount(d)
    val all = ReminderEngine.allDone(d)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Flower(if (all) 2 else if (done > 0) 1 else 0)
        Column(Modifier.width(400.dp)) {
            Text("今天吃了 $done / $total 次", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = K.ink)
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(K.line)) {
                Box(Modifier.fillMaxWidth(if (total == 0) 0f else done / total.toFloat()).height(22.dp).clip(RoundedCornerShape(11.dp)).background(K.teal))
            }
            Text(if (all) "今天的花開了！" else if (total == 0) "今天沒有要吃的藥" else "再 ${total - done} 次，今天的花就會開", fontSize = 28.sp, color = K.ink2)
            Text("已經開了 ${d.flowers + if (all) 1 else 0} 朵花", fontSize = 28.sp, color = K.ink2)
        }
    }
}

@Composable
private fun TodayList(vm: AppViewModel, d: AppData, withChips: Boolean) {
    val slots = ReminderEngine.activeSlots(d)
    KCard(Modifier.verticalScroll(rememberScrollState())) {
        if (slots.isEmpty()) Text("沒有要吃的藥", fontSize = 32.sp, color = K.ink2, modifier = Modifier.padding(24.dp))
        slots.forEachIndexed { i, s ->
            val st = d.slots[s]
            val overdue = withChips && st?.status == DoseStatus.MISSED && !st.lapsed
            Box(if (overdue) Modifier.plainClick { vm.askLate(s) } else Modifier) {
                if (withChips) TimeRow(Clock.fmt(d.schedule.timeOf(s)), "${s.label}・${medLine(d, s)}", { StatusChip(st) }, last = i == slots.lastIndex)
                else TimeRow(Clock.fmt(d.schedule.timeOf(s)), "${s.label}・${medLine(d, s)}", null, last = i == slots.lastIndex)
            }
        }
        if (withChips && slots.any { d.slots[it]?.let { st -> st.status == DoseStatus.MISSED && !st.lapsed } == true })
            Text("還沒吃的藥，點一下那一行可以現在吃（記成晚吃）", fontSize = 24.sp, color = K.amber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        if (withChips && slots.any { d.slots[it]?.status == DoseStatus.SKIP })
            Text("「明天開始」：今天新加的藥，吃藥時間已經過了，明天開始提醒", fontSize = 24.sp, color = K.ink2, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    }
}

@Composable
private fun StatusChip(st: tw.kebbi.medreminder.data.SlotState?) = when (st?.status) {
    DoseStatus.TAKEN -> KChip(if (st?.note == "晚吃" || st?.note == "補吃") "晚吃 ✓" else "吃了 ✓", ChipKind.OK)
    DoseStatus.REMINDING -> KChip("提醒中", ChipKind.NOW)
    DoseStatus.SNOOZED -> KChip("稍後提醒", ChipKind.NOW)
    DoseStatus.MISSED -> if (st?.lapsed == true) KChip("漏服", ChipKind.MISS) else KChip("還沒吃・點我", ChipKind.NOW)
    DoseStatus.SKIP -> KChip("明天開始", ChipKind.SKIP)
    else -> KChip("還沒吃", ChipKind.TODO)
}

@Composable
private fun DoseCard(d: AppData, s: Slot) {
    KCard(Modifier.width(820.dp)) {
        ReminderEngine.medsIn(d, s).forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, Modifier.weight(1f), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = K.ink)
                Text("${m.generic}・${m.dose}", fontSize = 28.sp, color = K.ink2)
            }
            if (i < ReminderEngine.medsIn(d, s).lastIndex) Box(Modifier.fillMaxWidth().height(2.dp).background(K.line))
        }
    }
}

@Composable
private fun MedListCard(vm: AppViewModel, d: AppData) {
    val meds = d.meds.mapNotNull { MedDb.get(it) }
    KCard(Modifier.width(900.dp).verticalScroll(rememberScrollState())) {
        if (meds.isEmpty()) Text("現在沒有記著的藥", fontSize = 32.sp, color = K.ink2, modifier = Modifier.padding(24.dp))
        meds.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(m.name, Modifier.width(200.dp), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = K.teal)
                Text((if (m.slots.isEmpty()) "需要時才吃" else m.slots.joinToString("、") { vm.slotLabel(d, it) }) +
                    (if (m.dose != "1 顆") "（${m.dose}）" else "") + "・${m.generic}",
                    Modifier.weight(1f), fontSize = 26.sp, color = K.ink, lineHeight = 36.sp)
            }
            if (i < meds.lastIndex) Box(Modifier.fillMaxWidth().height(2.dp).background(K.line))
        }
    }
}

/** 四張時間卡（早餐、午餐、晚餐、睡覺），每次 ±15 分鐘。 */
@Composable
private fun TimeCards(s: Schedule, onChange: (Schedule) -> Unit) {
    @Composable
    fun card(label: String, v: Int, range: IntRange, set: (Int) -> Schedule) {
        KCard(Modifier.width(520.dp)) {
            Text(label, fontSize = 28.sp, color = K.ink2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                KButtonBase(Modifier.size(96.dp), "−", enabled = v - 15 >= range.first, onClick = { onChange(set(v - 15)) })
                Text(Clock.fmt(v), fontSize = 64.sp, fontWeight = FontWeight.Black, color = K.ink)
                KButtonBase(Modifier.size(96.dp), "＋", enabled = v + 15 <= range.last, onClick = { onChange(set(v + 15)) })
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            card("早餐", s.breakfast, Schedule.RANGE_BREAKFAST) { s.copy(breakfast = it) }
            card("午餐", s.lunch, Schedule.RANGE_LUNCH) { s.copy(lunch = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            card("晚餐", s.dinner, Schedule.RANGE_DINNER) { s.copy(dinner = it) }
            card("睡覺", s.sleep, Schedule.RANGE_SLEEP) { s.copy(sleep = it) }
        }
    }
}
