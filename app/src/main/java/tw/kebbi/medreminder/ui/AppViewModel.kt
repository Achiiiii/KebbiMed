package tw.kebbi.medreminder.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.kebbi.medreminder.data.AppData
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.MedDb
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.data.Schedule
import tw.kebbi.medreminder.data.Slot
import tw.kebbi.medreminder.engine.EngineEvent
import tw.kebbi.medreminder.engine.ReminderEngine
import tw.kebbi.medreminder.engine.Rules
import tw.kebbi.medreminder.robot.Motion
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.sync.CloudSync

/** 選藥流程是站點設定（照服員）還是居家設定（長者）用的。 */
enum class PickMode { STATION, HOME }

/** 所有畫面。名稱對應 Figma 的畫面代號。 */
sealed class Screen {
    data class Splash(val home: Boolean) : Screen()   // S0 / S0h 啟動動畫
    data object Enter : Screen()                      // L0 進入用藥提醒
    // ① 站點設定
    data object Routine : Screen()                    // A1 作息：三餐大概幾點
    data class RoutineCards(val draft: Schedule) : Screen() // A1b 時間卡
    data class RoutineConfirm(val draft: Schedule) : Screen() // A3 確認
    data object TakeOut : Screen()                    // B1 拿出一包藥
    data object Wait : Screen()                       // B1a 等一下
    data class Scan(val mode: PickMode = PickMode.STATION) : Screen()     // B2 / H1 掃描 QR Code
    data class ScanFail(val mode: PickMode = PickMode.STATION) : Screen() // B2a 掃不到
    data class PickCat(val mode: PickMode) : Screen() // M1 / H1 藥的大分類
    data class PickMed(val mode: PickMode, val cat: String) : Screen() // M1b / H2
    data class ConfirmMed(val mode: PickMode, val id: String, val scanned: Boolean) : Screen() // B2b / M5 / H3
    data object Photo : Screen()                      // X 拍照存起來（請照服員補登）
    data object More : Screen()                       // B3 還有其他藥嗎
    data object SetupDone : Screen()                  // B4 / B4-0 設定完成
    // ② 居家
    data object Home : Screen()                       // K0
    data object Today : Screen()                      // K1
    data object Tomorrow : Screen()                   // K2
    data class Remind(val slot: Slot) : Screen()      // C1
    data class RemindAgain(val slot: Slot) : Screen() // C1b
    data class Reward(val slot: Slot) : Screen()      // C2
    data object Snoozed : Screen()                    // C3
    data class LateAsk(val slot: Slot) : Screen()     // C5 漏吃：要補吃嗎？
    data class LateConfirm(val slot: Slot) : Screen() // C6 確定補吃嗎？
    data class LateNo(val slot: Slot, val reason: String) : Screen() // C7 這次不要補吃
    data class Missed(val slot: Slot) : Screen()      // C4
    data object Standby : Screen()                    // L1 / L1-開花
    data class RemoteUpdate(val text: String) : Screen() // K3 藥有更新
    // ③ 設定模式
    data object Settings : Screen()                   // H0
    data class AdjustTime(val draft: Schedule) : Screen() // H5
    data object TimeSaved : Screen()                  // H6
    data object DeleteList : Screen()                 // H7
    data class DeleteConfirm(val id: String) : Screen() // H8
    data object Deleted : Screen()                    // H9
    /** 加好了一包藥。id＝掃到 QR Code 加的藥；photo＝掃不到、拍下藥袋等照服員確認。 */
    data class Added(val id: String? = null, val photo: Boolean = false) : Screen()  // H4
    data object Caregiver : Screen()                  // 照服員選單
    data object ExitConfirm : Screen()                // 離開 App 前確認
}

class AppViewModel : ViewModel() {
    val data get() = Repo.data
    var screen by mutableStateOf<Screen>(Screen.Splash(home = true))
        private set
    private var lastTouch = SystemClock.elapsedRealtime()
    private var enteredAt = SystemClock.elapsedRealtime()

    init {
        val d = Repo.value
        // 開機一律先播啟動動畫；長者是誰由後台指定（見 CloudSync 的 robots/{裝置編號}）
        screen = Screen.Splash(home = true)
        viewModelScope.launch {
            while (isActive) {
                loop()
                delay(1000)
            }
        }
    }

    fun onResume() { Repo.tick(); handleEvents() }

    fun touch() { lastTouch = SystemClock.elapsedRealtime() }

    fun go(s: Screen) {
        screen = s; enteredAt = SystemClock.elapsedRealtime(); touch()
    }

    private val inFlow get() = screen is Screen.Splash || screen is Screen.Enter || !Repo.value.setupDone

    private fun loop() {
        Repo.tick()
        handleEvents()
        val d = Repo.value
        // 照服員從後台改藥 → 告訴長者
        if (!inFlow && d.pendingRemoteNotice != null && screen.isHomeLike()) {
            val t = d.pendingRemoteNotice
            Repo.update { it.copy(pendingRemoteNotice = null) }
            go(Screen.RemoteUpdate(t)); return
        }
        val idle = (SystemClock.elapsedRealtime() - lastTouch) / 1000
        val since = (SystemClock.elapsedRealtime() - enteredAt) / 1000
        when (screen) {
            Screen.Home, Screen.Today, Screen.Tomorrow -> if (idle > Rules.IDLE_STANDBY_SEC) go(Screen.Standby)
            is Screen.Reward, Screen.Snoozed, is Screen.Missed, Screen.TimeSaved, Screen.Deleted, is Screen.RemoteUpdate,
            is Screen.LateAsk, is Screen.LateConfirm, is Screen.LateNo ->
                if (since > Rules.AUTO_BACK_SEC && idle > Rules.AUTO_BACK_SEC) go(Screen.Standby)
            else -> {}
        }
    }

    private fun Screen.isHomeLike() = this == Screen.Home || this == Screen.Standby || this == Screen.Today || this == Screen.Tomorrow

    private fun handleEvents() {
        val evs = Repo.consumeEvents()
        if (inFlow) return
        val d = Repo.value
        for (e in evs) when (e) {
            is EngineEvent.Fire -> { go(Screen.Remind(e.slot)); Robot.voice.motion(Motion.REMIND) }
            is EngineEvent.Repeat -> { go(Screen.RemindAgain(e.slot)); Robot.voice.motion(Motion.REMIND) }
            is EngineEvent.Missed -> { go(Screen.Missed(e.slot)) }
            is EngineEvent.Lapsed -> if (screen.isHomeLike()) go(Screen.Home)
            is EngineEvent.Rollover -> if (screen.isHomeLike()) go(Screen.Home)
        }
    }

    fun say(text: String) = Robot.say(text)

    // ---------- 文字 ----------
    fun slotLabel(d: AppData, s: Slot) = "${s.label} ${Clock.fmt(d.schedule.timeOf(s))}"
    fun remindText(d: AppData, s: Slot) = "${ReminderEngine.doseText(d, s, withTime = true)}吃了嗎？請照下面吃。"

    fun homeSay(d: AppData): String {
        if (d.meds.isEmpty()) return "目前沒有記著的藥。請按「設定」新增藥品。"
        if (ReminderEngine.allDone(d)) return "今天的藥都吃完了！明天見。"
        val counted = ReminderEngine.counted(d)
        val overdue = counted.filter { d.slots[it]?.let { st -> st.status == tw.kebbi.medreminder.data.DoseStatus.MISSED && !st.lapsed } == true }
        val next = ReminderEngine.nextSlot(d)
        // 逾時未服：還可以晚吃，提醒長者現在吃
        if (overdue.isNotEmpty()) return "${ReminderEngine.doseText(d, overdue.last())}還沒吃，現在吃也可以。到「今天的藥」點那一行。"
        if (next == null) return "今天沒有要吃的藥了。"
        if (ReminderEngine.takenCount(d) == 0) return "您好！今天要吃 ${counted.size} 次藥。第一次是 ${slotLabel(d, next)}。"
        return "很棒！下一次吃藥是 ${slotLabel(d, next)}。"
    }

    // ---------- 動作 ----------
    /** 離開 App：記一筆事件。鬧鐘還在，時間到會再把 App 叫出來。 */
    fun onExit() {
        CloudSync.logEvent(Repo.value, "app.exit", "離開用藥提醒 App")
        Robot.voice.stop()
    }

    /** 照服員：清空藥單，從掃第一包藥開始重新設定。 */
    fun startStationSetup() {
        Repo.update(reason = "reset") { it.copy(meds = emptyList(), setupDone = true, slots = emptyMap(), day = null, configUpdatedAt = Clock.nowMs()) }
        CloudSync.logEvent(Repo.value, "setup.reset", "照服員清空藥單重新設定")
        go(Screen.Scan(PickMode.HOME))
    }

    fun choosePreset(s: Schedule) = go(Screen.RoutineCards(s))

    fun confirmRoutine(s: Schedule) = saveTime(s)

    fun onScanned(text: String, mode: PickMode = PickMode.STATION) {
        val m = MedDb.parseQr(text)
        // 掃到就直接加進藥單，不用再按確認（長者一手拿藥袋，不方便按）。加錯可以在下一頁拿掉。
        if (m == null) go(Screen.ScanFail(mode)) else addMed(mode, m.id)
    }

    fun confirmSay(id: String): String {
        val m = MedDb.get(id) ?: return ""
        val how = if (m.slots.isEmpty()) "需要時才吃" else "一天 ${m.slots.size} 次，${m.slots.joinToString("、") { it.label }}"
        return "找到了：${m.generic}・${m.name}。$how，每次 ${m.dose}。跟藥袋一樣嗎？"
    }

    fun addMed(mode: PickMode, id: String) {
        val m = MedDb.get(id) ?: return
        Repo.update(reason = "med.add") { d ->
            val nd = d.copy(meds = (d.meds + id).distinct(), configUpdatedAt = Clock.nowMs())
            if (nd.setupDone) ReminderEngine.syncSlots(nd) else nd
        }
        CloudSync.logEvent(Repo.value, "med.add", "新增 ${m.generic}（${m.name}）",
            mapOf("med" to id, "by" to if (mode == PickMode.STATION) "station" else "elder", "needCaregiverConfirm" to (mode == PickMode.HOME)))
        if (mode == PickMode.STATION) { go(Screen.More) }
        else { go(Screen.Added(id)) }
    }

    /** 看不到 QR Code，自動拍下藥袋：照片存在機器上，事件通知照服員到後台確認、補登這包藥。 */
    fun onPhoto(file: java.io.File, mode: PickMode) {
        CloudSync.logEvent(Repo.value, "med.photo", "拍下藥袋（看不到 QR Code），請照服員在後台確認這包藥",
            mapOf("file" to file.name, "path" to file.absolutePath, "by" to if (mode == PickMode.STATION) "station" else "elder", "needCaregiverConfirm" to true))
        if (mode == PickMode.STATION) go(Screen.Photo) else go(Screen.Added(photo = true))
    }

    /** 掃錯了：把剛剛加的藥拿掉。 */
    fun undoAdd(id: String) {
        val m = MedDb.get(id) ?: return
        Repo.update(reason = "med.remove") { d ->
            val nd = d.copy(meds = d.meds - id, configUpdatedAt = Clock.nowMs())
            if (nd.setupDone) ReminderEngine.syncSlots(nd) else nd
        }
        CloudSync.logEvent(Repo.value, "med.remove", "剛剛掃錯，拿掉 ${m.generic}（${m.name}）", mapOf("med" to id, "by" to "elder", "reason" to "scan_mistake"))
        go(Screen.Scan(PickMode.HOME))
    }

    fun finishSetup() {
        Repo.update(reason = "setup.done") { d -> ReminderEngine.syncSlots(d.copy(setupDone = true, day = null).let { ReminderEngine.ensureDay(it).first }) }
        CloudSync.logEvent(Repo.value, "setup.done", "站點設定完成，共 ${Repo.value.meds.size} 種藥", mapOf("meds" to Repo.value.meds))
        go(Screen.Enter)
    }

    fun take(slot: Slot) {
        Repo.update(reason = "dose.taken") { ReminderEngine.take(it, slot) }
        val d = Repo.value
        CloudSync.logEvent(d, "dose.taken", "${slot.label} 吃了", mapOf("slot" to slot.label, "takenAt" to d.slots[slot]?.takenAt,
            "meds" to ReminderEngine.medsIn(d, slot).map { it.id }))
        if (ReminderEngine.allDone(d)) CloudSync.logEvent(d, "day.complete", "今天的藥全部吃完")
        Robot.voice.motion(Motion.CHEER)
        go(Screen.Reward(slot))
    }

    /** 長者點了漏吃的時段。 */
    fun askLate(slot: Slot) {
        val d = Repo.value
        val reason = ReminderEngine.lateTakeBlockReason(d, slot)
        // 逾時未服 → 直接問「確定現在要吃嗎」（少一步）；過了晚吃期限 → 這次不要吃了
        if (reason != null || d.slots[slot]?.lapsed == true) { go(Screen.LateNo(slot, reason ?: "已經過了可以晚吃的時間。這次就不要吃了，下次照常吃就好，不要一次吃兩份喔。")) }
        else { go(Screen.LateConfirm(slot)) }
    }

    fun confirmLate(slot: Slot) {
        go(Screen.LateConfirm(slot))
    }

    fun lateTake(slot: Slot) {
        Repo.update(reason = "dose.late") { ReminderEngine.lateTake(it, slot) }
        val d = Repo.value
        CloudSync.logEvent(d, "dose.late_taken", "${slot.label} 晚吃（逾時後才吃）", mapOf("slot" to slot.label, "takenAt" to d.slots[slot]?.takenAt,
            "meds" to ReminderEngine.medsIn(d, slot).map { it.id }))
        CloudSync.logEvent(d, "caregiver.notify", "${d.elderName} ${ReminderEngine.doseText(d, slot)}晚吃了", mapOf("slot" to slot.label, "level" to "info", "kind" to "late_taken"))
        Robot.voice.motion(Motion.CHEER)
        go(Screen.Reward(slot))
    }

    fun snooze(slot: Slot) {
        Repo.update(reason = "snooze") { ReminderEngine.snooze(it, slot) }
        CloudSync.logEvent(Repo.value, "reminder.snooze", "${slot.label} 按了等一下", mapOf("slot" to slot.label))
        go(Screen.Snoozed)
    }

    fun saveTime(s: Schedule) {
        Repo.update(reason = "schedule") { ReminderEngine.syncSlots(it.copy(schedule = s, configUpdatedAt = Clock.nowMs())) }
        CloudSync.logEvent(Repo.value, "schedule.update", "長者在機器人上改了吃飯、睡覺時間", mapOf("schedule" to scheduleMap(s), "by" to "elder"))
        go(Screen.TimeSaved)
    }

    fun deleteMed(id: String) {
        val m = MedDb.get(id)
        Repo.update(reason = "med.remove") { ReminderEngine.syncSlots(it.copy(meds = it.meds - id, configUpdatedAt = Clock.nowMs())) }
        CloudSync.logEvent(Repo.value, "med.remove", "刪除 ${m?.generic}（${m?.name}），長者表示醫師說可以停",
            mapOf("med" to id, "by" to "elder", "needCaregiverConfirm" to true))
        go(Screen.Deleted)
    }

    // ---------- 測試用（照服員設定頁） ----------
    /** 把 App 的時間快轉到下一次吃藥前 10 秒，方便測試提醒。 */
    fun testJumpToNext(): Boolean {
        val d = Repo.value
        val n = ReminderEngine.nextSlot(d) ?: return false
        val st = d.slots[n] ?: tw.kebbi.medreminder.data.SlotState()
        val target = when (st.status) {
            tw.kebbi.medreminder.data.DoseStatus.SNOOZED -> st.snoozeUntil ?: d.schedule.timeOf(n)
            tw.kebbi.medreminder.data.DoseStatus.REMINDING ->
                d.schedule.timeOf(n) + if (st.secondReminder) Rules.MISS_AFTER_MIN else Rules.REMIND_AGAIN_MIN
            else -> d.schedule.timeOf(n)
        }
        Clock.offsetMs = Clock.todayAt(target) - 10_000L - System.currentTimeMillis()
        go(Screen.Home)
        return true
    }

    fun testResetClock() { Clock.offsetMs = 0L; Repo.update { it } }

    private fun scheduleMap(s: Schedule) = mapOf(
        "breakfast" to Clock.fmt(s.breakfast), "lunch" to Clock.fmt(s.lunch), "dinner" to Clock.fmt(s.dinner), "sleep" to Clock.fmt(s.sleep),
    )
}
