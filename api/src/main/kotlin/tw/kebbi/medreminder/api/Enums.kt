package tw.kebbi.medreminder.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一天中吃藥的時段（第 6.3 節 B1 的 `slot`）。順序就是時間先後。
 * 時刻由作息（[RoutineDto]）推算，規則見 [timeOf]；`CUSTOM` 是指定時刻（D7）。
 */
@Serializable
enum class Slot(val label: String) {
    WAKE("起床"),
    BEFORE_BREAKFAST("早餐前"),
    AFTER_BREAKFAST("早餐後"),
    BEFORE_LUNCH("午餐前"),
    AFTER_LUNCH("午餐後"),
    BEFORE_DINNER("晚餐前"),
    AFTER_DINNER("晚餐後"),
    BEDTIME("睡前"),
    CUSTOM("指定時間");

    /**
     * 這個時段在一天中的第幾分鐘。規則：起床＝早餐前 60 分、餐前＝餐前 30 分、餐後＝吃飯時間、睡前＝睡覺時間。
     * `CUSTOM` 沒有固定時刻，回傳 null。
     */
    fun timeOf(breakfast: Int, lunch: Int, dinner: Int, sleep: Int): Int? = when (this) {
        WAKE -> breakfast - 60
        BEFORE_BREAKFAST -> breakfast - 30
        AFTER_BREAKFAST -> breakfast
        BEFORE_LUNCH -> lunch - 30
        AFTER_LUNCH -> lunch
        BEFORE_DINNER -> dinner - 30
        AFTER_DINNER -> dinner
        BEDTIME -> sleep
        CUSTOM -> null
    }

    companion object {
        /** 有固定時刻的 8 個時段。 */
        val FIXED: List<Slot> = entries.filter { it != CUSTOM }
    }
}

/** 每週一次的藥在星期幾。 */
@Serializable
enum class Weekday(val label: String, val isoDayOfWeek: Int) {
    MON("星期一", 1), TUE("星期二", 2), WED("星期三", 3), THU("星期四", 4),
    FRI("星期五", 5), SAT("星期六", 6), SUN("星期日", 7);

    companion object {
        fun ofIso(day: Int): Weekday = entries.first { it.isoDayOfWeek == day }
    }
}

/**
 * 藥和吃飯的關係（目錄 E1 的 `mealRelation`），用來把 QR 的「早、午、晚」對到餐前或餐後（D26）。
 * `ON_WAKE`：起床空腹吃（例如甲狀腺藥）；`AT_BEDTIME`：睡前吃；`ANY`：沒有規定。
 */
@Serializable
enum class MealRelation { BEFORE, AFTER, ON_WAKE, AT_BEDTIME, ANY }

/** 藥袋 QR 格式 A 的時段（只有早、午、晚、睡前，沒有餐前餐後）。 */
@Serializable
enum class PrescriptionSlot { MORNING, NOON, EVENING, BEDTIME }
