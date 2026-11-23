package tw.kebbi.medreminder.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.kebbi.medreminder.api.dto.AdherenceDto
import tw.kebbi.medreminder.api.dto.AppEventBatchDto
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
 * 範例 JSON（`src/test/resources/samples/`）都要能解析成資料類別，再輸出時不能少任何欄位。
 * 後端可以拿同一批範例比對自己的輸出。
 */
class SamplesTest {

    private val cases: Map<String, KSerializer<*>> = mapOf(
        "A1-register.request.json" to serializer<RegisterRequest>(),
        "A1-register.response.json" to serializer<RegisterResponse>(),
        "A2-pairing-code.response.json" to serializer<PairingCodeResponse>(),
        "A3-token-refresh.request.json" to serializer<TokenRefreshRequest>(),
        "A3-token-refresh.response.json" to serializer<TokenRefreshResponse>(),
        "A4-devices-me.response.json" to serializer<DeviceMeResponse>(),
        "A5-heartbeat.request.json" to serializer<HeartbeatRequest>(),
        "A5-heartbeat.response.json" to serializer<HeartbeatResponse>(),
        "B1-schedule.response.json" to serializer<ScheduleDto>(),
        "B2-schedule-ack.request.json" to serializer<ScheduleAckRequest>(),
        "C1-dose-events.request.json" to serializer<DoseEventBatchDto>(),
        "C1-dose-events.response.json" to serializer<DoseEventResultDto>(),
        "C2-adherence.response.json" to serializer<AdherenceDto>(),
        "C3-app-events.request.json" to serializer<AppEventBatchDto>(),
        "R1-upload.response.json" to serializer<UploadResultDto>(),
        "R2-add-medication.request.json" to serializer<ChangeRequestDto>(),
        "R2-remove-medication.request.json" to serializer<ChangeRequestDto>(),
        "R2-change-routine.request.json" to serializer<ChangeRequestDto>(),
        "R2-clear-medications.request.json" to serializer<ChangeRequestDto>(),
        "R2-unlisted-medication.request.json" to serializer<ChangeRequestDto>(),
        "R2-applied.response.json" to serializer<ChangeRequestResultDto>(),
        "R2-no-change.response.json" to serializer<ChangeRequestResultDto>(),
        "R2-recorded.response.json" to serializer<ChangeRequestResultDto>(),
        "R2-invalid-routine.problem.json" to serializer<ProblemDto>(),
        "E1-catalog.response.json" to serializer<CatalogDto>(),
        "problem-device-not-assigned.json" to serializer<ProblemDto>(),
    )

    @Test
    fun everySampleHasATest() {
        val dir = java.io.File(javaClass.getResource("/samples")!!.toURI())
        val files = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals("範例檔和測試清單要一致", files, cases.keys)
    }

    @Test
    fun samplesRoundTrip() {
        for ((file, ser) in cases) {
            val text = javaClass.getResource("/samples/$file")!!.readText()
            val original = ApiJson.parseToJsonElement(text)
            @Suppress("UNCHECKED_CAST")
            val s = ser as KSerializer<Any>
            val obj = try { ApiJson.decodeFromString(s, text) } catch (e: Exception) { fail("$file 解析失敗：${e.message}"); return }
            val again = ApiJson.encodeToJsonElement(s, obj)
            assertSubset(file, original, again, "$")
        }
    }

    /** [expected] 的每個非 null 欄位，都要出現在 [actual] 而且值相同。 */
    private fun assertSubset(file: String, expected: JsonElement, actual: JsonElement?, path: String) {
        when (expected) {
            is JsonNull -> return
            is JsonObject -> {
                assertTrue("$file $path 應該是物件", actual is JsonObject)
                expected.forEach { (k, v) -> assertSubset(file, v, (actual as JsonObject)[k], "$path.$k") }
            }
            is JsonArray -> {
                assertTrue("$file $path 應該是陣列", actual is JsonArray)
                val a = actual as JsonArray
                assertEquals("$file $path 陣列長度", expected.size, a.size)
                expected.forEachIndexed { i, v -> assertSubset(file, v, a[i], "$path[$i]") }
            }
            is JsonPrimitive -> {
                assertTrue("$file $path 不見了", actual is JsonPrimitive)
                val a = actual as JsonPrimitive
                if (expected.isString) assertEquals("$file $path", expected.content, a.content)
                else assertEquals("$file $path", expected.doubleOrNull ?: expected.content, a.doubleOrNull ?: a.content)
            }
        }
    }
}
