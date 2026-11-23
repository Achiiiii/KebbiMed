package tw.kebbi.medreminder.sync

import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import tw.kebbi.medreminder.api.AdminApi
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.ApiJson
import tw.kebbi.medreminder.api.ErrorCodes
import tw.kebbi.medreminder.api.KebbiApi
import tw.kebbi.medreminder.api.ProblemDto
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.AckResponse
import tw.kebbi.medreminder.api.dto.AdherenceDto
import tw.kebbi.medreminder.api.dto.AdherenceItemDto
import tw.kebbi.medreminder.api.dto.AppEventBatchDto
import tw.kebbi.medreminder.api.dto.AppEventResultDto
import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import tw.kebbi.medreminder.api.dto.CatalogDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestResultDto
import tw.kebbi.medreminder.api.dto.ChangeRequestStatus
import tw.kebbi.medreminder.api.dto.ChangeSource
import tw.kebbi.medreminder.api.dto.ClaimDeviceRequest
import tw.kebbi.medreminder.api.dto.ClaimDeviceResponse
import tw.kebbi.medreminder.api.dto.DeviceMeResponse
import tw.kebbi.medreminder.api.dto.DoseDto
import tw.kebbi.medreminder.api.dto.DoseEventBatchDto
import tw.kebbi.medreminder.api.dto.DoseEventDto
import tw.kebbi.medreminder.api.dto.DoseEventResultDto
import tw.kebbi.medreminder.api.dto.DoseEventType
import tw.kebbi.medreminder.api.dto.ElderCreateRequest
import tw.kebbi.medreminder.api.dto.ElderDto
import tw.kebbi.medreminder.api.dto.ElderSummaryDto
import tw.kebbi.medreminder.api.dto.HeartbeatRequest
import tw.kebbi.medreminder.api.dto.HeartbeatResponse
import tw.kebbi.medreminder.api.dto.MedicationDto
import tw.kebbi.medreminder.api.dto.MedicationOrderDto
import tw.kebbi.medreminder.api.dto.OccurrenceDto
import tw.kebbi.medreminder.api.dto.PairingCodeResponse
import tw.kebbi.medreminder.api.dto.PublishRequest
import tw.kebbi.medreminder.api.dto.PublishResponse
import tw.kebbi.medreminder.api.dto.RegisterRequest
import tw.kebbi.medreminder.api.dto.RegisterResponse
import tw.kebbi.medreminder.api.dto.RejectedEventDto
import tw.kebbi.medreminder.api.dto.RewardsDto
import tw.kebbi.medreminder.api.dto.RoutineDto
import tw.kebbi.medreminder.api.dto.ScheduleAckRequest
import tw.kebbi.medreminder.api.dto.ScheduleDto
import tw.kebbi.medreminder.api.dto.TokenRefreshRequest
import tw.kebbi.medreminder.api.dto.TokenRefreshResponse
import tw.kebbi.medreminder.api.dto.UploadResultDto
import tw.kebbi.medreminder.api.reference.ApplyResult
import tw.kebbi.medreminder.api.reference.ChangeRequestRules
import tw.kebbi.medreminder.api.reference.DoseStatusRules
import tw.kebbi.medreminder.api.reference.ElderPlan
import tw.kebbi.medreminder.api.reference.EventInput
import tw.kebbi.medreminder.api.reference.OccurrenceExpander
import tw.kebbi.medreminder.api.reference.OfficialStatus
import tw.kebbi.medreminder.api.reference.OrderInput
import tw.kebbi.medreminder.api.reference.PublishRules
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/**
 * 假後端：第三方後端完成前，App 接這個。實作和真的後端同一個 [KebbiApi]，
 * App 的其他程式不知道接的是真是假。另外實作 [AdminApi]，debug 版的照服員頁用它模擬照服員的操作。
 *
 * - 計算邏輯都用 `:api` 的 reference（展開 occurrence、申請生效規則、狀態判定），也就是後端行為的簡化版。
 * - 資料存在 [file]（App 私有目錄的 JSON）。只有一台凱比。
 * - 可以模擬斷網、401、5xx（[offline]、[failNext]）。
 */
class FakeBackend(
    private val file: File?,
    private val clock: () -> Long = { tw.kebbi.medreminder.data.Clock.nowMs() },
) : KebbiApi, AdminApi {

    @Serializable
    data class FakeDevice(
        val deviceId: String,
        val hardwareId: String,
        var accessToken: String,
        var refreshToken: String,
        var elderId: String? = null,
        var disabled: Boolean = false,
        var lastHeartbeatAt: Long? = null,
        var lastForegroundAt: Long? = null,
        var appliedVersion: Int? = null,
    )

    @Serializable
    data class FakeElder(
        val elderId: String,
        var displayName: String,
        var stationName: String? = null,
        var routine: RoutineDto,
        val orders: MutableList<MedicationOrderDto> = mutableListOf(),
        var version: Int = 0,
        var publishedAt: Long = 0,
        var effectiveFrom: Long = 0,
        var changeSummary: String? = null,
        var changeSource: ChangeSource? = null,
        /** 發佈時合併好的 occurrence（第 2.3 節生效規則的結果）。 */
        var frozen: List<OccurrenceDto> = emptyList(),
        val events: MutableList<DoseEventDto> = mutableListOf(),
    )

    @Serializable
    data class FakePairing(val code: String, val deviceId: String, val expiresAt: Long)

    @Serializable
    data class FakeState(
        var nextId: Int = 1,
        var currentDeviceId: String? = null,
        val devices: MutableList<FakeDevice> = mutableListOf(),
        val elders: MutableList<FakeElder> = mutableListOf(),
        val pairing: MutableList<FakePairing> = mutableListOf(),
        val requestResults: MutableMap<String, ChangeRequestResultDto> = mutableMapOf(),
        val uploads: MutableMap<String, String> = mutableMapOf(),
        var appEvents: Int = 0,
    )

    private val state: FakeState = file?.takeIf { it.exists() }
        ?.let { runCatching { ApiJson.decodeFromString(FakeState.serializer(), it.readText()) }.getOrNull() }
        ?: FakeState()

    /** 模擬斷網：所有呼叫丟 IOException。 */
    @Volatile var offline = false
    /** 下一次呼叫回這個 HTTP 狀態碼（例如 401、500），用一次就清掉。 */
    @Volatile var failNext: Int? = null

    private val catalog = FakeCatalog.items.associateBy { it.catalogId }
    private val catalogEtag = "\"c1\""

    // ---------- 共用 ----------

    private fun save() {
        file ?: return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiJson.encodeToString(FakeState.serializer(), state))
        tmp.renameTo(file)
    }

    private fun now() = clock()
    private fun serverTime() = ApiConventions.formatTime(now())
    private fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(ApiConventions.ZONE).toLocalDate()
    private fun newId(prefix: String) = "${prefix}_%04d".format(state.nextId++)

    private fun <T> gate(block: () -> Response<T>): Response<T> = synchronized(this) {
        if (offline) throw IOException("模擬斷網")
        failNext?.let { code ->
            failNext = null
            val c = if (code == 401) ErrorCodes.TOKEN_EXPIRED else null
            return error(code, "Simulated", c, "模擬的錯誤")
        }
        block()
    }

    private fun <T> error(status: Int, title: String, code: String?, detail: String? = null): Response<T> {
        val body = ApiJson.encodeToString(ProblemDto.serializer(), ProblemDto(title = title, status = status, code = code, detail = detail))
        return Response.error(status, body.toResponseBody("application/problem+json".toMediaType()))
    }

    private fun <T> notModified(): Response<T> {
        val raw = okhttp3.Response.Builder().code(304).protocol(Protocol.HTTP_1_1).message("Not Modified")
            .request(Request.Builder().url("http://fake.local/").build()).build()
        return Response.error("".toResponseBody(null), raw)
    }

    private fun me(): FakeDevice? = state.devices.firstOrNull { it.deviceId == state.currentDeviceId }

    /** 凱比的請求：檢查登記、停用、綁定。 */
    private fun <T> device(requireElder: Boolean = true, block: (FakeDevice, FakeElder?) -> Response<T>): Response<T> {
        val d = me() ?: return error(401, "Unauthorized", ErrorCodes.TOKEN_EXPIRED)
        if (d.disabled) return error(403, "Device disabled", ErrorCodes.DEVICE_DISABLED, "這台凱比已停用，請聯絡照服員。")
        val e = d.elderId?.let { id -> state.elders.firstOrNull { it.elderId == id } }
        if (requireElder && e == null) return error(409, "Device not assigned", ErrorCodes.DEVICE_NOT_ASSIGNED, "這台凱比還沒有綁定長者。")
        return block(d, e)
    }

    // ---------- 排程計算 ----------

    private fun orderInputs(e: FakeElder): List<OrderInput> = e.orders.mapNotNull { o ->
        val c = catalog[o.catalogId] ?: return@mapNotNull null
        OrderInput(
            medicationId = o.orderId ?: return@mapNotNull null,
            catalogId = o.catalogId,
            doseText = o.dose?.text ?: c.defaultDose.text,
            slots = slotsOf(o, c),
            customTimes = o.customTimes.map { ApiConventions.parseHm(it) },
            weeklyOn = o.weeklyOn ?: c.weeklyOn,
            startDate = o.startDate?.let { LocalDate.parse(it) },
            endDate = o.endDate?.let { LocalDate.parse(it) },
            displayName = c.displayName,
        )
    }

    /** 處方的時段：需要時才吃 → 沒有；沒寫 → 目錄預設。 */
    private fun slotsOf(o: MedicationOrderDto, c: CatalogEntryDto): List<Slot> =
        if (o.asNeeded) emptyList() else o.slots.ifEmpty { c.defaultSlots }

    private fun window() = today().minusDays(Rules.SCHEDULE_DAYS_BEHIND.toLong()) to today().plusDays(Rules.SCHEDULE_DAYS_AHEAD.toLong())

    /** 目前這個版本的 occurrence（視窗每天往後滾）。 */
    private fun currentOccurrences(e: FakeElder): List<OccurrenceDto> {
        if (e.version == 0) return emptyList()
        val (from, to) = window()
        val fresh = OccurrenceExpander.expand(e.elderId, orderInputs(e), RoutineMinutes.from(e.routine), from, to)
        val fromKey = from.toString()
        return PublishRules.compose(e.frozen, fresh, e.effectiveFrom, now()).filter { it.date >= fromKey && it.date <= to.toString() }
    }

    private fun takenIds(e: FakeElder) = e.events.filter { it.type == DoseEventType.TAKEN || it.type == DoseEventType.LATE_TAKEN }
        .map { it.occurrenceId }.toSet()

    /** 發佈新版本：[mutate] 改處方或作息，再用第 2.3 節的規則合併。 */
    private fun publish(
        e: FakeElder,
        source: ChangeSource,
        summary: String?,
        effectiveFrom: Long = now(),
        removed: Set<String> = emptySet(),
        routineChanged: Boolean = false,
        mutate: () -> Unit = {},
    ) {
        val previous = currentOccurrences(e)
        mutate()
        val (from, to) = window()
        val fresh = OccurrenceExpander.expand(e.elderId, orderInputs(e), RoutineMinutes.from(e.routine), from, to)
        e.frozen = PublishRules.compose(previous, fresh, effectiveFrom, now(), removed, takenIds(e), routineChanged)
        e.version += 1
        e.publishedAt = now()
        e.effectiveFrom = effectiveFrom
        e.changeSummary = summary
        e.changeSource = source
    }

    private fun scheduleOf(e: FakeElder): ScheduleDto {
        val todayKey = today().toString()
        // 吃完的藥（過了 endDate）不放進藥單；今天以前的紀錄靠 occurrence 裡的藥名顯示
        val meds = e.orders.filter { it.endDate == null || it.endDate!! >= todayKey }.mapNotNull { o ->
            val c = catalog[o.catalogId] ?: return@mapNotNull null
            val slots = slotsOf(o, c)
            MedicationDto(
                medicationId = o.orderId!!, catalogId = c.catalogId, displayName = c.displayName,
                genericName = c.genericName, brandName = c.brandName, dose = o.dose ?: c.defaultDose,
                instructions = when {
                    slots.any { it.name.startsWith("BEFORE_") } -> "飯前"
                    slots.any { it.name.startsWith("AFTER_") } -> "飯後"
                    else -> null
                },
                slots = slots, customTimes = o.customTimes, weeklyOn = o.weeklyOn ?: c.weeklyOn, endDate = o.endDate,
            )
        }
        return ScheduleDto(
            version = e.version, etag = etagOf(e), publishedAt = ApiConventions.formatTime(e.publishedAt),
            effectiveFrom = ApiConventions.formatTime(e.effectiveFrom), timezone = ApiConventions.TIMEZONE,
            changeSummary = e.changeSummary, changeSource = e.changeSource, routine = e.routine,
            medications = meds, occurrences = currentOccurrences(e), serverTime = serverTime(),
        )
    }

    /** ETag 含版本和視窗的第一天：排程視窗每天往後滾，所以每天至少會拿到新的內容。 */
    private fun etagOf(e: FakeElder) = "\"v${e.version}-${window().first}\""

    // ---------- KebbiApi ----------

    override suspend fun register(idempotencyKey: String, body: RegisterRequest) = gate {
        val d = state.devices.firstOrNull { it.hardwareId == body.hardwareId }?.also {
            it.accessToken = "at_" + Random.nextLong().toString(16); it.refreshToken = "rt_" + Random.nextLong().toString(16)
        } ?: FakeDevice(newId("dev"), body.hardwareId, "at_" + Random.nextLong().toString(16), "rt_" + Random.nextLong().toString(16))
            .also { state.devices += it }
        state.currentDeviceId = d.deviceId
        save()
        Response.success(201, RegisterResponse(d.deviceId, d.accessToken, d.refreshToken, 2_592_000, d.elderId != null, serverTime()))
    }

    override suspend fun createPairingCode(idempotencyKey: String) = gate {
        device(requireElder = false) { d, _ ->
            state.pairing.removeAll { it.deviceId == d.deviceId || it.expiresAt < now() }
            val code = "%06d".format(Random.nextInt(0, 1_000_000))
            val exp = now() + Rules.PAIRING_CODE_TTL_MIN * 60_000L
            state.pairing += FakePairing(code, d.deviceId, exp)
            save()
            Response.success(201, PairingCodeResponse(code, ApiConventions.formatTime(exp), "https://fake.local/pair?code=$code", serverTime()))
        }
    }

    override suspend fun refreshToken(body: TokenRefreshRequest) = gate {
        val d = state.devices.firstOrNull { it.refreshToken == body.refreshToken }
            ?: return@gate error(401, "Invalid refresh token", ErrorCodes.TOKEN_EXPIRED)
        d.accessToken = "at_" + Random.nextLong().toString(16)
        save()
        Response.success(TokenRefreshResponse(d.accessToken, 2_592_000))
    }

    override suspend fun getMe() = gate {
        device(requireElder = false) { d, e ->
            Response.success(DeviceMeResponse(
                deviceId = d.deviceId, assigned = e != null,
                elder = e?.let { ElderDto(it.elderId, it.displayName, it.stationName) },
                latestScheduleVersion = e?.version, rewards = e?.let { rewardsOf(it) }, serverTime = serverTime(),
            ))
        }
    }

    override suspend fun heartbeat(body: HeartbeatRequest) = gate {
        device(requireElder = false) { d, e ->
            d.lastHeartbeatAt = now()
            if (body.foreground) d.lastForegroundAt = now()
            d.appliedVersion = body.appliedScheduleVersion
            save()
            Response.success(HeartbeatResponse(e?.version, serverTime()))
        }
    }

    override suspend fun getSchedule(etag: String?) = gate {
        device { _, e ->
            if (etag != null && etag == etagOf(e!!)) notModified() else Response.success(scheduleOf(e!!))
        }
    }

    override suspend fun ackSchedule(idempotencyKey: String, body: ScheduleAckRequest) = gate {
        device { d, _ -> d.appliedVersion = body.version; save(); Response.success(AckResponse(serverTime())) }
    }

    override suspend fun postDoseEvents(idempotencyKey: String, body: DoseEventBatchDto) = gate {
        device { _, e ->
            val known = (currentOccurrences(e!!) + e.frozen).map { it.occurrenceId }.toSet() + e.events.map { it.occurrenceId }
            val accepted = mutableListOf<String>()
            val duplicates = mutableListOf<String>()
            val rejected = mutableListOf<RejectedEventDto>()
            for (ev in body.events) {
                val taken = ev.type == DoseEventType.TAKEN || ev.type == DoseEventType.LATE_TAKEN
                when {
                    e.events.any { it.eventId == ev.eventId } -> duplicates += ev.eventId
                    ev.occurrenceId !in known -> rejected += RejectedEventDto(ev.eventId, ErrorCodes.UNKNOWN_OCCURRENCE)
                    taken && e.events.any { it.occurrenceId == ev.occurrenceId && (it.type == DoseEventType.TAKEN || it.type == DoseEventType.LATE_TAKEN) } -> {
                        e.events += ev; duplicates += ev.eventId // 同一包第二筆「吃了」：以第一筆為準
                    }
                    else -> { e.events += ev; accepted += ev.eventId }
                }
            }
            save()
            Response.success(DoseEventResultDto(accepted, duplicates, rejected, e.version, serverTime()))
        }
    }

    override suspend fun getAdherence(date: String) = gate {
        device { d, e ->
            val online = !DoseStatusRules.deviceOffline(d.lastHeartbeatAt, now())
            val items = currentOccurrences(e!!).filter { it.date == date }.map { o ->
                val st = statusOf(e, o, online)
                val taken = e.events.firstOrNull { it.occurrenceId == o.occurrenceId && (it.type == DoseEventType.TAKEN || it.type == DoseEventType.LATE_TAKEN) }
                AdherenceItemDto(o.occurrenceId, st.name.lowercase(), taken?.occurredAt, st == OfficialStatus.TAKEN_LATE)
            }
            Response.success(AdherenceDto(date, items))
        }
    }

    override suspend fun postAppEvents(idempotencyKey: String, body: AppEventBatchDto) = gate {
        device(requireElder = false) { _, _ ->
            state.appEvents += body.events.size
            save()
            Response.success(AppEventResultDto(body.events.map { it.eventId }, serverTime()))
        }
    }

    override suspend fun uploadPhoto(
        idempotencyKey: String,
        file: MultipartBody.Part,
        purpose: RequestBody,
        requestId: RequestBody,
        capturedAt: RequestBody,
        sha256: RequestBody,
    ) = gate {
        device { _, _ ->
            val size = file.body.contentLength()
            if (size > Rules.UPLOAD_MAX_BYTES) return@device error(413, "Payload too large", ErrorCodes.PAYLOAD_TOO_LARGE)
            val sha = okio.Buffer().also { sha256.writeTo(it) }.readUtf8()
            val existing = state.uploads[sha]
            if (existing != null) {
                Response.success(200, UploadResultDto(existing, sha))
            } else {
                // 假後端不存照片：收下就丟掉（D17：後端怎麼處理與我們無關）
                val id = newId("upl")
                state.uploads[sha] = id
                save()
                Response.success(201, UploadResultDto(id, sha))
            }
        }
    }

    override suspend fun postChangeRequest(idempotencyKey: String, body: ChangeRequestDto) = gate {
        device { _, e ->
            state.requestResults[body.requestId]?.let { return@device Response.success(it) }
            val plan = ElderPlan(orderInputs(e!!), RoutineMinutes.from(e.routine))
            val result = when (val r = ChangeRequestRules.apply(plan, body, catalog, today()) { newId("med") }) {
                is ApplyResult.Rejected -> return@device error(422, "Invalid request", r.code, r.detail)
                is ApplyResult.NoChange -> ChangeRequestResultDto(body.requestId, ChangeRequestStatus.NO_CHANGE, r.reason, e.version, serverTime = serverTime())
                ApplyResult.Recorded -> ChangeRequestResultDto(body.requestId, ChangeRequestStatus.RECORDED, serverTime = serverTime())
                is ApplyResult.Applied -> {
                    publish(e, ChangeSource.DEVICE_REQUEST, null, removed = r.removedMedicationIds, routineChanged = r.routineChanged) {
                        e.routine = r.plan.routine.toDto()
                        val keep = r.plan.orders.map { it.medicationId }.toSet()
                        e.orders.removeAll { it.orderId !in keep }
                        r.plan.orders.filter { o -> e.orders.none { it.orderId == o.medicationId } }.forEach { o ->
                            e.orders += MedicationOrderDto(
                                o.medicationId, o.catalogId, DoseDto(text = o.doseText, unit = "顆"), o.slots, weeklyOn = o.weeklyOn,
                                endDate = o.endDate?.toString(), asNeeded = o.slots.isEmpty(),
                            )
                        }
                    }
                    ChangeRequestResultDto(body.requestId, ChangeRequestStatus.APPLIED, null, e.version, r.addedMedicationId, serverTime())
                }
            }
            state.requestResults[body.requestId] = result
            save()
            Response.success(result)
        }
    }

    override suspend fun getCatalog(etag: String?) = gate {
        if (etag == catalogEtag) notModified()
        else Response.success(CatalogDto(1, catalogEtag, FakeCatalog.categories, FakeCatalog.items, serverTime()))
    }

    // ---------- 判定 ----------

    private fun statusOf(e: FakeElder, o: OccurrenceDto, online: Boolean): OfficialStatus = DoseStatusRules.status(
        ApiConventions.parseTime(o.scheduledAt), ApiConventions.parseTime(o.lateDeadline),
        e.events.filter { it.occurrenceId == o.occurrenceId }.map { EventInput(it.type, ApiConventions.parseTime(it.occurredAt)) },
        now(), online,
    )

    /** 開花（D20）：今天以前全部吃完的天數；今天是否已經全部吃完。 */
    private fun rewardsOf(e: FakeElder): RewardsDto {
        val all = currentOccurrences(e) + e.frozen
        val byDate = all.distinctBy { it.occurrenceId }.groupBy { it.date }
        val taken = takenIds(e)
        val doneDays = byDate.filter { (_, list) -> list.isNotEmpty() && list.all { it.occurrenceId in taken } }.keys
        val todayKey = today().toString()
        return RewardsDto(flowers = doneDays.count { it < todayKey }, todayComplete = todayKey in doneDays)
    }

    // ---------- AdminApi（後台） ----------

    override suspend fun listElders() = gate {
        Response.success(state.elders.map { ElderSummaryDto(it.elderId, it.displayName, it.stationName) })
    }

    override suspend fun createElder(body: ElderCreateRequest) = gate {
        val e = FakeElder(newId("eld"), body.displayName, body.stationName, body.routine ?: RoutineMinutes.DEFAULT.toDto())
        state.elders += e
        save()
        Response.success(201, ElderSummaryDto(e.elderId, e.displayName, e.stationName))
    }

    override suspend fun claimDevice(body: ClaimDeviceRequest) = gate {
        val p = state.pairing.firstOrNull { it.code == body.code && it.expiresAt > now() }
            ?: return@gate error(404, "Pairing code invalid", ErrorCodes.PAIRING_CODE_INVALID, "配對碼錯了或過期了。")
        val e = state.elders.firstOrNull { it.elderId == body.elderId } ?: return@gate error(404, "Elder not found", null)
        val d = state.devices.first { it.deviceId == p.deviceId }
        d.elderId = e.elderId
        state.pairing.remove(p)
        if (e.version == 0) publish(e, ChangeSource.INITIAL, null)
        save()
        Response.success(ClaimDeviceResponse(d.deviceId, e.elderId))
    }

    override suspend fun unassignDevice(deviceId: String) = gate {
        state.devices.firstOrNull { it.deviceId == deviceId }?.elderId = null
        save()
        Response.success(Unit)
    }

    override suspend fun disableDevice(deviceId: String) = gate {
        state.devices.firstOrNull { it.deviceId == deviceId }?.disabled = true
        save()
        Response.success(Unit)
    }

    override suspend fun enableDevice(deviceId: String) = gate {
        state.devices.firstOrNull { it.deviceId == deviceId }?.disabled = false
        save()
        Response.success(Unit)
    }

    override suspend fun listOrders(elderId: String) = gate {
        Response.success(state.elders.firstOrNull { it.elderId == elderId }?.orders?.toList() ?: emptyList())
    }

    override suspend fun createOrder(elderId: String, body: MedicationOrderDto) = gate {
        val e = state.elders.firstOrNull { it.elderId == elderId } ?: return@gate error(404, "Elder not found", null)
        if (catalog[body.catalogId] == null) return@gate error(422, "Unknown catalog", ErrorCodes.UNKNOWN_CATALOG_ID, "目錄裡沒有這個藥。")
        val o = body.copy(orderId = newId("med"))
        e.orders += o
        save()
        Response.success(201, o)
    }

    override suspend fun deleteOrder(orderId: String) = gate {
        state.elders.forEach { e -> e.orders.removeAll { it.orderId == orderId } }
        save()
        Response.success(Unit)
    }

    override suspend fun putRoutine(elderId: String, body: RoutineDto) = gate {
        val e = state.elders.firstOrNull { it.elderId == elderId } ?: return@gate error(404, "Elder not found", null)
        RoutineMinutes.from(body).validate()?.let { return@gate error(422, "Invalid routine", ErrorCodes.INVALID_ROUTINE, it) }
        e.routine = body
        save()
        Response.success(body)
    }

    override suspend fun publishSchedule(elderId: String, body: PublishRequest) = gate {
        val e = state.elders.firstOrNull { it.elderId == elderId } ?: return@gate error(404, "Elder not found", null)
        val from = if (body.effective == "tomorrow") today().plusDays(1).atStartOfDay(ApiConventions.ZONE).toInstant().toEpochMilli() else now()
        publish(e, ChangeSource.CAREGIVER, body.changeSummary ?: "站點照服員幫您更新了藥。", effectiveFrom = from)
        save()
        Response.success(PublishResponse(e.version, ApiConventions.formatTime(e.effectiveFrom)))
    }

    // ---------- debug 選單用（模擬照服員在後台的操作） ----------

    private fun currentElder(): FakeElder? = me()?.elderId?.let { id -> state.elders.firstOrNull { it.elderId == id } }

    /** 模擬照服員在後台輸入凱比畫面上的配對碼，綁定「測試長者」。 */
    suspend fun debugClaim(): String {
        val d = me() ?: return "凱比還沒登記"
        val p = state.pairing.firstOrNull { it.deviceId == d.deviceId && it.expiresAt > now() } ?: return "還沒有配對碼"
        val e = state.elders.firstOrNull() ?: createElder(ElderCreateRequest("測試長者", "測試站")).body()!!.let { s -> state.elders.first { it.elderId == s.elderId } }
        val r = claimDevice(ClaimDeviceRequest(p.code, e.elderId))
        return if (r.isSuccessful) "已綁定 ${e.displayName}" else "綁定失敗（${r.code()}）"
    }

    /** 後台加一個目錄裡的藥（還沒加過、有固定時段的），立即生效。 */
    suspend fun debugAddMed(): String = synchronized(this) {
        val e = currentElder() ?: return "還沒綁定"
        val c = FakeCatalog.items.filter { it.defaultSlots.isNotEmpty() && e.orders.none { o -> o.catalogId == it.catalogId } }.randomOrNull()
            ?: return "目錄裡的藥都加過了"
        publish(e, ChangeSource.CAREGIVER, "站點照服員幫您更新了藥。新增：${c.displayName}。") {
            e.orders += MedicationOrderDto(newId("med"), c.catalogId)
        }
        save()
        "後台新增 ${c.displayName}（${c.genericName}），版本 ${e.version}"
    }

    /** 後台停掉第一個藥，立即生效（正在提醒中的維持原狀，第 2.3 節）。 */
    suspend fun debugRemoveMed(): String = synchronized(this) {
        val e = currentElder() ?: return "還沒綁定"
        val o = e.orders.firstOrNull() ?: return "藥單是空的"
        val name = catalog[o.catalogId]?.displayName ?: o.catalogId
        publish(e, ChangeSource.CAREGIVER, "站點照服員幫您更新了藥。拿掉：$name。") { e.orders.remove(o) }
        save()
        "後台停掉 $name，版本 ${e.version}"
    }

    /** 後台把三餐、睡覺時間都往後延 [minutes] 分鐘，立即生效。 */
    suspend fun debugShiftRoutine(minutes: Int): String = synchronized(this) {
        val e = currentElder() ?: return "還沒綁定"
        val r = RoutineMinutes.from(e.routine)
        val n = RoutineMinutes(r.breakfast + minutes, r.lunch + minutes, r.dinner + minutes, r.sleep + minutes)
        n.validate()?.let { return "不能改：$it" }
        publish(e, ChangeSource.CAREGIVER, "站點照服員幫您改了吃藥時間。", routineChanged = true) { e.routine = n.toDto() }
        save()
        "後台改作息：早餐 ${ApiConventions.formatHm(n.breakfast)}，版本 ${e.version}"
    }

    /**
     * 測試用：在 [minutesFromNow] 分鐘後加一個「指定時間」的藥（D7 的 CUSTOM），立即生效。
     * 方便在凱比上實測提醒，不用等到吃飯時間。
     */
    suspend fun debugAddMedAt(minutesFromNow: Int): String = synchronized(this) {
        val e = currentElder() ?: return "還沒綁定"
        val c = catalog["M19"]!!
        val at = ((now() + minutesFromNow * 60_000L).let { Instant.ofEpochMilli(it).atZone(ApiConventions.ZONE) }).let { it.hour * 60 + it.minute }
        publish(e, ChangeSource.CAREGIVER, "站點照服員幫您加了一個測試用的提醒。") {
            e.orders.removeAll { it.catalogId == c.catalogId }
            e.orders += MedicationOrderDto(newId("med"), c.catalogId, slots = listOf(Slot.CUSTOM), customTimes = listOf(ApiConventions.formatHm(at)))
        }
        save()
        "後台新增測試提醒：${c.displayName} ${ApiConventions.formatHm(at)}，版本 ${e.version}"
    }

    fun debugUnassign(): String = synchronized(this) { me()?.elderId = null; save(); "已解除綁定" }

    fun debugToggleDisabled(): String = synchronized(this) {
        val d = me() ?: return "凱比還沒登記"
        d.disabled = !d.disabled
        save()
        if (d.disabled) "已停用這台凱比" else "已恢復這台凱比"
    }

    /** 後台看到的狀態（用 reference 的 DoseStatusRules 判定），給 debug 頁顯示。 */
    fun debugSummary(): String = synchronized(this) {
        val d = me() ?: return "凱比還沒登記"
        val e = currentElder() ?: return "凱比 ${d.deviceId}：還沒綁定" + if (d.disabled) "（已停用）" else ""
        val online = !DoseStatusRules.deviceOffline(d.lastHeartbeatAt, now())
        val todayKey = today().toString()
        val lines = currentOccurrences(e).filter { it.date == todayKey }.joinToString("\n") { o ->
            val st = statusOf(e, o, online)
            val alert = if (DoseStatusRules.overdueAlertDue(st, ApiConventions.parseTime(o.scheduledAt), now())) "（會發 LINE）" else ""
            "${o.slotLabel} ${o.scheduledAt.substring(11, 16)}：${st.name}$alert"
        }
        val noFg = DoseStatusRules.appNotForegroundAlertDue(d.lastForegroundAt, online, now())
        "${e.displayName}｜排程第 ${e.version} 版（凱比套用 ${d.appliedVersion}）｜${e.orders.size} 種藥｜事件 ${e.events.size} 筆｜照片 ${state.uploads.size} 張\n" +
            "凱比${if (online) "在線" else "離線"}" + (if (noFg) "｜App 不在前景超過 30 分鐘（會通知照服員）" else "") + "\n今天：\n" + lines.ifEmpty { "（沒有）" }
    }
}
