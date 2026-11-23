package tw.kebbi.medreminder.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import tw.kebbi.medreminder.api.dto.AckResponse
import tw.kebbi.medreminder.api.dto.AdherenceDto
import tw.kebbi.medreminder.api.dto.AppEventBatchDto
import tw.kebbi.medreminder.api.dto.AppEventResultDto
import tw.kebbi.medreminder.api.dto.CatalogDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestResultDto
import tw.kebbi.medreminder.api.dto.DeviceMeResponse
import tw.kebbi.medreminder.api.dto.DoseEventBatchDto
import tw.kebbi.medreminder.api.dto.DoseEventResultDto
import tw.kebbi.medreminder.api.dto.HeartbeatRequest
import tw.kebbi.medreminder.api.dto.HeartbeatResponse
import tw.kebbi.medreminder.api.dto.PairingCodeResponse
import tw.kebbi.medreminder.api.dto.RegisterRequest
import tw.kebbi.medreminder.api.dto.RegisterResponse
import tw.kebbi.medreminder.api.dto.ScheduleAckRequest
import tw.kebbi.medreminder.api.dto.ScheduleDto
import tw.kebbi.medreminder.api.dto.TokenRefreshRequest
import tw.kebbi.medreminder.api.dto.TokenRefreshResponse
import tw.kebbi.medreminder.api.dto.UploadResultDto

/**
 * 凱比會呼叫的所有後端 API。Base URL：`https://<host>/api/v1/`（[ApiConventions]）。
 *
 * - 一個函式對應一個 endpoint，HTTP 方法和路徑寫在 Retrofit 註記上。
 * - 除了 [register]、[refreshToken]，都要帶 `Authorization: Bearer <deviceAccessToken>`（App 的 interceptor 統一加）。
 * - 回傳 [Response]：App 看 HTTP 狀態碼處理錯誤（[ErrorCodes] 有每個錯誤碼的處理方式）。
 * - 範例 JSON：`api/src/test/resources/samples/`。後端可以拿來比對自己的輸出。
 *
 * App 裡只有 `sync/` 層會呼叫這個介面。後端完成前，App 用假後端（`FakeBackend`）實作同一個介面。
 */
interface KebbiApi {

    /**
     * A1. 凱比第一次啟動時登記，拿到 token。
     * - 什麼時候呼叫：App 第一次啟動、或 token 換不回來時。
     * - 不需要 Authorization。`hardwareId` 已登記過時回傳同一個 `deviceId`。
     */
    @POST("devices/register")
    suspend fun register(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
        @Body body: RegisterRequest,
    ): Response<RegisterResponse>

    /**
     * A2. 取得配對碼，顯示在凱比的「等待綁定」畫面（D29 (c)）。
     * - 照服員在後台輸入或掃描這個碼，選擇長者，就完成綁定（後台用 `POST /devices/claim`，見 [AdminApi.claimDevice]）。
     * - 10 分鐘有效，凱比到期前自動換新；等待期間每 15 秒查一次 [getMe]。
     */
    @POST("devices/me/pairing-code")
    suspend fun createPairingCode(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
    ): Response<PairingCodeResponse>

    /** A3. 用 refresh token 換新的 access token。不需要 Authorization。失敗（401）時凱比重新登記。 */
    @POST("devices/token/refresh")
    suspend fun refreshToken(@Body body: TokenRefreshRequest): Response<TokenRefreshResponse>

    /**
     * A4. 查詢綁定狀態、長者資訊、開花數（D20）。
     * - 什麼時候呼叫：開機、等待綁定時每 15 秒、每次心跳之後（拿開花數）。
     * - 沒綁定時回 `assigned:false`，凱比顯示配對畫面、不提醒。403：凱比被停用。
     */
    @GET("devices/me")
    suspend fun getMe(): Response<DeviceMeResponse>

    /**
     * A5. 心跳，同時當作「有沒有新排程」的輪詢（D28）。
     * - App 在前景時每 1 分鐘；在背景時由 WorkManager 每 15 分鐘；開機、網路恢復時各一次。
     * - 回應的 `latestScheduleVersion` 和凱比套用的版本不同，凱比就呼叫 [getSchedule]。
     * - 後端用心跳判斷凱比離線（D14）、App 不在前景（`foreground`，第 7.4 節）。
     */
    @POST("devices/me/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): Response<HeartbeatResponse>

    /**
     * B1. 取得排程（今天往前 1 天、往後 7 天的 occurrence）。
     * - 什麼時候呼叫：開機、心跳發現新版本、送出申請或事件後回應的版本不同（第 2.4 節）。
     * - 帶 `If-None-Match`（上次的 `etag`）；排程沒變回 304。
     * - 409 `DEVICE_NOT_ASSIGNED`：回到配對畫面。
     * - 凱比以 `occurrenceId` 合併：已提醒、已吃的保留本機狀態；被刪除的未來 occurrence 取消提醒（第 2.3 節）。
     */
    @GET("devices/me/schedule")
    suspend fun getSchedule(
        @Header(ApiConventions.HEADER_IF_NONE_MATCH) etag: String?,
    ): Response<ScheduleDto>

    /** B2. 回報已套用的排程版本。後台看得到每台凱比套用到哪一版。 */
    @POST("devices/me/schedule/ack")
    suspend fun ackSchedule(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
        @Body body: ScheduleAckRequest,
    ): Response<AckResponse>

    /**
     * C1. 批次上傳服藥事件，最多 100 筆（[Rules.MAX_EVENTS_PER_BATCH]）。
     * - 事件在發生的當下寫進 outbox，立刻送；失敗由 WorkManager 在有網路時補送（第 2.6 節）。
     * - 後端用 `eventId` 去重，同一批可以安全重送。同一個 occurrence 有多筆 `taken` 時以第一筆為準。
     * - 回應帶 `scheduleVersion`，和本機不同就呼叫 [getSchedule]（第 2.4 節第 7 項）。
     * - 後端依 `occurredAt` 判定準時、逾時、晚吃（D5），不是依送到的時間。
     */
    @POST("devices/me/dose-events")
    suspend fun postDoseEvents(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
        @Body body: DoseEventBatchDto,
    ): Response<DoseEventResultDto>

    /** C2（選用）. 凱比重灌或更換時，還原某一天的狀態。 */
    @GET("devices/me/adherence")
    suspend fun getAdherence(@Query("date") date: String): Response<AdherenceDto>

    /** C3（選用）. 非服藥類的操作紀錄（`app.start`、`app.exit`、`notification.opened`、`upload.too_large`…）。 */
    @POST("devices/me/app-events")
    suspend fun postAppEvents(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
        @Body body: AppEventBatchDto,
    ): Response<AppEventResultDto>

    /**
     * R1. 上傳藥袋照片原圖（D17），`multipart/form-data`。
     * - 欄位：`file`（JPEG 原圖，不縮圖）、`purpose=med_bag_photo`、`requestId`（對應 R2 的申請）、`capturedAt`、`sha256`。
     * - 凱比收到 201 就刪除本機照片；失敗就留在 outbox 重試。413：不重試（照片留在本機，回報 `upload.too_large`）。
     * - 同一個 `sha256` 重送時回 200 和原本的 `uploadId`。單檔上限請設 20 MB 以上。
     * - 照片上有長者姓名、病歷號等個資；怎麼存、存多久、誰能看，由後端決定。
     */
    @Multipart
    @POST("devices/me/uploads")
    suspend fun uploadPhoto(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
        @Part file: MultipartBody.Part,
        @Part("purpose") purpose: RequestBody,
        @Part("requestId") requestId: RequestBody,
        @Part("capturedAt") capturedAt: RequestBody,
        @Part("sha256") sha256: RequestBody,
    ): Response<UploadResultDto>

    /**
     * R2. 變更申請（加藥、停藥、改作息、清空藥單、目錄沒有的藥）。後端收到就自動套用，不需要核准（D8）。
     * - 先送申請、照片後傳（[uploadPhoto]）：申請很小，排程可以馬上更新。
     * - 回應 `applied`／`no_change`／`recorded`；`applied` 時帶新版本號，凱比立刻 [getSchedule]。
     * - 422：內容不合理（例如作息順序錯），`detail` 是給長者聽的原因。
     * - `requestId` 去重：重送時回傳第一次的結果。生效規則見 `reference/ChangeRequestRules`。
     */
    @POST("devices/me/change-requests")
    suspend fun postChangeRequest(
        @Header(ApiConventions.HEADER_IDEMPOTENCY) idempotencyKey: String,
        @Body body: ChangeRequestDto,
    ): Response<ChangeRequestResultDto>

    /**
     * E1. 藥品目錄（D6）。凱比開機時和每天檢查一次，帶 `If-None-Match`。
     * 凱比的目錄快取找不到排程裡的 `catalogId` 時，也會先更新目錄再套用排程。
     */
    @GET("medications/catalog")
    suspend fun getCatalog(
        @Header(ApiConventions.HEADER_IF_NONE_MATCH) etag: String?,
    ): Response<CatalogDto>
}
