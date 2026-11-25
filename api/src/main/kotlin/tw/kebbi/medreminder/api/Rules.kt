package tw.kebbi.medreminder.api

/**
 * 提醒規則的常數。
 *
 * 凱比和後端都要判定狀態（D5）：凱比判定給長者看，後端依事件重算當作正式紀錄。
 * 兩邊一定要用這同一份數字，結果才會一致。T ＝ occurrence 的 `scheduledAt`。
 *
 * [SNOOZE_MIN]、[MAX_SNOOZES]、[OVERDUE_AFTER_MIN] 是**預設值**：照服員可以在後台改，
 * 由 B1 排程的 `reminderSettings`（[tw.kebbi.medreminder.api.dto.ReminderSettingsDto]）帶給凱比；沒帶就用這裡的值。
 * 其他常數固定。
 */
object Rules {
    /** T−30 分鐘起可以按「吃了」，算準時（D15）。 */
    const val EARLY_TAKE_MIN = 30

    /** T＋15 再提醒一次（畫面＋語音，D16）。「等一下」期間不觸發（D11）；逾時時間比這個早就不會觸發。 */
    const val REMIND_AGAIN_MIN = 15

    /** 預設：T＋30 還沒吃 → 逾時未服（D23）。之後在晚吃期限前吃，記成晚吃。後台可改（`overdueAfterMinutes`）。 */
    const val OVERDUE_AFTER_MIN = 30

    /** 預設：按「等一下」後幾分鐘再提醒；實際到期 ＝ min(按下時間＋10 分, 逾時時間)（D11）。後台可改（`snoozeMinutes`）。 */
    const val SNOOZE_MIN = 10

    /** 預設：同一包最多可以按幾次「等一下」。後台可改（`maxSnoozes`）。 */
    const val MAX_SNOOZES = 2

    /** 離逾時不到這麼多分鐘時，不顯示「等一下」（D11）。 */
    const val SNOOZE_HIDE_BELOW_MIN = 2

    /** 後端判定逾時後，多等幾分鐘才發 LINE，讓晚一點送到的事件趕上（第 2.6 節，預設）。 */
    const val LINE_NOTIFY_DELAY_MIN = 2

    /** App 連續不在前景這麼久，後端通知照服員（第 7.4 節，預設）。 */
    const val APP_NOT_FOREGROUND_ALERT_MIN = 30

    /** 凱比多久沒有心跳就算離線（D14）。背景心跳是 15 分鐘一次，所以給兩倍再多一點。 */
    const val DEVICE_OFFLINE_AFTER_MIN = 35

    /** App 在前景時的心跳間隔（D28）。 */
    const val HEARTBEAT_FOREGROUND_SEC = 60

    /** App 在背景時的心跳間隔（D28；Android 的 WorkManager 週期工作最短就是 15 分鐘）。 */
    const val HEARTBEAT_BACKGROUND_MIN = 15

    /** 排程回傳今天往前幾天、往後幾天的 occurrence（第 6.3 節 B1）。 */
    const val SCHEDULE_DAYS_BEHIND = 1
    const val SCHEDULE_DAYS_AHEAD = 7

    /** 一次最多送幾筆服藥事件（第 6.3 節 C1）。 */
    const val MAX_EVENTS_PER_BATCH = 100

    /** 凱比時鐘和後端差超過幾分鐘要警告（第 2.3 節）。 */
    const val CLOCK_SKEW_WARN_MIN = 2

    /** 配對碼有效時間，以及等待綁定時查詢的間隔（第 6.3 節 A2）。 */
    const val PAIRING_CODE_TTL_MIN = 10
    const val PAIRING_POLL_SEC = 15

    /** 照片上限（第 6.3 節 R1）。 */
    const val UPLOAD_MAX_BYTES = 20L * 1024 * 1024

    /** 作息順序檢查（CR-20）：午餐要比早餐晚、晚餐要比午餐晚至少 2 小時；睡覺要比晚餐晚至少 1 小時。 */
    const val MIN_MEAL_GAP_MIN = 120
    const val MIN_SLEEP_AFTER_DINNER_MIN = 60

    /** 作息時間卡的可調範圍（一天中的第幾分鐘）。 */
    val RANGE_BREAKFAST = 4 * 60..10 * 60 + 30
    val RANGE_LUNCH = 10 * 60..14 * 60 + 30
    val RANGE_DINNER = 15 * 60..21 * 60
    val RANGE_SLEEP = 18 * 60..23 * 60 + 45
}
