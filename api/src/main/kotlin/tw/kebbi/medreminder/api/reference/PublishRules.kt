package tw.kebbi.medreminder.api.reference

import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.Rules
import tw.kebbi.medreminder.api.dto.OccurrenceDto

/**
 * 發佈新排程版本時，新舊 occurrence 怎麼合併（生效規則）。參考實作。
 *
 * - 已經開始的（`scheduledAt` ≤ 生效時間）維持上一版的樣子，不受新版本影響。
 *   例外：停藥、清空藥單（[removedMedicationIds]）時，還在進行中、還沒吃的那包要拿掉這個藥；
 *   拿掉後沒有藥了就整包刪除，凱比會說「這個藥已經停了，不用吃」。
 * - 還沒開始的照新版本走；新版本沒有的就刪除。
 * - 生效前的時段不產生新的 occurrence，也不算漏服（D13）。例如今天中午加的藥，今天早上的時段不會出現。
 * - 改作息（[routineChanged]）時：今天還沒開始的時段改到新時間；新時間已經過了的，改成「現在」提醒，避免今天那包被跳過。
 * - 照服員選「明天起」時，生效時間是明天 00:00，所以今天全部維持原狀。
 */
object PublishRules {

    fun compose(
        previous: List<OccurrenceDto>,
        fresh: List<OccurrenceDto>,
        effectiveFrom: Long,
        now: Long,
        removedMedicationIds: Set<String> = emptySet(),
        takenOccurrenceIds: Set<String> = emptySet(),
        routineChanged: Boolean = false,
    ): List<OccurrenceDto> {
        fun at(o: OccurrenceDto) = ApiConventions.parseTime(o.scheduledAt)
        fun deadline(o: OccurrenceDto) = ApiConventions.parseTime(o.lateDeadline)

        val result = linkedMapOf<String, OccurrenceDto>()

        // 1. 已經開始的：維持上一版
        for (p in previous.filter { at(it) <= effectiveFrom }) {
            val inProgress = p.occurrenceId !in takenOccurrenceIds && now < deadline(p)
            if (removedMedicationIds.isNotEmpty() && inProgress) {
                val items = p.items.filter { it.medicationId !in removedMedicationIds }
                if (items.isNotEmpty()) result[p.occurrenceId] = p.copy(items = items)
            } else {
                result[p.occurrenceId] = p
            }
        }

        // 2. 還沒開始的：照新版本
        for (f in fresh.filter { at(it) > effectiveFrom }) {
            if (f.occurrenceId !in result) result[f.occurrenceId] = f
        }

        // 3. 改作息：原本還沒開始、新時間已經過了 → 現在提醒
        if (routineChanged) {
            val prevById = previous.associateBy { it.occurrenceId }
            for (f in fresh.filter { at(it) <= effectiveFrom && it.occurrenceId !in result }) {
                val p = prevById[f.occurrenceId] ?: continue
                if (at(p) <= effectiveFrom) continue
                val newAt = effectiveFrom
                val minDeadline = newAt + (Rules.OVERDUE_AFTER_MIN + 30) * 60_000L
                result[f.occurrenceId] = f.copy(
                    scheduledAt = ApiConventions.formatTime(newAt),
                    lateDeadline = ApiConventions.formatTime(maxOf(deadline(f), minDeadline)),
                )
            }
        }

        return result.values.sortedBy { at(it) }
    }
}
