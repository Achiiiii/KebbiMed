package tw.kebbi.medreminder.api.reference

import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.dto.RoutineDto

/** 作息，用「一天中的第幾分鐘」表示。各時段的時刻由這四個推算（[Slot.timeOf]）。 */
data class RoutineMinutes(
    val breakfast: Int,
    val lunch: Int,
    val dinner: Int,
    val sleep: Int,
) {
    fun timeOf(slot: Slot): Int? = slot.timeOf(breakfast, lunch, dinner, sleep)

    fun toDto() = RoutineDto(
        breakfast = ApiConventions.formatHm(breakfast),
        lunch = ApiConventions.formatHm(lunch),
        dinner = ApiConventions.formatHm(dinner),
        sleep = ApiConventions.formatHm(sleep),
    )

    /**
     * 作息順序檢查（CR-20）。不合理時回傳給長者聽的原因（後端回 422 `INVALID_ROUTINE` 的 `detail`），合理時回傳 null。
     * 凱比在送出前也先檢查一次。
     */
    fun validate(): String? {
        fun hm(m: Int) = ApiConventions.formatHm(m)
        fun outOf(name: String, v: Int, r: IntRange) =
            if (v !in r) "${name}時間要在 ${hm(r.first)} 到 ${hm(r.last)} 之間。" else null
        return outOf("早餐", breakfast, Rules.RANGE_BREAKFAST)
            ?: outOf("午餐", lunch, Rules.RANGE_LUNCH)
            ?: outOf("晚餐", dinner, Rules.RANGE_DINNER)
            ?: outOf("睡覺", sleep, Rules.RANGE_SLEEP)
            ?: if (lunch - breakfast < Rules.MIN_MEAL_GAP_MIN) "午餐要比早餐晚至少 ${Rules.MIN_MEAL_GAP_MIN / 60} 小時。" else null
            ?: if (dinner - lunch < Rules.MIN_MEAL_GAP_MIN) "晚餐要比午餐晚至少 ${Rules.MIN_MEAL_GAP_MIN / 60} 小時。" else null
            ?: if (sleep - dinner < Rules.MIN_SLEEP_AFTER_DINNER_MIN) "睡覺時間要比晚餐晚至少 ${Rules.MIN_SLEEP_AFTER_DINNER_MIN / 60} 小時。" else null
    }

    companion object {
        /** 預設作息：早餐 08:00、午餐 12:30、晚餐 18:30、睡覺 22:00。 */
        val DEFAULT = RoutineMinutes(8 * 60, 12 * 60 + 30, 18 * 60 + 30, 22 * 60)

        fun from(dto: RoutineDto) = RoutineMinutes(
            breakfast = ApiConventions.parseHm(dto.breakfast),
            lunch = ApiConventions.parseHm(dto.lunch),
            dinner = ApiConventions.parseHm(dto.dinner),
            sleep = ApiConventions.parseHm(dto.sleep),
        )
    }
}
