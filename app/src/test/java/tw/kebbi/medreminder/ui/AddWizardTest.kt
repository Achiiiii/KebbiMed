package tw.kebbi.medreminder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.PrescriptionSlot
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.IdentifiedBy
import tw.kebbi.medreminder.api.dto.PrescriptionDto
import tw.kebbi.medreminder.api.reference.RoutineMinutes
import tw.kebbi.medreminder.sync.FakeCatalog
import java.time.LocalDate

/** 加藥精靈（D38）：一天幾次 → 飯前飯後 → 每次幾顆 → 吃幾天 → 確認。 */
class AddWizardTest {

    private fun entry(id: String) = FakeCatalog.items.first { it.catalogId == id }
    private val amlodipine = entry("M01")      // 降血壓藥，目錄：早餐後 1 顆
    private val nitroglycerin = entry("M25")   // 舌下含片，目錄：需要時才吃
    private val today = LocalDate.of(2026, 11, 23)
    private val routine = RoutineMinutes.DEFAULT

    @Test fun `從目錄選的藥用目錄預設當建議`() {
        val d = AddDraft.start(amlodipine, IdentifiedBy.USER_SELECTED, null, null)
        assertEquals(1, d.markedTimes)
        assertEquals(MealRelation.AFTER, d.markedMeal)
        assertEquals(1.0, d.markedDose, 0.0)
        assertEquals("建議", d.markHint(AddStep.TIMES))
        assertFalse(d.daysOptions.any { d.isMarkedDays(it) }) // 沒有藥袋可以算，不建議天數
        assertFalse(d.fromScan)
    }

    @Test fun `藥袋 QR 的次數和劑量當建議，天數用總量算`() {
        val qr = PrescriptionDto(dosePerTime = 1.0, timesPerDay = 2, slots = listOf(PrescriptionSlot.MORNING, PrescriptionSlot.EVENING))
        val d = AddDraft.start(amlodipine, IdentifiedBy.QR, qr, qrQuantity = 28)
        assertEquals(2, d.markedTimes)
        assertEquals("藥袋上寫的", d.markHint(AddStep.TIMES))
        assertEquals(14, d.suggestedDays) // 28 顆 ÷（1 顆 × 2 次）
        assertTrue(d.isMarkedDays(14))
        assertTrue(d.fromScan)
        assertTrue(d.question(today, routine).startsWith("找到了，是降血壓藥。"))
    }

    @Test fun `一步一步回答到確認`() {
        var d = AddDraft.start(amlodipine, IdentifiedBy.USER_SELECTED, null, null)
        d = d.answerTimes(2); assertEquals(AddStep.MEAL, d.step)
        d = d.answerMeal(MealRelation.BEFORE); assertEquals(AddStep.DOSE, d.step)
        d = d.answerDose(0.5); assertEquals(AddStep.DAYS, d.step)
        d = d.answerDays(7); assertEquals(AddStep.CONFIRM, d.step)

        assertEquals(listOf(Slot.BEFORE_BREAKFAST, Slot.BEFORE_DINNER), d.slots())
        assertEquals("每天早餐前 07:30、晚餐前 18:00", d.whenText(routine))
        assertEquals("吃 7 天，到 11 月 29 日", d.daysText(today))
        assertEquals("請確認：降血壓藥，每天早餐前、晚餐前，每次半顆，吃 7 天，到 11 月 29 日。這樣對嗎？", d.question(today, routine))
        val p = d.prescription()
        assertEquals(PrescriptionDto(dosePerTime = 0.5, timesPerDay = 2, mealRelation = MealRelation.BEFORE, durationDays = 7), p)
    }

    @Test fun `一直吃`() {
        val d = AddDraft.start(amlodipine, IdentifiedBy.USER_SELECTED, null, null)
            .answerTimes(1).answerMeal(MealRelation.AT_BEDTIME).answerDose(1.0).answerDays(null)
        assertEquals(listOf(Slot.BEDTIME), d.slots())
        assertNull(d.prescription().durationDays)
        assertEquals("一直吃，直到醫師說停", d.daysText(today))
        assertTrue(d.isMarkedDays(null))
    }

    @Test fun `需要時才吃不問飯前飯後和天數`() {
        var d = AddDraft.start(nitroglycerin, IdentifiedBy.USER_SELECTED, null, null)
        assertEquals(0, d.markedTimes) // 目錄沒有時段 → 建議「需要時才吃」
        d = d.answerTimes(0); assertEquals(AddStep.DOSE, d.step)
        d = d.answerDose(1.0); assertEquals(AddStep.CONFIRM, d.step)
        assertEquals(emptyList<Slot>(), d.slots())
        assertEquals(PrescriptionDto(dosePerTime = 1.0, timesPerDay = 0), d.prescription())
        assertEquals(AddStep.DOSE, d.back()!!.step)
        assertEquals(AddStep.TIMES, d.back()!!.back()!!.step)
    }

    @Test fun `改了次數，QR 的時段就不帶`() {
        val qr = PrescriptionDto(timesPerDay = 2, slots = listOf(PrescriptionSlot.MORNING, PrescriptionSlot.EVENING))
        val keep = AddDraft.start(amlodipine, IdentifiedBy.QR, qr, null).answerTimes(2)
        assertEquals(qr.slots, keep.prescription().slots)
        val changed = AddDraft.start(amlodipine, IdentifiedBy.QR, qr, null).answerTimes(3)
        assertEquals(emptyList<PrescriptionSlot>(), changed.prescription().slots)
    }

    @Test fun `一天一次才有睡前可以選`() {
        val d = AddDraft.start(amlodipine, IdentifiedBy.USER_SELECTED, null, null)
        assertTrue(MealRelation.AT_BEDTIME in d.mealOptions(1))
        assertEquals(listOf(MealRelation.BEFORE, MealRelation.AFTER), d.mealOptions(2))
        // 選了睡前再改成一天 2 次：睡前不算數，回到建議
        val back = d.answerTimes(1).answerMeal(MealRelation.AT_BEDTIME).restart().answerTimes(2)
        assertEquals(MealRelation.AFTER, back.markedMeal)
    }

    @Test fun `第一步再上一步就離開精靈`() {
        assertNull(AddDraft.start(amlodipine, IdentifiedBy.QR, null, null).back())
    }
}
