package tw.kebbi.medreminder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.kebbi.medreminder.BuildConfig
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestType
import tw.kebbi.medreminder.api.dto.EventSource
import tw.kebbi.medreminder.api.dto.IdentifiedBy
import tw.kebbi.medreminder.api.dto.MedicationDto
import tw.kebbi.medreminder.api.dto.RemoveReason
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import tw.kebbi.medreminder.data.AppState
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.OccStatus
import tw.kebbi.medreminder.data.Occurrence
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.engine.AlarmScheduler
import tw.kebbi.medreminder.engine.ReminderEngine
import tw.kebbi.medreminder.engine.Speech
import tw.kebbi.medreminder.engine.TakeOutcome
import tw.kebbi.medreminder.engine.UiRules
import tw.kebbi.medreminder.robot.Motion
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.scan.CatalogMatcher
import tw.kebbi.medreminder.scan.MedBagScan
import tw.kebbi.medreminder.scan.ScanModule
import tw.kebbi.medreminder.scan.camera.CaptureSource
import tw.kebbi.medreminder.scan.camera.PendingCapture
import tw.kebbi.medreminder.sync.SubmitResult
import tw.kebbi.medreminder.sync.SyncManager
import java.io.File

/** 結果畫面按「好」之後去哪裡。 */
enum class ResultNext { ADD_MORE, TODAY, SETTINGS, HOME }

/** 所有畫面。 */
sealed class Screen {
    data object Splash : Screen()
    data object Enter : Screen()
    /** 還沒綁定：顯示配對碼（D29 (c)）。 */
    data object Pairing : Screen()
    /** 凱比被後台停用（403）。 */
    data object Disabled : Screen()
    // ② 居家
    data object Home : Screen()
    data object Today : Screen()
    data object Tomorrow : Screen()
    data object Standby : Screen()
    data class Remind(val occId: String) : Screen()
    data class Overdue(val occId: String) : Screen()
    /** 清單上直接按（D15：提醒前 30 分鐘起就可以按）。 */
    data class TakeConfirm(val occId: String) : Screen()
    data class LateConfirm(val occId: String) : Screen()
    data class LateNo(val occId: String, val reason: String) : Screen()
    data class Reward(val occId: String) : Screen()
    data class Snoozed(val minutes: Int) : Screen()
    /** 後台改了藥、或正在提醒的藥被停掉。 */
    data class Notice(val text: String) : Screen()
    // ③ 設定
    data object Settings : Screen()
    data object Routine : Screen()
    data class RoutineCards(val draft: RoutineMinutes) : Screen()
    data class RoutineConfirm(val draft: RoutineMinutes) : Screen()
    data object Scan : Screen()
    /** 倒數拍的照片什麼都沒認出來，再拍一次（D39）。 */
    data object ScanAgain : Screen()
    data object Recognizing : Screen()
    data class PickAmong(val catalogIds: List<String>) : Screen()
    data object PickCat : Screen()
    data class PickMed(val cat: String) : Screen()
    /** 加藥精靈（D38）：一天幾次 → 飯前飯後 → 每次幾顆 → 吃幾天 → 確認。 */
    data class AddMed(val draft: AddDraft) : Screen()
    data class Working(val text: String) : Screen()
    data class Added(val medicationId: String?, val catalogId: String) : Screen()
    data class Result(val text: String, val next: ResultNext) : Screen()
    data object DeleteList : Screen()
    data class DeleteConfirm(val medicationId: String) : Screen()
    data object Caregiver : Screen()
    data object ClearConfirm : Screen()
    data object ExitConfirm : Screen()
    data object DebugBackend : Screen()
}

/** 掃描的結果（只放記憶體，個資規則）。 */
private class ScanContext(val scan: MedBagScan?, val photo: File?)

class AppViewModel : ViewModel() {
    val data get() = Repo.state
    var screen by mutableStateOf<Screen>(Screen.Splash)
        private set
    var debugMessage by mutableStateOf("")
        private set
    private var lastTouch = SystemClock.elapsedRealtime()
    private var enteredAt = SystemClock.elapsedRealtime()
    private var scanCtx: ScanContext? = null

    init {
        // 被外部打開（點橫幅、RoFlow、點圖示）時，有提醒就跳過啟動動畫，直接依狀態顯示（CR-02、D32）
        Repo.tick()
        screen = alertScreen(Repo.value) ?: Screen.Splash
        viewModelScope.launch {
            while (isActive) {
                loop()
                delay(1000)
            }
        }
    }

    fun onResume() = loop()

    fun touch() { lastTouch = SystemClock.elapsedRealtime() }

    fun go(s: Screen) {
        screen = s; enteredAt = SystemClock.elapsedRealtime(); touch()
    }

    // ---------- 畫面由狀態決定（CR-02、CR-23） ----------

    private fun alertScreen(st: AppState): Screen? {
        if (!st.device.assigned || st.device.disabled) return null
        ReminderEngine.activeReminder(st.occurrences)?.let { return Screen.Remind(it.id) }
        ReminderEngine.unseenOverdue(st.occurrences)?.let { return Screen.Overdue(it.id) }
        return null
    }

    /** 這個畫面能不能被提醒打斷。長者正在確認、送出申請時不打斷。 */
    private fun interruptible(s: Screen, st: AppState): Boolean = when (s) {
        is Screen.Remind -> st.occurrences.none { it.id == s.occId && it.status == OccStatus.REMINDING }
        is Screen.Overdue -> st.occurrences.none { it.id == s.occId && it.status == OccStatus.OVERDUE }
        Screen.Splash, Screen.Enter, Screen.Recognizing, Screen.ClearConfirm, Screen.ExitConfirm, Screen.DebugBackend,
        is Screen.TakeConfirm, is Screen.LateConfirm, is Screen.LateNo, is Screen.Working, is Screen.DeleteConfirm -> false
        else -> true
    }

    private fun Screen.isHomeLike() = this == Screen.Home || this == Screen.Standby || this == Screen.Today || this == Screen.Tomorrow

    private fun loop() {
        Repo.tick()
        val st = Repo.value
        val d = st.device
        val s = screen
        val setupScreens = s == Screen.Caregiver || s == Screen.ExitConfirm || s == Screen.DebugBackend || s == Screen.Splash
        if (d.disabled) { if (s != Screen.Disabled && !setupScreens) go(Screen.Disabled); return }
        if (!d.assigned) { if (s != Screen.Pairing && !setupScreens && s != Screen.Enter) go(Screen.Pairing); return }
        if (s == Screen.Pairing || s == Screen.Disabled) { go(Screen.Home); return }

        val alert = alertScreen(st)
        if (alert != null && alert != s && interruptible(s, st)) {
            if (alert is Screen.Remind) Robot.voice.motion(Motion.REMIND)
            go(alert); return
        }
        if (alert == null && (s is Screen.Remind || s is Screen.Overdue) && interruptible(s, st)) { go(Screen.Home); return }

        // 後台改藥、正在提醒的藥被停掉：告訴長者
        if (s.isHomeLike() && (d.stopNotice != null || d.pendingNotice != null)) {
            val text = listOfNotNull(d.stopNotice, d.pendingNotice).joinToString("")
            Repo.consumeNotices()
            go(Screen.Notice(text)); return
        }

        val idle = (SystemClock.elapsedRealtime() - lastTouch) / 1000
        val since = (SystemClock.elapsedRealtime() - enteredAt) / 1000
        when (s) {
            Screen.Home, Screen.Today, Screen.Tomorrow -> if (idle > UiRules.IDLE_STANDBY_SEC) go(Screen.Standby)
            is Screen.Reward, is Screen.Snoozed, is Screen.Notice, is Screen.LateNo, is Screen.Result ->
                if (since > UiRules.AUTO_BACK_SEC && idle > UiRules.AUTO_BACK_SEC) go(Screen.Standby)
            else -> {}
        }
    }

    fun say(text: String) = Robot.say(text)

    // ---------- 查詢 ----------

    fun occ(id: String): Occurrence? = Repo.find(id)
    fun today(st: AppState) = ReminderEngine.forDate(st.occurrences, Clock.todayKey())
    fun tomorrow(st: AppState) = ReminderEngine.forDate(st.occurrences, Clock.tomorrowKey())
    fun catalog(st: AppState): List<CatalogEntryDto> = st.device.catalog?.items ?: emptyList()
    fun categories(st: AppState): List<String> =
        st.device.catalog?.categories?.ifEmpty { null } ?: catalog(st).map { it.category }.distinct()

    fun homeSay(st: AppState): String {
        val day = today(st)
        val now = Clock.nowMs()
        if (st.device.medications.isEmpty() && day.isEmpty()) return "目前沒有記著的藥。請按「設定」新增藥品。"
        if (ReminderEngine.allDone(day)) return "今天的藥都吃完了！明天見。"
        ReminderEngine.overdueTakeable(st.occurrences, now).lastOrNull()?.let {
            return "${it.doseText(withTime = false)}還沒吃，現在吃也可以。到「今天的藥」點那一行。"
        }
        val next = ReminderEngine.nextOpen(st.occurrences, now) ?: return "今天沒有要吃的藥了。"
        val label = if (next.date == Clock.todayKey()) "${next.slotLabel} ${Clock.hm(next.scheduledAt)}" else "明天${next.slotLabel} ${Clock.hm(next.scheduledAt)}"
        if (ReminderEngine.takenCount(day) == 0 && day.isNotEmpty()) return "您好！今天要吃 ${day.size} 次藥。第一次是 $label。"
        return "很棒！下一次吃藥是 $label。"
    }

    // ---------- 吃藥 ----------

    fun take(occId: String) {
        when (Repo.take(occId, EventSource.TOUCH)) {
            TakeOutcome.TAKEN, TakeOutcome.LATE_TAKEN -> { Robot.voice.motion(Motion.CHEER); go(Screen.Reward(occId)) }
            TakeOutcome.TOO_LATE -> { Repo.declineLate(occId); go(Screen.LateNo(occId, Speech.TOO_LATE)) }
            else -> go(Screen.Home)
        }
    }

    fun snooze(occId: String) {
        val o = occ(occId) ?: return
        val now = Clock.nowMs()
        val min = ReminderEngine.snoozeMinutes(o, now)
        if (Repo.snooze(occId, EventSource.TOUCH, now) != null) go(Screen.Snoozed(min)) else go(Screen.Home)
    }

    fun overdueLater(occId: String) {
        Repo.markOverdueSeen(occId)
        go(Screen.Home)
    }

    /** 逾時之後要補吃：還在晚吃期限內 → 確認；過了 → 這次不要吃了。 */
    fun askLate(occId: String) {
        val o = occ(occId) ?: return go(Screen.Home)
        Repo.markOverdueSeen(occId)
        when (ReminderEngine.takeOutcome(o, Clock.nowMs())) {
            TakeOutcome.LATE_TAKEN -> go(Screen.LateConfirm(occId))
            TakeOutcome.TAKEN -> go(Screen.TakeConfirm(occId))
            TakeOutcome.TOO_LATE -> { Repo.declineLate(occId); go(Screen.LateNo(occId, Speech.TOO_LATE)) }
            else -> go(Screen.Home)
        }
    }

    /** 今天的藥清單上點一行（D15：T−30 起可以按，包含提醒中、稍後的）。 */
    fun listTap(o: Occurrence) {
        when (ReminderEngine.takeOutcome(o, Clock.nowMs())) {
            TakeOutcome.TAKEN -> go(Screen.TakeConfirm(o.id))
            TakeOutcome.LATE_TAKEN -> go(Screen.LateConfirm(o.id))
            TakeOutcome.TOO_LATE -> if (o.status == OccStatus.OVERDUE) { Repo.declineLate(o.id); go(Screen.LateNo(o.id, Speech.TOO_LATE)) }
            else -> {}
        }
    }

    // ---------- 加藥（D24、D38：辨識 → 比對目錄 → 對不到才選 → 回答用法 → 確認 → 送出） ----------

    fun startScan() { discardScan(); countdownRetries = 0; go(Screen.Scan) }

    /** 倒數拍的照片什麼都沒認出來時，已經重拍幾次（D39）。 */
    private var countdownRetries = 0

    fun onCaptured(context: Context, capture: PendingCapture) {
        go(Screen.Recognizing)
        viewModelScope.launch {
            val scan = runCatching { ScanModule.recognizer(context).recognize(capture) }.getOrNull()
            // 倒數拍的可能還沒拿好：什麼藥名都沒認出來就刪掉照片，再拍一次（只重拍一次，之後照常讓長者選）
            if (capture.source == CaptureSource.COUNTDOWN && scan?.drugName == null && countdownRetries < 1) {
                countdownRetries++
                withContext(Dispatchers.IO) { capture.file.delete() }
                go(Screen.ScanAgain); return@launch
            }
            scanCtx = ScanContext(scan, capture.file)
            val matches = CatalogMatcher.match(scan?.drugName, catalog(Repo.value))
            when {
                matches.size == 1 -> startAdd(matches.first()) // 對到藥名：不用選種類，直接問用法
                matches.size > 1 -> go(Screen.PickAmong(matches.map { it.catalogId }))
                else -> go(Screen.PickCat)
            }
        }
    }

    /** debug 版：用推到 App 資料夾的圖片代替拍照（`adb push x.jpg /sdcard/Android/data/tw.kebbi.medreminder/files/test/`）。 */
    fun onTestImage(context: Context) {
        viewModelScope.launch {
            val src = withContext(Dispatchers.IO) {
                context.getExternalFilesDir("test")?.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            }
            if (src == null) { debugMessage = "沒有測試圖片"; return@launch }
            val dst = ScanModule.imageStore(context).newImageFile()
            withContext(Dispatchers.IO) {
                // 測試圖片常是 BMP、PNG：存成 JPEG，跟相機拍的一樣（後端會檢查格式，BMP 會被拒收）
                val bmp = BitmapFactory.decodeFile(src.path)
                if (bmp == null) src.copyTo(dst, overwrite = true)
                else { dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bmp.recycle() }
            }
            onCaptured(context, PendingCapture(dst, CaptureSource.TEST_IMAGE, Clock.nowMs(), emptyList()))
        }
    }

    /** 改用選的（沒有拍照）。 */
    fun pickWithoutPhoto() { discardScan(); go(Screen.PickCat) }

    /** 不加了：拍的照片刪掉（D17，個資不留在凱比上）。 */
    fun cancelAdd() { discardScan(); go(Screen.Settings) }

    private fun discardScan() {
        scanCtx?.photo?.let { f -> viewModelScope.launch(Dispatchers.IO) { f.delete() } }
        scanCtx = null
    }

    /** 選好藥（掃描對到、或從目錄選）之後，開始問用法（D38）。 */
    fun startAdd(entry: CatalogEntryDto) {
        if (Repo.value.device.medications.any { it.catalogId == entry.catalogId }) {
            discardScan()
            go(Screen.Result("${entry.displayName}已經在藥單裡了，不用再加。", ResultNext.ADD_MORE)); return
        }
        val scan = scanCtx?.scan
        val matchedByName = scan != null && CatalogMatcher.match(scan.drugName, catalog(Repo.value)).any { it.catalogId == entry.catalogId }
        go(Screen.AddMed(AddDraft.start(
            entry,
            identifiedBy = if (matchedByName) scan!!.identifiedBy else IdentifiedBy.USER_SELECTED,
            // D26：名稱對到、而且有 QR 格式 A 時，才用 QR 的劑量、次數、時段當建議
            qr = if (matchedByName) scan!!.prescription else null,
            qrQuantity = if (matchedByName) scan!!.qr.b?.quantity else null,
        )))
    }

    fun addAnswer(d: AddDraft) = go(Screen.AddMed(d))

    /** 精靈的「上一步」；第一步再往前：掃描對到的回去選種類，自己選的回去選藥。 */
    fun addBack(d: AddDraft) {
        d.back()?.let { go(Screen.AddMed(it)); return }
        go(if (d.fromScan) Screen.PickCat else Screen.PickMed(d.entry.category))
    }

    fun submitAdd(d: AddDraft) {
        val entry = d.entry
        val ctx = scanCtx
        scanCtx = null
        val req = ChangeRequestDto(
            requestId = SyncManager.newRequestId(), type = ChangeRequestType.ADD_MEDICATION, createdAt = nowText(),
            catalogId = entry.catalogId,
            identifiedBy = d.identifiedBy,
            prescription = d.prescription(),
        )
        submit("我幫您加上${entry.displayName}，請等一下…", req, ctx?.photo) { r ->
            when (r) {
                is SubmitResult.Applied -> Screen.Added(r.medicationId, entry.catalogId)
                is SubmitResult.NoChange -> Screen.Result("這個藥已經在藥單裡了。", ResultNext.ADD_MORE)
                SubmitResult.Queued -> Screen.Result("網路斷了，連上後會幫您加上${entry.displayName}。", ResultNext.ADD_MORE)
                is SubmitResult.Rejected -> Screen.Result(r.detail, ResultNext.ADD_MORE)
                SubmitResult.Recorded -> Screen.Result("記好了。", ResultNext.ADD_MORE)
            }
        }
    }

    /** 目錄裡也找不到（D26 (ii)）：只上傳照片當紀錄，不改排程。 */
    fun submitUnlisted() {
        val ctx = scanCtx
        if (ctx?.photo == null) {
            go(Screen.Result("目錄裡沒有的藥，請拍一下藥袋，我把照片傳給照服員。", ResultNext.ADD_MORE)); return
        }
        scanCtx = null
        val req = ChangeRequestDto(SyncManager.newRequestId(), ChangeRequestType.UNLISTED_MEDICATION, nowText())
        submit("我把照片傳到後台，請等一下…", req, ctx.photo) { r ->
            when (r) {
                SubmitResult.Queued -> Screen.Result("網路斷了，連上後會把照片傳到後台。這個藥目前不會提醒您。", ResultNext.ADD_MORE)
                is SubmitResult.Rejected -> Screen.Result(r.detail, ResultNext.ADD_MORE)
                else -> Screen.Result("這個藥我還不認識，照片已經傳到後台。這個藥目前不會提醒您。", ResultNext.ADD_MORE)
            }
        }
    }

    fun addedText(st: AppState, s: Screen.Added): String {
        val m = st.device.medications.firstOrNull { it.medicationId == s.medicationId }
            ?: st.device.medications.firstOrNull { it.catalogId == s.catalogId }
        val name = m?.displayName ?: catalog(st).firstOrNull { it.catalogId == s.catalogId }?.displayName ?: "這個藥"
        val whenText = m?.let { medWhen(it) }
        return if (whenText == null) "已經幫您加上$name。還有其他藥嗎？"
        else if (m.slots.isEmpty()) "已經幫您加上$name，這個藥是需要時才吃，不會定時提醒。還有其他藥嗎？"
        else "已經幫您加上$name，以後${whenText}提醒您" + (endText(m)?.let { "，$it" } ?: "") + "。還有其他藥嗎？"
    }

    /** 「吃到 11 月 29 日」；一直吃的藥是 null。 */
    fun endText(m: MedicationDto): String? = m.endDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?.let { "吃到 ${it.monthValue} 月 ${it.dayOfMonth} 日" }

    fun todayDate(): java.time.LocalDate = Clock.today()

    /** 「每天早餐後、晚餐後」「每週一起床」。 */
    fun medWhen(m: MedicationDto): String {
        val parts = m.slots.filter { it != tw.kebbi.medreminder.api.Slot.CUSTOM }.map { it.label } + m.customTimes
        return (m.weeklyOn?.let { "每週${it.label.removePrefix("星期")}" } ?: "每天") + parts.joinToString("、")
    }

    fun undoAdd(medicationId: String?) {
        medicationId ?: return go(Screen.Home)
        submitRemove(medicationId, RemoveReason.ADDED_BY_MISTAKE, ResultNext.ADD_MORE)
    }

    // ---------- 停藥、改時間、清空（D8：都改成送申請） ----------

    fun submitRemove(medicationId: String, reason: RemoveReason, next: ResultNext = ResultNext.TODAY) {
        val req = ChangeRequestDto(SyncManager.newRequestId(), ChangeRequestType.REMOVE_MEDICATION, nowText(), medicationId = medicationId, reason = reason)
        submit("好，我幫您拿掉，請等一下…", req, null) { r ->
            when (r) {
                SubmitResult.Queued -> Screen.Result("網路斷了，連上後會幫您拿掉。", next)
                is SubmitResult.Rejected -> Screen.Result(r.detail, next)
                else -> Screen.Result("好，已經拿掉了。", next)
            }
        }
    }

    fun confirmRoutine(draft: RoutineMinutes) {
        draft.validate()?.let { go(Screen.Result(it, ResultNext.SETTINGS)); return } // CR-20：送出前先檢查
        val req = ChangeRequestDto(SyncManager.newRequestId(), ChangeRequestType.CHANGE_ROUTINE, nowText(), routine = draft.toDto())
        submit("好，我幫您改時間，請等一下…", req, null) { r ->
            when (r) {
                is SubmitResult.Applied -> Screen.Result("時間改好了！我會照新的時間提醒您。", ResultNext.TODAY)
                is SubmitResult.NoChange -> Screen.Result("時間跟原本一樣，不用改。", ResultNext.SETTINGS)
                SubmitResult.Queued -> Screen.Result("網路斷了，連上後會幫您改。在那之前，還是照原本的時間提醒。", ResultNext.HOME)
                is SubmitResult.Rejected -> Screen.Result(r.detail, ResultNext.SETTINGS)
                SubmitResult.Recorded -> Screen.Result("記好了。", ResultNext.HOME)
            }
        }
    }

    /** 照服員頁的「清空藥單」（D27 (a)）：送申請；今天已吃的紀錄不受影響。 */
    fun clearMedications() {
        val req = ChangeRequestDto(SyncManager.newRequestId(), ChangeRequestType.CLEAR_MEDICATIONS, nowText())
        submit("好，我幫您清空藥單，請等一下…", req, null) { r ->
            when (r) {
                is SubmitResult.Applied -> Screen.Result("藥單清空了。", ResultNext.SETTINGS)
                is SubmitResult.NoChange -> Screen.Result("藥單本來就是空的。", ResultNext.SETTINGS)
                SubmitResult.Queued -> Screen.Result("網路斷了，連上後會幫您清空。", ResultNext.HOME)
                is SubmitResult.Rejected -> Screen.Result(r.detail, ResultNext.SETTINGS)
                SubmitResult.Recorded -> Screen.Result("記好了。", ResultNext.SETTINGS)
            }
        }
    }

    private fun submit(working: String, req: ChangeRequestDto, photo: File?, next: (SubmitResult) -> Screen) {
        go(Screen.Working(working))
        viewModelScope.launch {
            val r = SyncManager.submit(req, photo)
            go(next(r))
        }
    }

    fun resultNext(n: ResultNext) = when (n) {
        ResultNext.ADD_MORE -> go(Screen.Today)
        ResultNext.TODAY -> go(Screen.Today)
        ResultNext.SETTINGS -> go(Screen.Settings)
        ResultNext.HOME -> go(Screen.Home)
    }

    private fun nowText() = ApiConventions.formatTime(Clock.nowMs())

    // ---------- 照服員頁 ----------

    /** 「1.0.0（3）」：照服員回報問題時看得出裝的是哪一版。 */
    val appVersion get() = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"

    /** 離開 App（D3：只在照服員設定頁）。鬧鐘還在，時間到會發橫幅、說話。 */
    fun onExit() {
        Repo.logAppEvent("app.exit")
        Robot.voice.lockSystemUi(false)
        Robot.hush()
    }

    // ---------- 測試用（只有 debug 版看得到，D27） ----------

    val isDebug get() = BuildConfig.DEBUG
    val hasFakeBackend get() = SyncManager.fake != null

    /** 把 App 的時間快轉到下一個轉換點前 10 秒。 */
    fun testJumpToNext(): Boolean {
        val now = Clock.nowMs()
        val next = ReminderEngine.nextWakeMs(Repo.value.occurrences, now) ?: return false
        Clock.offsetMs += next - now - 10_000L
        AlarmScheduler.invalidate()
        Repo.tick()
        go(Screen.Home)
        return true
    }

    fun testResetClock() { Clock.offsetMs = 0L; AlarmScheduler.invalidate(); Repo.tick() }

    fun debug(action: suspend () -> String) {
        viewModelScope.launch {
            debugMessage = "處理中…"
            debugMessage = runCatching { action() }.getOrElse { "失敗：${it.message}" }
            SyncManager.syncAll("debug")
        }
    }

    fun debugSummary(): String = SyncManager.fake?.debugSummary() ?: "接的是真的後端"
}
