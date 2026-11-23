package tw.kebbi.medreminder.api.dto

import kotlinx.serialization.Serializable
import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.Weekday

// ---------- E1 GET /medications/catalog ----------

/**
 * 藥品目錄的一項（D6）。藥單和凱比送出的申請都只引用 `catalogId`。
 * 凱比用 `genericName`、`brandName`、`aliases` 比對辨識出來的藥名：正規化後完全相符才算對到，不做模糊比對。
 */
@Serializable
data class CatalogEntryDto(
    val catalogId: String,
    val displayName: String,
    val genericName: String,
    val brandName: String? = null,
    /** 用途分類，例如「心衰竭」。凱比選藥畫面依這個分組。 */
    val category: String,
    /** 學名、商品名、中文名等別名。 */
    val aliases: List<String> = emptyList(),
    val defaultDose: DoseDto,
    /** 空的表示「需要時才吃」，不會定時提醒。 */
    val defaultSlots: List<Slot> = emptyList(),
    val mealRelation: MealRelation = MealRelation.ANY,
    val weeklyOn: Weekday? = null,
)

@Serializable
data class CatalogDto(
    val version: Int,
    val etag: String,
    /** 分類的顯示順序。 */
    val categories: List<String> = emptyList(),
    val items: List<CatalogEntryDto>,
    val serverTime: String,
)
