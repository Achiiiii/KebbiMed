package tw.kebbi.medreminder.sync

import android.app.AlarmManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import tw.kebbi.medreminder.BuildConfig
import tw.kebbi.medreminder.api.AdminApi
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.ApiJson
import tw.kebbi.medreminder.api.ErrorCodes
import tw.kebbi.medreminder.api.KebbiApi
import tw.kebbi.medreminder.api.ProblemDto
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.dto.AppEventBatchDto
import tw.kebbi.medreminder.api.dto.AppEventDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestStatus
import tw.kebbi.medreminder.api.dto.DiagnosticsDto
import tw.kebbi.medreminder.api.dto.DoseEventBatchDto
import tw.kebbi.medreminder.api.dto.DoseEventDto
import tw.kebbi.medreminder.api.dto.HeartbeatRequest
import tw.kebbi.medreminder.api.dto.PairingCodeResponse
import tw.kebbi.medreminder.api.dto.RegisterRequest
import tw.kebbi.medreminder.api.dto.ScheduleAckRequest
import tw.kebbi.medreminder.api.dto.TokenRefreshRequest
import tw.kebbi.medreminder.data.AppForeground
import tw.kebbi.medreminder.data.AppScope
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.data.db.OutboxKind
import tw.kebbi.medreminder.robot.Robot
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** 送出變更申請的結果（給畫面用）。 */
sealed class SubmitResult {
    data class Applied(val medicationId: String?) : SubmitResult()
    data class NoChange(val reason: String?) : SubmitResult()
    data object Recorded : SubmitResult()
    /** 422 等：內容不合理，[detail] 念給長者聽。 */
    data class Rejected(val detail: String) : SubmitResult()
    /** 網路斷了，存在 outbox，連上網後補送。 */
    data object Queued : SubmitResult()
}

/** 照片上傳的 outbox 內容。 */
@Serializable
private data class UploadPayload(val requestId: String, val capturedAt: String, val sha256: String)

/**
 * 所有連後端的程式都在這裡：畫面、提醒引擎都不直接呼叫 API。
 *
 * - [syncAll]：登記 → 查綁定 → 心跳 → 需要時拉排程、目錄 → 送 outbox。前景每 1 分鐘、背景每 15 分鐘（D28）。
 * - [flush]：只送 outbox（事件發生的當下呼叫，第 2.6 節）。
 * - [submit]：送出變更申請（D8），等結果給畫面。
 */
object SyncManager {
    private const val TAG = "Sync"
    private lateinit var app: Context
    lateinit var api: KebbiApi
        private set
    /** 假後端（後端完成前用，第 6.6 節）。接上第三方後端後是 null。 */
    var fake: FakeBackend? = null
        private set
    val admin: AdminApi? get() = fake

    private val mutex = Mutex()
    private val flushQueued = AtomicBoolean(false)
    private val results = mutableMapOf<String, SubmitResult>()

    data class Status(
        val online: Boolean = true,
        val lastOkAt: Long = 0L,
        val lastError: String? = null,
        val pairing: PairingCodeResponse? = null,
        val clockSkewMin: Long = 0L,
    )

    val status = MutableStateFlow(Status())
    fun isOnline() = status.value.online

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val (k, f) = ApiFactory.create(app)
        api = k
        fake = f
    }

    /** 事件發生時呼叫：馬上送；送不出去時交給 WorkManager 等有網路再送。 */
    fun requestFlush() {
        if (!::app.isInitialized) return
        if (!flushQueued.compareAndSet(false, true)) return
        AppScope.io.launch {
            delay(300)
            flushQueued.set(false)
            flush()
            if (Repo.value.pendingOutbox > 0) SyncWorker.kick(app)
        }
    }

    suspend fun syncAll(reason: String) = mutex.withLock { doSync(reason) }

    suspend fun flush() = mutex.withLock { doFlush() }

    /**
     * 送出變更申請（D8）。照片在背景上傳，收到 201 就刪除（D17）。
     * 10 秒內沒有結果（網路斷了），回 [SubmitResult.Queued]，申請留在 outbox，連上網後補送。
     */
    suspend fun submit(req: ChangeRequestDto, photo: File? = null): SubmitResult {
        Repo.enqueue(OutboxKind.CHANGE_REQUEST, req.requestId, ApiJson.encodeToString(ChangeRequestDto.serializer(), req))
        if (photo != null && photo.exists()) {
            val payload = UploadPayload(req.requestId, ApiConventions.formatTime(photo.lastModified()), sha256(photo))
            Repo.enqueue(OutboxKind.UPLOAD, req.requestId, ApiJson.encodeToString(UploadPayload.serializer(), payload), photo.absolutePath)
        }
        val r = withTimeoutOrNull(10_000) {
            mutex.withLock {
                flushChangeRequests()
                synchronized(results) { results.remove(req.requestId) }
            }
        }
        requestFlush()
        return r ?: SubmitResult.Queued
    }

    fun newRequestId(): String = UUID.randomUUID().toString()

    // ---------- 內部 ----------

    private sealed class Res<out T> {
        data class Ok<T>(val body: T?, val code: Int) : Res<T>()
        data class Http(val code: Int, val problem: ProblemDto?) : Res<Nothing>()
        data class Net(val error: Throwable) : Res<Nothing>()
    }

    private suspend fun <T> call(retryAuth: Boolean = true, block: suspend () -> Response<T>): Res<T> {
        return try {
            val r = block()
            markOnline(true, null)
            when {
                r.isSuccessful -> Res.Ok(r.body(), r.code())
                r.code() == 304 -> Res.Ok(null, 304)
                else -> {
                    val problem = r.errorBody()?.string()?.let { runCatching { ApiJson.decodeFromString(ProblemDto.serializer(), it) }.getOrNull() }
                    when (r.code()) {
                        401 -> if (retryAuth && refreshToken()) return call(false, block)
                        403 -> onDisabled()
                        409 -> if (problem?.code == ErrorCodes.DEVICE_NOT_ASSIGNED) onUnassigned()
                    }
                    Res.Http(r.code(), problem)
                }
            }
        } catch (e: IOException) {
            markOnline(false, e.message)
            Res.Net(e)
        } catch (e: Exception) {
            Log.w(TAG, "API 呼叫失敗：${e.javaClass.simpleName}")
            Res.Http(-1, null)
        }
    }

    private fun markOnline(ok: Boolean, error: String?) {
        status.value = status.value.copy(
            online = ok,
            lastOkAt = if (ok) Clock.nowMs() else status.value.lastOkAt,
            lastError = if (ok) null else error,
        )
    }

    private fun checkSkew(serverTime: String?) {
        val s = serverTime?.let { runCatching { ApiConventions.parseTime(it) }.getOrNull() } ?: return
        status.value = status.value.copy(clockSkewMin = (Clock.nowMs() - s) / 60_000L)
    }

    private fun onDisabled() {
        if (!Repo.value.device.disabled) Repo.clearElderData(disabled = true)
    }

    private fun onUnassigned() {
        if (Repo.value.device.assigned) Repo.clearElderData(disabled = false)
    }

    private suspend fun ensureRegistered(): Boolean {
        if (Repo.value.device.accessToken != null) return true
        val d = Repo.value.device
        val body = RegisterRequest(
            hardwareId = d.hardwareId, serialNo = null, model = Build.MODEL, osVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE,
        )
        return when (val r = call(retryAuth = false) { api.register(UUID.randomUUID().toString(), body) }) {
            is Res.Ok -> {
                val b = r.body ?: return false
                Repo.updateDevice { it.copy(deviceId = b.deviceId, accessToken = b.accessToken, refreshToken = b.refreshToken) }
                checkSkew(b.serverTime)
                true
            }
            else -> false
        }
    }

    private suspend fun refreshToken(): Boolean {
        val rt = Repo.value.device.refreshToken
        if (rt != null) {
            val r = runCatching { api.refreshToken(TokenRefreshRequest(rt)) }.getOrNull()
            val b = r?.body()
            if (r != null && r.isSuccessful && b != null) {
                Repo.updateDevice { it.copy(accessToken = b.accessToken, refreshToken = b.refreshToken ?: it.refreshToken) }
                return true
            }
        }
        // refresh token 也失效：重新登記（排程和本機狀態保留，繼續提醒）
        Repo.updateDevice { it.copy(accessToken = null, refreshToken = null) }
        return ensureRegistered()
    }

    private suspend fun doSync(reason: String) {
        if (!ensureRegistered()) return
        val me = (call { api.getMe() } as? Res.Ok)?.body ?: run { doFlush(); return }
        checkSkew(me.serverTime)
        Repo.updateDevice { it.copy(assigned = me.assigned, disabled = false, elder = me.elder, rewards = me.rewards ?: it.rewards) }
        if (!me.assigned) {
            if (Repo.value.occurrences.isNotEmpty()) Repo.clearElderData(disabled = false)
            ensurePairingCode()
            return
        }
        if (status.value.pairing != null) status.value = status.value.copy(pairing = null)

        val hb = (call { api.heartbeat(heartbeatBody()) } as? Res.Ok)?.body
        hb?.let { checkSkew(it.serverTime) }
        val d = Repo.value.device
        val latest = hb?.latestScheduleVersion ?: me.latestScheduleVersion
        if (latest != d.scheduleVersion || d.scheduleFetchedDate != Clock.todayKey() || Repo.value.occurrences.isEmpty()) {
            fetchSchedule()
        }
        if (d.catalog == null || d.catalogFetchedDate != Clock.todayKey()) fetchCatalog()
        doFlush()
        Log.i(TAG, "同步完成（$reason）：版本 ${Repo.value.device.scheduleVersion}，待送 ${Repo.value.pendingOutbox}")
    }

    private suspend fun ensurePairingCode() {
        val p = status.value.pairing
        val now = Clock.nowMs()
        if (p != null && ApiConventions.parseTime(p.expiresAt) - now > 60_000L) return
        val r = call { api.createPairingCode(UUID.randomUUID().toString()) }
        if (r is Res.Ok && r.body != null) status.value = status.value.copy(pairing = r.body)
    }

    private suspend fun fetchSchedule() {
        val d = Repo.value.device
        val etag = if (Repo.value.occurrences.isEmpty()) null else d.scheduleEtag
        when (val r = call { api.getSchedule(etag) }) {
            is Res.Ok -> {
                val s = r.body
                if (s == null) {
                    Repo.updateDevice { it.copy(scheduleFetchedDate = Clock.todayKey()) }
                } else {
                    val unknownCatalog = s.medications.any { m -> d.catalog?.items?.none { it.catalogId == m.catalogId } != false }
                    if (unknownCatalog) fetchCatalog()
                    // 本機是空的（重灌、恢復啟用、重新綁定）：先用 C2 拿昨天、今天已經吃過的，再套用排程（第 6.3 節 C2）
                    val restored = if (Repo.value.occurrences.isEmpty()) {
                        listOf(Clock.today().minusDays(1), Clock.today()).flatMap { day ->
                            (call { api.getAdherence(day.toString()) } as? Res.Ok)?.body?.occurrences ?: emptyList()
                        }
                    } else emptyList()
                    Repo.applySchedule(s, restored)
                    Repo.tick()
                    val ack = ScheduleAckRequest(s.version, ApiConventions.formatTime(Clock.nowMs()), "applied")
                    call { api.ackSchedule(UUID.randomUUID().toString(), ack) }
                }
            }
            else -> {}
        }
    }

    private suspend fun fetchCatalog() {
        val d = Repo.value.device
        when (val r = call { api.getCatalog(if (d.catalog == null) null else d.catalog.etag) }) {
            is Res.Ok -> Repo.updateDevice { it.copy(catalog = r.body ?: it.catalog, catalogFetchedDate = Clock.todayKey()) }
            else -> {}
        }
    }

    private suspend fun doFlush() {
        if (Repo.value.device.accessToken == null) return
        if (!Repo.value.device.assigned) return
        if (!flushChangeRequests()) return
        if (!flushDoseEvents()) return
        if (!flushAppEvents()) return
        flushUploads()
    }

    /** 回傳 false 表示網路斷了，後面的先不送。 */
    private suspend fun flushChangeRequests(): Boolean {
        while (true) {
            val item = Repo.outboxNext(OutboxKind.CHANGE_REQUEST, 1).firstOrNull() ?: return true
            val req = runCatching { ApiJson.decodeFromString(ChangeRequestDto.serializer(), item.payload) }.getOrNull()
            if (req == null) { Repo.outboxDelete(listOf(item.id)); continue }
            when (val r = call { api.postChangeRequest(req.requestId, req) }) {
                is Res.Ok -> {
                    val b = r.body
                    val result = when (b?.status) {
                        ChangeRequestStatus.APPLIED -> SubmitResult.Applied(b.medicationId)
                        ChangeRequestStatus.NO_CHANGE -> SubmitResult.NoChange(b.reason)
                        ChangeRequestStatus.RECORDED -> SubmitResult.Recorded
                        null -> SubmitResult.NoChange(null)
                    }
                    synchronized(results) { results[req.requestId] = result }
                    Repo.outboxDelete(listOf(item.id))
                    if (b?.scheduleVersion != null && b.scheduleVersion != Repo.value.device.scheduleVersion) fetchSchedule()
                }
                is Res.Http -> {
                    if (r.code in 400..499 && r.code != 401 && r.code != 429) {
                        synchronized(results) { results[req.requestId] = SubmitResult.Rejected(r.problem?.detail ?: "後台不接受這個修改。") }
                        Repo.outboxDelete(listOf(item.id))
                        Repo.outboxFind(OutboxKind.UPLOAD, req.requestId)?.let { up ->
                            // 申請被拒絕：照片也不用傳了
                            up.filePath?.let { File(it).delete() }
                            Repo.outboxDelete(listOf(up.id))
                        }
                    } else {
                        Repo.outboxBump(listOf(item.id)); return false
                    }
                }
                is Res.Net -> return false
            }
        }
    }

    private suspend fun flushDoseEvents(): Boolean {
        while (true) {
            val items = Repo.outboxNext(OutboxKind.DOSE_EVENT, Rules.MAX_EVENTS_PER_BATCH)
            if (items.isEmpty()) return true
            val events = items.mapNotNull { runCatching { ApiJson.decodeFromString(DoseEventDto.serializer(), it.payload) }.getOrNull() }
            when (val r = call { api.postDoseEvents(UUID.randomUUID().toString(), DoseEventBatchDto(events)) }) {
                is Res.Ok -> {
                    Repo.outboxDelete(items.map { it.id })
                    val v = r.body?.scheduleVersion
                    // 第 2.4 節第 7 項：送事件時順便檢查排程
                    if (v != null && v != Repo.value.device.scheduleVersion) fetchSchedule()
                }
                is Res.Http -> {
                    Repo.outboxBump(items.map { it.id })
                    // 內容有問題（不是暫時的錯誤）又重試很多次的，丟掉，免得卡住後面的事件
                    if (r.code in 400..499 && items.all { it.attempts >= 5 }) Repo.outboxDelete(items.map { it.id }) else return false
                }
                is Res.Net -> return false
            }
        }
    }

    private suspend fun flushAppEvents(): Boolean {
        val items = Repo.outboxNext(OutboxKind.APP_EVENT, 100)
        if (items.isEmpty()) return true
        val events = items.mapNotNull { runCatching { ApiJson.decodeFromString(AppEventDto.serializer(), it.payload) }.getOrNull() }
        return when (call { api.postAppEvents(UUID.randomUUID().toString(), AppEventBatchDto(events)) }) {
            is Res.Ok -> { Repo.outboxDelete(items.map { it.id }); true }
            is Res.Http -> { Repo.outboxBump(items.map { it.id }); true }
            is Res.Net -> false
        }
    }

    private suspend fun flushUploads() {
        while (true) {
            val item = Repo.outboxNext(OutboxKind.UPLOAD, 1).firstOrNull() ?: return
            // 申請還沒送出去的，照片先等（第 6.3 節：先送申請，照片後傳）
            if (Repo.outboxFind(OutboxKind.CHANGE_REQUEST, item.key) != null) return
            val file = item.filePath?.let { File(it) }
            val p = runCatching { ApiJson.decodeFromString(UploadPayload.serializer(), item.payload) }.getOrNull()
            if (file == null || !file.exists() || p == null) { Repo.outboxDelete(listOf(item.id)); continue }
            val text = "text/plain".toMediaType()
            val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            val r = call {
                api.uploadPhoto(
                    item.key, part, "med_bag_photo".toRequestBody(text), p.requestId.toRequestBody(text),
                    p.capturedAt.toRequestBody(text), p.sha256.toRequestBody(text),
                )
            }
            when {
                r is Res.Ok -> { file.delete(); Repo.outboxDelete(listOf(item.id)) } // D17：上傳完就刪
                r is Res.Http && r.code == 413 -> {
                    Repo.outboxDelete(listOf(item.id))
                    Repo.logAppEvent("upload.too_large", mapOf("bytes" to file.length().toString()))
                }
                r is Res.Http -> { Repo.outboxBump(listOf(item.id)); return }
                else -> return
            }
        }
    }

    private fun heartbeatBody(): HeartbeatRequest {
        val am = app.getSystemService(AlarmManager::class.java)
        val bm = app.getSystemService(BatteryManager::class.java)
        return HeartbeatRequest(
            appliedScheduleVersion = Repo.value.device.scheduleVersion,
            deviceTime = ApiConventions.formatTime(Clock.nowMs()),
            foreground = AppForeground.isForeground,
            pendingEvents = Repo.value.pendingOutbox,
            diagnostics = DiagnosticsDto(
                appVersion = BuildConfig.VERSION_NAME,
                voice = Robot.voice.code,
                exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms(),
                notificationPermission = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = bm?.isCharging,
            ),
        )
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 時鐘差超過 2 分鐘要警告（第 2.3 節）。 */
    fun clockSkewWarning(): String? = status.value.clockSkewMin.takeIf { abs(it) >= Rules.CLOCK_SKEW_WARN_MIN }
        ?.let { "凱比的時間和後台差了 $it 分鐘" }
}
