package tw.kebbi.medreminder.scan

import tw.kebbi.medreminder.scan.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyDictionaryTest {

    private fun ocr(text: String) = FrequencyDictionary.match(text, FrequencyDictionary.OCR_RULES)
    private fun qr(text: String) = FrequencyDictionary.match(text, FrequencyDictionary.QR_RULES)

    @Test
    fun qrRules_onlySpecPatterns() {
        assertEquals(4, qr("每6小時使用1次").timesPerDay)
        assertEquals(3, qr("每日3次").timesPerDay)
        assertEquals(FrequencyHint(1, setOf(TimeSlot.BEDTIME)), qr("睡前使用"))
        // QR 規則不含處方縮寫與中文數字
        assertTrue(qr("TID 飯後").isEmpty)
        assertTrue(qr("每日三次").isEmpty)
    }

    @Test
    fun ocr_chineseNumbers() {
        assertEquals(3, ocr("用法：每日三次 飯後").timesPerDay)
        assertEquals(2, ocr("一天2次").timesPerDay)
        assertEquals(2, ocr("每日兩次").timesPerDay)
    }

    @Test
    fun ocr_abbreviations() {
        assertEquals(3, ocr("1# TID PC").timesPerDay)
        assertEquals(2, ocr("bid").timesPerDay)
        assertEquals(4, ocr("Q6H").timesPerDay)
        assertEquals(setOf(TimeSlot.BEDTIME), ocr("1# HS").slots)
        // 單字裡的字母不算（例如 TIDE、BIDEN）
        assertTrue(ocr("TIDE BIDEN").isEmpty)
    }

    @Test
    fun ocr_mealsAndSlots() {
        val h = ocr("三餐飯後及睡前")
        assertEquals(3, h.timesPerDay)
        assertEquals(setOf(TimeSlot.MORNING, TimeSlot.NOON, TimeSlot.EVENING, TimeSlot.BEDTIME), h.slots)
        assertEquals(setOf(TimeSlot.MORNING, TimeSlot.EVENING), ocr("早晚飯後").slots)
    }

    @Test
    fun ocr_outOfRange_notPrefilled() {
        assertNull(ocr("每日6次").timesPerDay)
        assertNull(ocr("每5小時").timesPerDay)
    }
}
