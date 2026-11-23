package tw.kebbi.medreminder.scan

import tw.kebbi.medreminder.scan.TimeSlot
import tw.kebbi.medreminder.scan.UsageMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 測試資料都是模擬的藥袋 OCR 文字（虛構）。 */
class OcrKeywordExtractorTest {

    @Test
    fun typicalBag_labelsWithFullWidthColon() {
        val text = """
            測試醫院 藥品明細
            姓名：王小明　病歷號：TEST000001
            藥品名稱：Amlodipine 5mg 錠
            用法用量：每次1粒　每日2次　早晚飯後
            數量：28
            口服
        """.trimIndent()
        val e = OcrKeywordExtractor.extract(text)
        assertEquals("Amlodipine 5mg 錠", e.drugName)
        assertEquals(1, e.dosePerTime)
        assertEquals(UsageMethod.ORAL, e.method)
        assertEquals(2, e.frequency.timesPerDay)
        assertEquals(setOf(TimeSlot.MORNING, TimeSlot.EVENING), e.frequency.slots)
    }

    @Test
    fun nameOnNextLine_andChineseNumbers() {
        val text = "藥品名稱\n脈優錠 5 毫克\n用法\n每日三次 每次一粒 飯後"
        val e = OcrKeywordExtractor.extract(text)
        assertEquals("脈優錠 5 毫克", e.drugName)
        assertEquals(3, e.frequency.timesPerDay)
        assertEquals(1, e.dosePerTime)
    }

    @Test
    fun nameStopsAtOtherField_onSameLine() {
        val e = OcrKeywordExtractor.extract("藥名: Metformin 500mg 用法: BID 飯後")
        assertEquals("Metformin 500mg", e.drugName)
        assertEquals(2, e.frequency.timesPerDay)
    }

    @Test
    fun bedtime() {
        val e = OcrKeywordExtractor.extract("品名：安眠藥A\n用法：睡前 每次1顆")
        assertEquals("安眠藥A", e.drugName)
        assertEquals(setOf(TimeSlot.BEDTIME), e.frequency.slots)
        assertNull(e.frequency.timesPerDay)
    }

    @Test
    fun topical() {
        val e = OcrKeywordExtractor.extract("藥品名稱：痠痛藥膏\n外用 每日3次 塗抹患部")
        assertEquals(UsageMethod.TOPICAL, e.method)
        assertEquals(3, e.frequency.timesPerDay)
    }

    @Test
    fun fullWidthDigitsNormalized() {
        val e = OcrKeywordExtractor.extract("藥品名稱：測試錠\n用法用量：每日３次")
        assertEquals(3, e.frequency.timesPerDay)
    }

    @Test
    fun noKeywords_nothingPrefilled() {
        val e = OcrKeywordExtractor.extract("祝您早日康復\n請按時服藥\n電話 02-1234-5678")
        assertTrue(e.isEmpty)
        assertTrue(OcrKeywordExtractor.extract("").isEmpty)
        assertTrue(OcrKeywordExtractor.extract(null).isEmpty)
    }

    @Test
    fun frequencyOnlyFromUsageLines() {
        // 「三餐」出現在非用法行（例如衛教文字）不應該被當成用法
        val e = OcrKeywordExtractor.extract("藥品名稱：測試錠\n注意：三餐均衡飲食")
        assertNull(e.frequency.timesPerDay)
    }

    @Test
    fun labelOnly_noName() {
        assertNull(OcrKeywordExtractor.extract("藥品名稱：\n用法：每日2次").drugName)
    }

    @Test
    fun orElse_fillsMissingFromSecondEngine() {
        val mlkit = OcrKeywordExtractor.extract("用法：每日2次")
        val tess = OcrKeywordExtractor.extract("藥品名稱：測試錠\n用法：每日3次")
        val merged = mlkit.orElse(tess)
        assertEquals("測試錠", merged.drugName)
        assertEquals(2, merged.frequency.timesPerDay) // ML Kit 優先
    }
}
