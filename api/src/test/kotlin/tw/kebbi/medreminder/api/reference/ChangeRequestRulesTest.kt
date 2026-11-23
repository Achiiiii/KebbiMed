package tw.kebbi.medreminder.api.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.api.ErrorCodes
import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.PrescriptionSlot
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import tw.kebbi.medreminder.api.dto.ChangeRequestDto
import tw.kebbi.medreminder.api.dto.ChangeRequestType
import tw.kebbi.medreminder.api.dto.DoseDto
import tw.kebbi.medreminder.api.dto.PrescriptionDto
import tw.kebbi.medreminder.api.dto.RoutineDto
import java.time.LocalDate

class ChangeRequestRulesTest {

    private val furosemide = CatalogEntryDto(
        "M13", "利尿劑", "Furosemide", "Lasix 來適泄", "心衰竭", listOf("Lasix"),
        DoseDto(1.0, "顆", "1 顆"), listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER), MealRelation.AFTER,
    )
    private val mosapride = CatalogEntryDto(
        "M31", "腸胃蠕動藥", "Mosapride", null, "腸胃", emptyList(),
        DoseDto(1.0, "顆", "1 顆"), listOf(Slot.BEFORE_BREAKFAST, Slot.BEFORE_LUNCH, Slot.BEFORE_DINNER), MealRelation.BEFORE,
    )
    private val thyroid = CatalogEntryDto(
        "M41", "甲狀腺藥", "Levothyroxine", null, "甲狀腺", emptyList(),
        DoseDto(1.0, "顆", "1 顆"), listOf(Slot.WAKE), MealRelation.ON_WAKE,
    )
    private val catalog = listOf(furosemide, mosapride, thyroid).associateBy { it.catalogId }
    private val empty = ElderPlan(emptyList(), RoutineMinutes.DEFAULT)
    private val today = LocalDate.of(2026, 11, 23)
    private var n = 0
    private val newId = { "med_${++n}" }

    private fun req(type: ChangeRequestType, catalogId: String? = null, p: PrescriptionDto? = null, routine: RoutineDto? = null, medId: String? = null) =
        ChangeRequestDto("r", type, "2026-11-23T10:00:00+08:00", catalogId = catalogId, prescription = p, routine = routine, medicationId = medId)

    @Test
    fun addUsesCatalogDefaultsWithoutQr() {
        val r = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13"), catalog, today, newId) as ApplyResult.Applied
        val o = r.plan.orders.single()
        assertEquals(listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER), o.slots)
        assertEquals("1 顆", o.doseText)
        assertEquals(o.medicationId, r.addedMedicationId)
    }

    @Test
    fun addingSameMedTwiceIsNoChange() {
        val first = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13"), catalog, today, newId) as ApplyResult.Applied
        val second = ChangeRequestRules.apply(first.plan, req(ChangeRequestType.ADD_MEDICATION, "M13"), catalog, today, newId)
        assertEquals(ApplyResult.NoChange("ALREADY_ACTIVE"), second)
    }

    @Test
    fun unknownCatalogIdIsRejected() {
        val r = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "X99"), catalog, today, newId)
        assertEquals(ErrorCodes.UNKNOWN_CATALOG_ID, (r as ApplyResult.Rejected).code)
    }

    @Test
    fun d26QrSlotsFollowMealRelation() {
        val p = PrescriptionDto(dosePerTime = 2.0, slots = listOf(PrescriptionSlot.MORNING, PrescriptionSlot.EVENING))
        assertEquals(listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER), ChangeRequestRules.slotsFor(furosemide, p))
        assertEquals(listOf(Slot.BEFORE_BREAKFAST, Slot.BEFORE_DINNER), ChangeRequestRules.slotsFor(mosapride, p))
        assertEquals(listOf(Slot.WAKE), ChangeRequestRules.slotsFor(thyroid, PrescriptionDto(slots = listOf(PrescriptionSlot.MORNING))))
        assertEquals("2 顆", ChangeRequestRules.doseTextFor(furosemide, p))
    }

    @Test
    fun d26TimesPerDayWithoutSlots() {
        fun s(n: Int) = ChangeRequestRules.slotsFor(furosemide, PrescriptionDto(timesPerDay = n))
        assertEquals(listOf(Slot.AFTER_BREAKFAST), s(1))
        assertEquals(listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_DINNER), s(2))
        assertEquals(listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_LUNCH, Slot.AFTER_DINNER), s(3))
        assertEquals(listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_LUNCH, Slot.AFTER_DINNER, Slot.BEDTIME), s(4))
    }

    @Test
    fun invalidRoutineIsRejectedWithReason() {
        val bad = RoutineDto("10:00", "11:00", "18:00", "22:00")
        val r = ChangeRequestRules.apply(empty, req(ChangeRequestType.CHANGE_ROUTINE, routine = bad), catalog, today, newId)
        r as ApplyResult.Rejected
        assertEquals(ErrorCodes.INVALID_ROUTINE, r.code)
        assertTrue(r.detail.contains("午餐"))
    }

    @Test
    fun validRoutineIsApplied() {
        val ok = RoutineDto("07:30", "12:00", "18:00", "21:30")
        val r = ChangeRequestRules.apply(empty, req(ChangeRequestType.CHANGE_ROUTINE, routine = ok), catalog, today, newId) as ApplyResult.Applied
        assertTrue(r.routineChanged)
        assertEquals(7 * 60 + 30, r.plan.routine.breakfast)
    }

    @Test
    fun removeAndClear() {
        val added = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13"), catalog, today, newId) as ApplyResult.Applied
        val id = added.addedMedicationId!!
        val removed = ChangeRequestRules.apply(added.plan, req(ChangeRequestType.REMOVE_MEDICATION, medId = id), catalog, today, newId) as ApplyResult.Applied
        assertEquals(setOf(id), removed.removedMedicationIds)
        assertTrue(removed.plan.orders.isEmpty())
        assertEquals(ApplyResult.NoChange("ALREADY_EMPTY"), ChangeRequestRules.apply(removed.plan, req(ChangeRequestType.CLEAR_MEDICATIONS), catalog, today, newId))
        assertEquals(ApplyResult.Recorded, ChangeRequestRules.apply(empty, req(ChangeRequestType.UNLISTED_MEDICATION), catalog, today, newId))
    }

    // ---------- D38：長者回答的用法 ----------

    @Test
    fun mealRelationChosenByElderOverridesCatalog() {
        // 目錄說飯後，長者選飯前
        val p = PrescriptionDto(timesPerDay = 3, mealRelation = MealRelation.BEFORE)
        assertEquals(listOf(Slot.BEFORE_BREAKFAST, Slot.BEFORE_LUNCH, Slot.BEFORE_DINNER), ChangeRequestRules.slotsFor(furosemide, p))
        // 4 次：早、午、晚加睡前
        assertEquals(
            listOf(Slot.AFTER_BREAKFAST, Slot.AFTER_LUNCH, Slot.AFTER_DINNER, Slot.BEDTIME),
            ChangeRequestRules.slotsFor(mosapride, PrescriptionDto(timesPerDay = 4, mealRelation = MealRelation.AFTER)),
        )
    }

    @Test
    fun onceADayUsesTheCatalogMealOrBedtime() {
        val statin = CatalogEntryDto("M20", "降血脂藥", "Atorvastatin", null, "高血脂", emptyList(),
            DoseDto(1.0, "顆", "1 顆"), listOf(Slot.AFTER_DINNER), MealRelation.AFTER)
        fun once(e: CatalogEntryDto, r: MealRelation?) = ChangeRequestRules.slotsFor(e, PrescriptionDto(timesPerDay = 1, mealRelation = r))
        assertEquals(listOf(Slot.AFTER_DINNER), once(statin, null))
        assertEquals(listOf(Slot.BEFORE_DINNER), once(statin, MealRelation.BEFORE))
        assertEquals(listOf(Slot.BEDTIME), once(statin, MealRelation.AT_BEDTIME))
        assertEquals(listOf(Slot.BEFORE_BREAKFAST), once(thyroid, MealRelation.BEFORE)) // 起床的藥選飯前 → 早餐前
        assertEquals(listOf(Slot.WAKE), once(furosemide, MealRelation.ON_WAKE))
    }

    @Test
    fun zeroTimesMeansAsNeeded() {
        assertEquals(emptyList<Slot>(), ChangeRequestRules.slotsFor(furosemide, PrescriptionDto(timesPerDay = 0)))
    }

    @Test
    fun halfAndFractionalDoses() {
        assertEquals("半顆", ChangeRequestRules.doseText(0.5, "顆"))
        assertEquals("1 顆", ChangeRequestRules.doseText(1.0, "顆"))
        assertEquals("1 顆半", ChangeRequestRules.doseText(1.5, "顆"))
        assertEquals("3 包", ChangeRequestRules.doseText(3.0, "包"))
    }

    @Test
    fun durationSetsTheLastDay() {
        val p = PrescriptionDto(timesPerDay = 2, durationDays = 7)
        val r = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13", p), catalog, today, newId) as ApplyResult.Applied
        val o = r.plan.orders.single()
        assertEquals(LocalDate.of(2026, 11, 29), o.endDate) // 今天算第 1 天
        assertTrue(o.activeOn(LocalDate.of(2026, 11, 29)))
        assertTrue(!o.activeOn(LocalDate.of(2026, 11, 30)))
        // 一直吃
        val forever = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13", PrescriptionDto(timesPerDay = 2)), catalog, today, newId) as ApplyResult.Applied
        assertEquals(null, forever.plan.orders.single().endDate)
    }

    @Test
    fun anEndedCourseCanBeAddedAgain() {
        val first = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13", PrescriptionDto(durationDays = 3)), catalog, today, newId) as ApplyResult.Applied
        // 吃完之前再加：已經有了
        assertEquals(ApplyResult.NoChange("ALREADY_ACTIVE"),
            ChangeRequestRules.apply(first.plan, req(ChangeRequestType.ADD_MEDICATION, "M13"), catalog, today.plusDays(2), newId))
        // 吃完之後再加：換成新的
        val again = ChangeRequestRules.apply(first.plan, req(ChangeRequestType.ADD_MEDICATION, "M13"), catalog, today.plusDays(3), newId) as ApplyResult.Applied
        assertEquals(listOf(again.addedMedicationId), again.plan.orders.map { it.medicationId })
    }

    @Test
    fun unreasonableAnswersAreRejected() {
        fun add(p: PrescriptionDto) = ChangeRequestRules.apply(empty, req(ChangeRequestType.ADD_MEDICATION, "M13", p), catalog, today, newId)
        assertTrue(add(PrescriptionDto(timesPerDay = 5)) is ApplyResult.Rejected)
        assertTrue(add(PrescriptionDto(dosePerTime = 0.0)) is ApplyResult.Rejected)
        assertTrue(add(PrescriptionDto(durationDays = 0)) is ApplyResult.Rejected)
    }
}
