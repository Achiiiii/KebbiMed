package tw.kebbi.medreminder.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import tw.kebbi.medreminder.api.dto.ClaimDeviceRequest
import tw.kebbi.medreminder.api.dto.ClaimDeviceResponse
import tw.kebbi.medreminder.api.dto.ElderCreateRequest
import tw.kebbi.medreminder.api.dto.ElderSummaryDto
import tw.kebbi.medreminder.api.dto.MedicationOrderDto
import tw.kebbi.medreminder.api.dto.PublishRequest
import tw.kebbi.medreminder.api.dto.PublishResponse
import tw.kebbi.medreminder.api.dto.RoutineDto

/**
 * 後台（照服員網頁）中會影響凱比的操作。**凱比不會呼叫這個介面。**
 *
 * 用途：
 * - 讓後端知道凱比需要後台提供哪些操作。
 * - App 的假後端實作它，debug 版的照服員頁用它模擬照服員的操作（綁定、改藥、停用）。
 *
 * 驗證是照服員帳號的 JWT。後台網頁的其他 API（帳號、聯絡人、警示、服藥紀錄）由後端自己設計。
 */
interface AdminApi {

    @GET("elders")
    suspend fun listElders(): Response<List<ElderSummaryDto>>

    @POST("elders")
    suspend fun createElder(@Body body: ElderCreateRequest): Response<ElderSummaryDto>

    /** 綁定（D29 (c)）：照服員輸入凱比畫面上的配對碼，選擇長者。配對碼錯或過期回 404 `PAIRING_CODE_INVALID`。 */
    @POST("devices/claim")
    suspend fun claimDevice(@Body body: ClaimDeviceRequest): Response<ClaimDeviceResponse>

    /** 解除綁定。凱比下一次查詢發現沒有綁定，就清掉本機排程、回到配對畫面。 */
    @DELETE("devices/{deviceId}/assignment")
    suspend fun unassignDevice(@Path("deviceId") deviceId: String): Response<Unit>

    /** 停用凱比（例如合約結束、遺失）。之後凱比的請求都回 403 `DEVICE_DISABLED`。 */
    @POST("devices/{deviceId}/disable")
    suspend fun disableDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @POST("devices/{deviceId}/enable")
    suspend fun enableDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @GET("elders/{elderId}/medication-orders")
    suspend fun listOrders(@Path("elderId") elderId: String): Response<List<MedicationOrderDto>>

    @POST("elders/{elderId}/medication-orders")
    suspend fun createOrder(
        @Path("elderId") elderId: String,
        @Body body: MedicationOrderDto,
    ): Response<MedicationOrderDto>

    @DELETE("medication-orders/{orderId}")
    suspend fun deleteOrder(@Path("orderId") orderId: String): Response<Unit>

    @PUT("elders/{elderId}/routine")
    suspend fun putRoutine(
        @Path("elderId") elderId: String,
        @Body body: RoutineDto,
    ): Response<RoutineDto>

    /**
     * 發佈排程：把目前的處方和作息展開成新版本（[tw.kebbi.medreminder.api.reference.OccurrenceExpander]）。
     * 生效規則（第 2.3 節）：`now` 時已經開始的 occurrence 維持原狀，還沒到的照新版本；`tomorrow` 時今天都不動。
     */
    @POST("elders/{elderId}/schedule/publish")
    suspend fun publishSchedule(
        @Path("elderId") elderId: String,
        @Body body: PublishRequest,
    ): Response<PublishResponse>
}
