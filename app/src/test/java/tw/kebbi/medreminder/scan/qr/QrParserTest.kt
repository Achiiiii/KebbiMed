package tw.kebbi.medreminder.scan.qr

import tw.kebbi.medreminder.scan.TimeSlot
import tw.kebbi.medreminder.scan.UsageMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QrParserTest {

    // task.md 的四段範例（格式 B 第 3、4 欄為假資料）
    private val sampleA1 = "Utraphen 錠劑 325/37.5 mg 每次1粒 口服 每6小時使用1次 臨床用途為止痛 (但無消炎) " +
        "副作用為便秘，嗜睡，盜汗，噁心，暈眩，呼吸抑制 (呼吸變慢，變淺)。本品可能增加跌倒風險，請注意。"
    private val sampleA2 = "藥包 Topamax 低 膠囊 25 mg 每次1粒 口服 睡前使用 臨床用途為抗癲癇劑、治療偏頭痛 " +
        "副作用為暈眩、頭痛、嗜睡、震顫、疲勞、運動失調等"
    private val sampleB1 = "20260417;UTR02;TEST000001;Z000000000;56"
    private val sampleB2 = "20260423;TOP02;TEST000001;Z000000000;28"

    // ---- 格式 A ----

    @Test
    fun a1_every6Hours() {
        val a = QrParser.parseA(sampleA1)!!
        assertEquals("Utraphen 錠劑 325/37.5 mg", a.drugName)
        assertEquals(1, a.dosePerTime)
        assertEquals(UsageMethod.ORAL, a.method)
        assertEquals(4, a.timesPerDay) // 24 / 6
        assertTrue(a.slots.isEmpty())
    }

    @Test
    fun a2_bedtime() {
        val a = QrParser.parseA(sampleA2)!!
        assertEquals("藥包 Topamax 低 膠囊 25 mg", a.drugName)
        assertEquals(1, a.dosePerTime)
        assertEquals(UsageMethod.ORAL, a.method)
        assertEquals(1, a.timesPerDay)
        assertEquals(setOf(TimeSlot.BEDTIME), a.slots)
    }

    @Test
    fun a_dailyNTimes() {
        val a = QrParser.parseA("Amlodipine 5 mg 每次2粒 口服 每日2次 飯後")!!
        assertEquals(2, a.dosePerTime)
        assertEquals(2, a.timesPerDay)
    }

    @Test
    fun a_frequencyNotMatched_notPrefilled() {
        val a = QrParser.parseA("某藥 10 mg 每次1粒 口服 需要時使用")!!
        assertEquals("某藥 10 mg", a.drugName)
        assertNull(a.timesPerDay)
        assertTrue(a.slots.isEmpty())
    }

    @Test
    fun a_every5Hours_notDivisible_notPrefilled() {
        assertNull(QrParser.parseA("某藥 每次1粒 口服 每5小時使用1次")!!.timesPerDay)
    }

    @Test
    fun a_every4Hours_outOfOptions_notPrefilled() {
        // 24/4 = 6 次，精靈只有 1～4 次
        assertNull(QrParser.parseA("某藥 每次1粒 口服 每4小時使用1次")!!.timesPerDay)
    }

    @Test
    fun a_topical() {
        assertEquals(UsageMethod.TOPICAL, QrParser.parseA("某藥膏 每次1次 外用 每日2次")!!.method)
    }

    @Test
    fun a_noMethod_notPrefilled() {
        assertNull(QrParser.parseA("某藥 每次1粒 每日2次")!!.method)
    }

    @Test
    fun a_invalid() {
        assertNull(QrParser.parseA(""))
        assertNull(QrParser.parseA(null))
        assertNull(QrParser.parseA("每次1粒 口服"))
        assertNull(QrParser.parseA("沒有關鍵字的文字"))
    }

    // ---- 格式 B ----

    @Test
    fun b1() {
        assertEquals(QrBInfo(LocalDate.of(2026, 4, 17), "UTR02", 56), QrParser.parseB(sampleB1))
    }

    @Test
    fun b2() {
        assertEquals(QrBInfo(LocalDate.of(2026, 4, 23), "TOP02", 28), QrParser.parseB(sampleB2))
    }

    @Test
    fun b_dropsChartNumberAndIdNumber() {
        val b = QrParser.parseB(sampleB1)!!
        val dump = b.toString()
        assertFalse(dump.contains("TEST000001"))
        assertFalse(dump.contains("Z000000000"))
    }

    @Test
    fun b_wrongFieldCount() {
        assertNull(QrParser.parseB("20260417;UTR02;TEST000001;56"))
        assertNull(QrParser.parseB("20260417;UTR02;TEST000001;Z000000000;56;X"))
    }

    @Test
    fun b_invalidValues() {
        assertNull(QrParser.parseB(""))
        assertNull(QrParser.parseB("20260231;UTR02;X;Y;56")) // 不存在的日期
        assertNull(QrParser.parseB("2026041;UTR02;X;Y;56"))
        assertNull(QrParser.parseB("20260417;;X;Y;56")) // 沒有藥碼
        assertNull(QrParser.parseB("20260417;UTR02;X;Y;abc")) // 發藥量不是數字
        assertNull(QrParser.parseB("20260417;UTR02;X;Y;0"))
    }

    // ---- 整張圖 ----

    @Test
    fun parseAll_aAndB() {
        val r = QrParser.parseAll(listOf(sampleA1, sampleB1, "https://example.com/x"))
        assertNotNull(r.a)
        assertNotNull(r.b)
        assertEquals(1, r.aCount)
        assertEquals(1, r.bCount)
        assertEquals(1, r.unparsedCount)
        assertTrue(r.anyParsed)
    }

    @Test
    fun parseAll_multipleA_usesFirst() {
        val r = QrParser.parseAll(listOf(sampleA2, sampleA1))
        assertEquals(2, r.aCount)
        assertEquals("藥包 Topamax 低 膠囊 25 mg", r.a!!.drugName)
    }

    @Test
    fun parseAll_nothing() {
        val r = QrParser.parseAll(listOf("https://example.com", "20260417;UTR02;X;56"))
        assertFalse(r.anyParsed)
        assertEquals(2, r.unparsedCount)
        assertFalse(QrParser.parseAll(emptyList()).anyParsed)
    }
}
