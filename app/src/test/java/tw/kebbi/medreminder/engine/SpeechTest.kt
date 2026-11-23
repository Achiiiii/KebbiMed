package tw.kebbi.medreminder.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.data.OccItem
import tw.kebbi.medreminder.data.Occurrence

/** 到點提醒的語音：前景請長者照畫面吃，背景看不到畫面時念劑量。 */
class SpeechTest {

    private fun occ(vararg items: OccItem) = Occurrence(
        id = "o1", date = "2026-11-23", slot = Slot.AFTER_BREAKFAST, slotLabel = "早餐後",
        scheduledAt = ApiConventions.parseTime("2026-11-23T08:00:00+08:00"),
        lateDeadline = ApiConventions.parseTime("2026-11-23T20:00:00+08:00"),
        items = items.toList(),
    )

    @Test fun `前景照畫面吃`() {
        val o = occ(OccItem("m1", "利尿劑", "1 顆"))
        assertEquals("早餐後 08:00 的利尿劑吃了嗎？請照下面吃。", Speech.remind(o))
    }

    @Test fun `背景只有一種藥時念劑量`() {
        val o = occ(OccItem("m1", "利尿劑", "1 顆"))
        assertEquals("早餐後 08:00 的利尿劑吃了嗎？請吃 1 顆。", Speech.remind(o, onScreen = false))
    }

    @Test fun `背景好幾種藥時每一種都念`() {
        val o = occ(OccItem("m1", "利尿劑", "1 顆"), OccItem("m2", "降血壓藥", "半顆"))
        assertEquals(
            "早餐後 08:00 的利尿劑、降血壓藥吃了嗎？請吃利尿劑 1 顆、降血壓藥 半顆。還有 1 包也要吃。",
            Speech.remind(o, others = 1, onScreen = false),
        )
    }
}
