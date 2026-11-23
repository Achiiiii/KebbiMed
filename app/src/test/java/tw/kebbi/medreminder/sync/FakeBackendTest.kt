package tw.kebbi.medreminder.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.ApiJson
import tw.kebbi.medreminder.api.ErrorCodes
import tw.kebbi.medreminder.api.ProblemDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestStatus
import tw.kebbi.medreminder.api.dto.ChangeRequestType
import tw.kebbi.medreminder.api.dto.DoseEventBatchDto
import tw.kebbi.medreminder.api.dto.DoseEventDto
import tw.kebbi.medreminder.api.dto.DoseEventType
import tw.kebbi.medreminder.api.dto.EventSource
import tw.kebbi.medreminder.api.dto.RegisterRequest
import tw.kebbi.medreminder.api.dto.RemoveReason
import tw.kebbi.medreminder.api.dto.RoutineDto
import java.io.IOException

/** 假後端的端到端流程：登記 → 配對 → 加藥 → 排程 → 事件 → 停藥。 */
class FakeBackendTest {
    private var now = ApiConventions.parseTime("2026-11-23T07:00:00+08:00")
    private val api = FakeBackend(file = null, clock = { now })

    private fun req(type: ChangeRequestType, id: String, catalogId: String? = null, medId: String? = null, routine: RoutineDto? = null) =
        ChangeRequestDto(id, type, ApiConventions.formatTime(now), catalogId = catalogId, medicationId = medId, routine = routine,
            reason = if (medId != null) RemoveReason.DOCTOR_SAID_STOP else null)

    @Test
    fun fullFlow() = runTest {
        // A1 登記，A4 還沒綁定
        val reg = api.register("k1", RegisterRequest("hw1", null, "AIR-H300", "15", "0.2.0", 2))
        assertEquals(201, reg.code())
        assertFalse(api.getMe().body()!!.assigned)
        assertEquals(409, api.getSchedule(null).code())

        // A2 配對碼 → 後台綁定
        val code = api.createPairingCode("k2").body()!!.code
        assertEquals(6, code.length)
        assertTrue(api.debugClaim().startsWith("已綁定"))
        val me = api.getMe().body()!!
        assertTrue(me.assigned)
        assertEquals(1, me.latestScheduleVersion)

        // R2 加藥（利尿劑：早餐後、晚餐後）
        val add = api.postChangeRequest("r1", req(ChangeRequestType.ADD_MEDICATION, "r1", catalogId = "M13")).body()!!
        assertEquals(ChangeRequestStatus.APPLIED, add.status)
        assertEquals(2, add.scheduleVersion)
        val medId = add.medicationId!!
        // 同一個 requestId 重送：回傳第一次的結果
        assertEquals(add, api.postChangeRequest("r1", req(ChangeRequestType.ADD_MEDICATION, "r1", catalogId = "M13")).body())
        // 再加一次同一個藥：no_change
        assertEquals(ChangeRequestStatus.NO_CHANGE, api.postChangeRequest("r2", req(ChangeRequestType.ADD_MEDICATION, "r2", catalogId = "M13")).body()!!.status)

        // B1 排程：今天 08:00 起都有
        val s = api.getSchedule(null).body()!!
        val morning = s.occurrences.first { it.date == "2026-11-23" && it.slotLabel == "早餐後" }
        assertEquals("2026-11-23T08:00:00+08:00", morning.scheduledAt)
        assertEquals("2026-11-23T13:15:00+08:00", morning.lateDeadline)
        assertEquals(1, s.medications.size)
        // ETag 沒變：304
        assertEquals(304, api.getSchedule(s.etag).code())

        // C1 服藥事件：去重、同一包第二筆「吃了」算重複
        now = ApiConventions.parseTime("2026-11-23T08:03:00+08:00")
        fun ev(id: String, type: DoseEventType) = DoseEventDto(id, morning.occurrenceId, type, ApiConventions.formatTime(now), EventSource.TOUCH)
        val r1 = api.postDoseEvents("e1", DoseEventBatchDto(listOf(ev("a", DoseEventType.TAKEN)))).body()!!
        assertEquals(listOf("a"), r1.accepted)
        assertEquals(2, r1.scheduleVersion)
        val r2 = api.postDoseEvents("e2", DoseEventBatchDto(listOf(ev("a", DoseEventType.TAKEN), ev("b", DoseEventType.TAKEN)))).body()!!
        assertEquals(listOf("a", "b"), r2.duplicates)
        val r3 = api.postDoseEvents("e3", DoseEventBatchDto(listOf(DoseEventDto("c", "occ_unknown", DoseEventType.TAKEN, ApiConventions.formatTime(now), EventSource.TOUCH)))).body()!!
        assertEquals(ErrorCodes.UNKNOWN_OCCURRENCE, r3.rejected.single().code)
        assertTrue(api.debugSummary().contains("TAKEN"))

        // 改作息不合理：422，detail 給長者聽
        val bad = api.postChangeRequest("r3", req(ChangeRequestType.CHANGE_ROUTINE, "r3", routine = RoutineDto("10:00", "11:00", "18:00", "22:00")))
        assertEquals(422, bad.code())
        val problem = ApiJson.decodeFromString(ProblemDto.serializer(), bad.errorBody()!!.string())
        assertEquals(ErrorCodes.INVALID_ROUTINE, problem.code)

        // 停藥：晚上的不見了，早上已經吃過的那包還在
        now = ApiConventions.parseTime("2026-11-23T09:00:00+08:00")
        val rm = api.postChangeRequest("r4", req(ChangeRequestType.REMOVE_MEDICATION, "r4", medId = medId)).body()!!
        assertEquals(ChangeRequestStatus.APPLIED, rm.status)
        val s2 = api.getSchedule(null).body()!!
        assertNotNull(s2.occurrences.firstOrNull { it.occurrenceId == morning.occurrenceId })
        assertNull(s2.occurrences.firstOrNull { it.date == "2026-11-23" && it.slotLabel == "晚餐後" })
        assertTrue(s2.medications.isEmpty())
    }

    @Test
    fun simulatedFailures() = runTest {
        api.register("k1", RegisterRequest("hw1", null, "AIR-H300", "15", "0.2.0", 2))
        api.offline = true
        try { api.getMe(); fail("應該要丟 IOException") } catch (_: IOException) { }
        api.offline = false
        api.failNext = 500
        assertEquals(500, api.getMe().code())
        assertEquals(200, api.getMe().code())
        api.debugToggleDisabled()
        assertEquals(403, api.getMe().code())
    }
}
