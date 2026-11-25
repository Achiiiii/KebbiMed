package tw.kebbi.medreminder.engine

import tw.kebbi.medreminder.data.Occurrence

/** 凱比要說的話。畫面和背景語音（CR-40）共用同一份文字。 */
object Speech {
    /**
     * 到點提醒。[onScreen]：畫面上有這包藥的清單（App 在前景），請長者照著畫面吃；
     * App 在背景時長者看不到畫面，改念每一種藥的劑量。
     */
    fun remind(o: Occurrence, others: Int = 0, onScreen: Boolean = true): String =
        "${o.doseText()}吃了嗎？" + (if (onScreen) "請照下面吃。" else doses(o)) +
            if (others > 0) "還有 $others 包也要吃。" else ""

    /** 「請吃 1 顆。」；一包有好幾種藥時「請吃利尿劑 1 顆、降血壓藥 半顆。」 */
    fun doses(o: Occurrence): String = when (o.items.size) {
        0 -> ""
        1 -> "請吃 ${o.items[0].doseText}。"
        else -> "請吃" + o.items.joinToString("、") { "${it.name} ${it.doseText}" } + "。"
    }

    fun remindAgain(o: Occurrence): String = "${o.doseText()}還沒吃喔。現在吃也可以，吃完請按「吃了」。"

    fun overdue(o: Occurrence, online: Boolean): String =
        "${o.doseText(withTime = false)}還沒吃，已經過了 ${duration(o.settings.overdueAfterMinutes)}。" +
            (if (online) "照服員也會收到通知。" else "網路斷了，需要的話請打電話給照服員。") + "現在吃也可以。"

    /** 「30 分鐘」「2 小時」「1 小時 30 分鐘」（逾時時間由後台設定，可能超過 1 小時）。 */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes 分鐘"
        minutes % 60 == 0 -> "${minutes / 60} 小時"
        else -> "${minutes / 60} 小時 ${minutes % 60} 分鐘"
    }

    fun stopped(list: List<Occurrence>): String =
        list.joinToString("、") { it.doseText(withTime = false) } + "，這個藥已經停了，不用吃。"

    fun snoozed(minutes: Int): String = "好，$minutes 分鐘後再提醒您。"

    /**
     * 念完畫面上方的文字後，告訴長者可以按哪些按鈕。
     * 凱比的語音遇到「：」「、」不太會停，按鈕名稱會黏在一起，所以一律用「，」隔開，最後一個前面加「或」：
     * 「可以按，今天的藥，或，明天的藥。」「可以按，返回，清空藥單，或，離開 App。」
     */
    fun options(labels: List<String>): String {
        val opts = labels.distinct()
        return when {
            opts.isEmpty() -> ""
            opts.size == 1 -> "請按「${opts[0]}」。"
            opts.size <= 4 -> "可以按，" + opts.dropLast(1).joinToString("，") + "，或，" + opts.last() + "。"
            else -> "請選一個。"
        }
    }

    const val TAKEN_BY_NOTIFICATION = "好，記下來了。"
    const val TOO_LATE = "已經過了可以晚吃的時間。這次就不要吃了，下次照常吃，不要一次吃兩份喔。"
}
