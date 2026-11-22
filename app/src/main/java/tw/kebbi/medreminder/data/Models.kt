package tw.kebbi.medreminder.data

/** 一天中吃藥的 8 個時段，順序即時間先後。 */
enum class Slot(val label: String) {
    WAKE("起床"), PRE_B("早餐前"), POST_B("早餐後"),
    PRE_L("午餐前"), POST_L("午餐後"),
    PRE_D("晚餐前"), POST_D("晚餐後"),
    BED("睡前");

    companion object {
        fun fromLabel(s: String): Slot? = entries.firstOrNull { it.label == s }
    }
}

/** 作息：四個時間（一天中的第幾分鐘）。各時段時間由這四個推算。 */
data class Schedule(
    val breakfast: Int = 8 * 60,
    val lunch: Int = 12 * 60 + 30,
    val dinner: Int = 18 * 60 + 30,
    val sleep: Int = 22 * 60,
) {
    /** 時段時間的規則（與 Figma、網頁版原型一致）。 */
    fun timeOf(slot: Slot): Int = when (slot) {
        Slot.WAKE -> breakfast - 60
        Slot.PRE_B -> breakfast - 30
        Slot.POST_B -> breakfast
        Slot.PRE_L -> lunch - 30
        Slot.POST_L -> lunch
        Slot.PRE_D -> dinner - 30
        Slot.POST_D -> dinner
        Slot.BED -> sleep
    }

    companion object {
        val EARLY = Schedule(5 * 60 + 30, 11 * 60, 17 * 60, 20 * 60 + 30)
        val NORMAL = Schedule()
        val LATE = Schedule(9 * 60, 13 * 60, 19 * 60, 22 * 60)

        /** 時間卡可調範圍（與 Figma 時間卡元件相同） */
        val RANGE_BREAKFAST = 4 * 60..10 * 60 + 30
        val RANGE_LUNCH = 10 * 60..14 * 60 + 30
        val RANGE_DINNER = 15 * 60..21 * 60
        val RANGE_SLEEP = 18 * 60..23 * 60 + 45
    }
}

/** 藥品資料庫的一筆。 */
data class Med(
    val id: String,
    val name: String,        // 凱比顯示名稱，例：利尿劑
    val slots: List<Slot>,
    val dose: String,        // 每次的量，例：1 顆、半顆
    val generic: String,     // 學名，例：Furosemide
    val category: String,    // 用途分類，例：心衰竭
    val brand: String,       // 常見商品名
    val weeklyOn: Int? = null, // 每週一次的藥：Calendar.DAY_OF_WEEK（星期一 = 2）
)

enum class DoseStatus { PENDING, REMINDING, SNOOZED, TAKEN, MISSED, SKIP }

/** 今天某個時段的服藥狀態。時間都用「一天中的第幾分鐘」。 */
data class SlotState(
    val status: DoseStatus = DoseStatus.PENDING,
    val secondReminder: Boolean = false,
    val snoozeUntil: Int? = null,
    val remindedAt: String? = null,
    val takenAt: String? = null,
    val note: String = "",
    /** 已正式判定漏服：狀態是 MISSED（逾時未服），而且過了補吃期限。 */
    val lapsed: Boolean = false,
)

/** App 的全部狀態。存在本機，並同步到後台。 */
data class AppData(
    val paired: Boolean = true,   // 不再手動綁定：由後台 robots/{裝置編號} 指定長者
    val stationId: String = "德高",
    val elderId: String = "KB-0013",
    val elderName: String = "林阿公",
    val setupDone: Boolean = true,  // 不再有強制的站點設定流程；設定都在「設定」裡
    val meds: List<String> = emptyList(),
    val schedule: Schedule = Schedule.NORMAL,
    val day: String? = null,
    val slots: Map<Slot, SlotState> = emptyMap(),
    val flowers: Int = 0,
    /** 最後一次藥單或作息變更的時間（毫秒），用來和後台比較誰比較新 */
    val configUpdatedAt: Long = 0L,
    /** 照服員從後台改了藥，凱比要跟長者說一聲 */
    val pendingRemoteNotice: String? = null,
)
