package tw.kebbi.medreminder.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.ApiJson
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.AdherenceItemDto
import tw.kebbi.medreminder.api.dto.AppEventDto
import tw.kebbi.medreminder.api.dto.ChangeSource
import tw.kebbi.medreminder.api.dto.DoseEventDto
import tw.kebbi.medreminder.api.dto.DoseEventType
import tw.kebbi.medreminder.api.dto.EventSource
import tw.kebbi.medreminder.api.dto.ReminderSettingsDto
import tw.kebbi.medreminder.api.dto.ScheduleDto
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import tw.kebbi.medreminder.data.db.AppDatabase
import tw.kebbi.medreminder.data.db.OccurrenceEntity
import tw.kebbi.medreminder.data.db.OutboxEntity
import tw.kebbi.medreminder.data.db.OutboxKind
import tw.kebbi.medreminder.engine.AlarmScheduler
import tw.kebbi.medreminder.engine.EngineEvent
import tw.kebbi.medreminder.engine.Notifier
import tw.kebbi.medreminder.engine.ReminderEngine
import tw.kebbi.medreminder.engine.Speech
import tw.kebbi.medreminder.engine.TakeOutcome
import tw.kebbi.medreminder.robot.Robot
import tw.kebbi.medreminder.sync.SyncManager
import java.util.UUID

/** App 的畫面是否在最上層（KebbiApp 用 ProcessLifecycleOwner 設定）。 */
object AppForeground {
    val state = MutableStateFlow(false)
    val isForeground: Boolean get() = state.value
}

/** App 層級的協程（同步、上傳）。 */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * 唯一的資料來源。畫面、鬧鐘、通知按鈕、同步都透過這裡讀寫。
 * 所有修改都在 @Synchronized 方法裡，寫進資料庫後才更新 [state]。
 */
object Repo {
    private lateinit var app: Context
    private lateinit var db: AppDatabase
    private lateinit var store: DeviceStore
    @Volatile private var initialized = false

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> get() = _state
    val value: AppState get() = _state.value

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        db = AppDatabase.get(app)
        store = DeviceStore(app)
        val device = store.load()
        _state.value = AppState(
            device = device,
            occurrences = db.occurrences().all().map { it.toModel(device.reminderSettings ?: ReminderSettingsDto.DEFAULT) },
            pendingOutbox = db.outbox().count(),
        )
        initialized = true
    }

    // ---------- 裝置資料 ----------

    @Synchronized
    fun updateDevice(f: (DeviceData) -> DeviceData): DeviceData {
        val d = f(value.device)
        if (d != value.device) {
            store.save(d)
            _state.value = value.copy(device = d)
        }
        return d
    }

    fun find(occId: String): Occurrence? = value.occurrences.firstOrNull { it.id == occId }

    // ---------- 提醒引擎 ----------

    /** 依現在時間跑一次引擎；鬧鐘響、畫面每秒、開機都呼叫這裡。 */
    @Synchronized
    fun tick(now: Long = Clock.nowMs()): List<EngineEvent> {
        if (!value.device.assigned || value.device.disabled) return emptyList()
        val (next, events) = ReminderEngine.tick(value.occurrences, now)
        if (events.isNotEmpty()) {
            val changedIds = events.map { it.occ.id }.toSet()
            saveOccurrences(next.filter { it.id in changedIds })
            _state.value = value.copy(occurrences = next)
            events.forEach { onEngineEvent(it, now) }
            SyncManager.requestFlush()
        }
        AlarmScheduler.schedule(app, ReminderEngine.nextWakeMs(value.occurrences, now))
        return events
    }

    private fun onEngineEvent(e: EngineEvent, now: Long) {
        val type = when (e) {
            is EngineEvent.Fire -> DoseEventType.REMINDED
            is EngineEvent.RemindAgain -> DoseEventType.REMINDED_AGAIN
            is EngineEvent.Overdue -> DoseEventType.OVERDUE
            is EngineEvent.Missed -> DoseEventType.MISSED
            is EngineEvent.NotReminded -> DoseEventType.NOT_REMINDED_DEVICE_OFF
        }
        val foreground = AppForeground.isForeground
        enqueueDoseEvent(e.occ.id, type, now, EventSource.SYSTEM, mapOf("foreground" to foreground.toString()))
        Notifier.onEngineEvent(app, e, foreground)
        // CR-40：App 被蓋住時，畫面不會說話，改由這裡直接說（在前景時由畫面說，避免講兩次）
        if (!foreground) when (e) {
            is EngineEvent.Fire -> Robot.say(Speech.remind(e.occ, othersCount(e.occ), onScreen = false))
            is EngineEvent.RemindAgain -> Robot.say(Speech.remindAgain(e.occ))
            is EngineEvent.Overdue -> Robot.say(Speech.overdue(e.occ, SyncManager.isOnline()))
            else -> {}
        }
    }

    /** 同時在提醒中的其他包數。 */
    fun othersCount(o: Occurrence): Int = value.occurrences.count { it.id != o.id && it.status == OccStatus.REMINDING }

    /** 按「吃了」。以按下時的狀態判斷（第 2.6 節的表）。 */
    @Synchronized
    fun take(occId: String, source: EventSource, now: Long = Clock.nowMs()): TakeOutcome {
        val o = find(occId)
        val outcome = ReminderEngine.takeOutcome(o, now)
        if (o != null && (outcome == TakeOutcome.TAKEN || outcome == TakeOutcome.LATE_TAKEN)) {
            val n = ReminderEngine.take(o, now)
            replace(n)
            enqueueDoseEvent(o.id, if (n.late) DoseEventType.LATE_TAKEN else DoseEventType.TAKEN, now, source)
            Notifier.cancel(app, o.id)
            AlarmScheduler.schedule(app, ReminderEngine.nextWakeMs(value.occurrences, now))
            SyncManager.requestFlush()
        }
        return outcome
    }

    /** 按「等一下」。回傳稍後到期時間；不能按時回傳 null。 */
    @Synchronized
    fun snooze(occId: String, source: EventSource, now: Long = Clock.nowMs()): Long? {
        val o = find(occId) ?: return null
        if (!ReminderEngine.canSnooze(o, now)) return null
        val n = ReminderEngine.snooze(o, now)
        replace(n)
        enqueueDoseEvent(o.id, DoseEventType.SNOOZED, now, source,
            mapOf("snoozeUntil" to ApiConventions.formatTime(n.snoozeUntil!!)))
        Notifier.cancel(app, o.id)
        AlarmScheduler.schedule(app, ReminderEngine.nextWakeMs(value.occurrences, now))
        SyncManager.requestFlush()
        return n.snoozeUntil
    }

    /** 長者看過「逾時未服」畫面，按了「等一下再說」。 */
    @Synchronized
    fun markOverdueSeen(occId: String) {
        val o = find(occId) ?: return
        if (!o.overdueSeen) replace(o.copy(overdueSeen = true))
    }

    /** 告訴長者這次不要補吃了（第 6.3 節 C1 `declined_late`）。 */
    @Synchronized
    fun declineLate(occId: String, now: Long = Clock.nowMs()) {
        enqueueDoseEvent(occId, DoseEventType.DECLINED_LATE, now, EventSource.TOUCH)
        SyncManager.requestFlush()
    }

    private fun replace(o: Occurrence) {
        saveOccurrences(listOf(o))
        _state.value = value.copy(occurrences = value.occurrences.map { if (it.id == o.id) o else it })
    }

    private fun saveOccurrences(list: List<Occurrence>) {
        if (list.isNotEmpty()) db.occurrences().upsert(list.map { it.toEntity() })
    }

    // ---------- 排程（第 2.3 節：後端是唯一來源） ----------

    class MergeResult(val removedInProgress: List<Occurrence>, val versionChanged: Boolean)

    /**
     * 套用後端的排程，以 occurrenceId 合併：
     * - 已經在凱比上的：保留本機狀態（已提醒、已吃），更新時間和藥。
     * - 新的：加進來（已經過了晚吃期限的不加，凱比沒有參與，不判定）。
     * - 不見了的：還沒開始的直接刪；正在提醒中的刪掉並告訴長者「這個藥已經停了，不用吃」；已經結束的留著當今天的紀錄。
     */
    @Synchronized
    fun applySchedule(s: ScheduleDto, restored: List<AdherenceItemDto> = emptyList(), now: Long = Clock.nowMs()): MergeResult {
        // 後台的提醒參數：新版本一到就生效，正在提醒中的那包也用新的
        val settings = s.reminderSettings?.normalized() ?: ReminderSettingsDto.DEFAULT
        val medsById = s.medications.associateBy { it.medicationId }
        // 本機是空的（重灌、恢復啟用、重新綁定）：用 C2 的結果還原已經吃過的，免得又提醒一次
        val takenById = restored.filter { it.status == "taken" || it.status == "taken_late" }.associateBy { it.occurrenceId }
        val local = value.occurrences.associateBy { it.id }
        val serverIds = s.occurrences.map { it.occurrenceId }.toSet()
        val keep = mutableListOf<Occurrence>()
        for (o in s.occurrences) {
            val items = o.items.map { i ->
                val m = medsById[i.medicationId]
                OccItem(i.medicationId, m?.displayName ?: i.displayName ?: "藥", i.doseText, m?.genericName)
            }
            val at = ApiConventions.parseTime(o.scheduledAt)
            val deadline = ApiConventions.parseTime(o.lateDeadline)
            val l = local[o.occurrenceId]
            if (l != null) {
                keep += if (l.status.isOpen) l.copy(slot = o.slot, slotLabel = o.slotLabel, scheduledAt = at, lateDeadline = deadline, items = items) else l
            } else if (deadline > now || takenById.containsKey(o.occurrenceId)) {
                val taken = takenById[o.occurrenceId]
                keep += Occurrence(o.occurrenceId, o.date, o.slot, o.slotLabel, at, deadline, items).let {
                    if (taken == null) it
                    else it.copy(status = OccStatus.TAKEN, takenAt = taken.takenAt?.let(ApiConventions::parseTime),
                        late = taken.late || taken.status == "taken_late")
                }
            }
        }
        val windowStart = Clock.today(now).minusDays(1).toString()
        val removedInProgress = mutableListOf<Occurrence>()
        val delete = mutableListOf<String>()
        for (l in value.occurrences.filter { it.id !in serverIds }) {
            when {
                l.date < windowStart -> delete += l.id
                l.status == OccStatus.REMINDING || l.status == OccStatus.SNOOZED || l.status == OccStatus.OVERDUE -> {
                    removedInProgress += l; delete += l.id
                }
                l.status.isOpen -> delete += l.id
                else -> keep += l
            }
        }
        if (delete.isNotEmpty()) db.occurrences().delete(delete)
        val kept = keep.map { it.copy(settings = settings) }
        saveOccurrences(kept)
        delete.forEach { Notifier.cancel(app, it) }

        val versionChanged = value.device.scheduleVersion != s.version
        val notice = if (versionChanged && value.device.scheduleVersion != null && s.changeSource == ChangeSource.CAREGIVER)
            s.changeSummary ?: "站點照服員幫您更新了藥。" else value.device.pendingNotice
        val stop = if (removedInProgress.isNotEmpty()) Speech.stopped(removedInProgress) else value.device.stopNotice
        val d = value.device.copy(
            medications = s.medications,
            scheduleVersion = s.version,
            scheduleEtag = s.etag,
            scheduleFetchedDate = Clock.todayKey(now),
            pendingNotice = notice,
            stopNotice = stop,
            reminderSettings = settings,
        ).withRoutine(RoutineMinutes.from(s.routine))
        store.save(d)
        _state.value = value.copy(device = d, occurrences = kept.sortedBy { it.scheduledAt })
        AlarmScheduler.schedule(app, ReminderEngine.nextWakeMs(value.occurrences, now))
        return MergeResult(removedInProgress, versionChanged)
    }

    /** 解除綁定、凱比被停用：清掉本機的排程、藥單和待送的資料。 */
    @Synchronized
    fun clearElderData(disabled: Boolean) {
        value.occurrences.forEach { Notifier.cancel(app, it.id) }
        db.occurrences().clear()
        db.outbox().clear()
        val d = value.device.copy(
            assigned = false, disabled = disabled, elder = null, medications = emptyList(),
            scheduleVersion = null, scheduleEtag = null, scheduleFetchedDate = null,
            rewards = null, pendingNotice = null, stopNotice = null,
        )
        store.save(d)
        _state.value = AppState(device = d, occurrences = emptyList(), pendingOutbox = 0)
        AlarmScheduler.schedule(app, null)
    }

    @Synchronized
    fun consumeNotices() = updateDevice { it.copy(pendingNotice = null, stopNotice = null) }

    // ---------- outbox ----------

    @Synchronized
    fun enqueue(kind: String, key: String, payload: String, filePath: String? = null) {
        if (db.outbox().find(kind, key) != null) return
        db.outbox().insert(OutboxEntity(kind = kind, key = key, payload = payload, createdAt = Clock.nowMs(), filePath = filePath))
        _state.value = value.copy(pendingOutbox = db.outbox().count())
    }

    fun outboxNext(kind: String, limit: Int): List<OutboxEntity> = db.outbox().next(kind, limit)

    fun outboxFind(kind: String, key: String): OutboxEntity? = db.outbox().find(kind, key)

    @Synchronized
    fun outboxDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.outbox().delete(ids)
        _state.value = value.copy(pendingOutbox = db.outbox().count())
    }

    fun outboxBump(ids: List<Long>) { if (ids.isNotEmpty()) db.outbox().bumpAttempts(ids) }

    fun enqueueDoseEvent(occId: String, type: DoseEventType, at: Long, source: EventSource, detail: Map<String, String> = emptyMap()) {
        val e = DoseEventDto(
            eventId = UUID.randomUUID().toString(), occurrenceId = occId, type = type,
            occurredAt = ApiConventions.formatTime(at), source = source,
            detail = if (detail.isEmpty()) null else JsonObject(detail.mapValues { JsonPrimitive(it.value) }),
        )
        enqueue(OutboxKind.DOSE_EVENT, e.eventId, ApiJson.encodeToString(DoseEventDto.serializer(), e))
    }

    /** 非服藥類的操作紀錄（C3），例如 `app.start`、`notification.opened`。 */
    fun logAppEvent(type: String, detail: Map<String, String> = emptyMap()) {
        if (!initialized) return
        val e = AppEventDto(
            eventId = UUID.randomUUID().toString(), type = type, occurredAt = ApiConventions.formatTime(Clock.nowMs()),
            detail = if (detail.isEmpty()) null else JsonObject(detail.mapValues { JsonPrimitive(it.value) }),
        )
        enqueue(OutboxKind.APP_EVENT, e.eventId, ApiJson.encodeToString(AppEventDto.serializer(), e))
        SyncManager.requestFlush()
    }

    // ---------- 轉換 ----------

    private val itemsSer = ListSerializer(OccItem.serializer())

    private fun Occurrence.toEntity() = OccurrenceEntity(
        id = id, date = date, slot = slot.name, slotLabel = slotLabel, scheduledAt = scheduledAt, lateDeadline = lateDeadline,
        itemsJson = ApiJson.encodeToString(itemsSer, items), status = status.name, remindedAt = remindedAt,
        secondReminder = secondReminder, snoozeUntil = snoozeUntil, takenAt = takenAt, late = late, overdueSeen = overdueSeen,
        snoozeCount = snoozeCount,
    )

    private fun OccurrenceEntity.toModel(settings: ReminderSettingsDto) = Occurrence(
        id = id, date = date, slot = runCatching { Slot.valueOf(slot) }.getOrDefault(Slot.CUSTOM), slotLabel = slotLabel,
        scheduledAt = scheduledAt, lateDeadline = lateDeadline,
        items = runCatching { ApiJson.decodeFromString(itemsSer, itemsJson) }.getOrDefault(emptyList()),
        status = runCatching { OccStatus.valueOf(status) }.getOrDefault(OccStatus.PENDING), remindedAt = remindedAt,
        secondReminder = secondReminder, snoozeUntil = snoozeUntil, takenAt = takenAt, late = late, overdueSeen = overdueSeen,
        snoozeCount = snoozeCount, settings = settings,
    )
}
