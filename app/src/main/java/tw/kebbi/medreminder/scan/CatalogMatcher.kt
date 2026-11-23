package tw.kebbi.medreminder.scan

import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import java.text.Normalizer

/**
 * 辨識出來的藥名 → 目錄（D6）。**正規化後完全相符才算對到，不做模糊比對**，避免對錯藥。
 *
 * - 比對對象：目錄的學名、商品名、別名（不含「降血壓藥」這種顯示名稱，因為很多藥共用）。
 * - 正規化：全形轉半形、轉小寫、去掉劑量字樣（`40mg`、`5 毫克`）、去掉空白和標點。
 * - 先比整個名稱；對不到時，把名稱依空白、括號、斜線拆開，每一段各自完全比對
 *   （藥袋常寫「Lasix 40mg (Furosemide)」）。
 * - 回傳所有對到的目錄項目：1 個就直接用；多個讓使用者在這幾個裡面選；0 個讓使用者從目錄選（D24）。
 */
object CatalogMatcher {

    private val doseRegex = Regex("""\d+(\.\d+)?\s*(mg|mcg|µg|μg|g|ml|iu|毫克|公克|毫升|微克|國際單位)""", RegexOption.IGNORE_CASE)
    private val dropRegex = Regex("""[\s\-_.,，、/／()（）\[\]【】:：;；'"®™]""")
    private val splitRegex = Regex("""[\s/／()（）\[\]【】,，、;；]+""")

    fun normalize(s: String): String {
        val half = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        return half.replace(doseRegex, "").replace(dropRegex, "")
    }

    private fun keysOf(e: CatalogEntryDto): Set<String> =
        (listOf(e.genericName) + listOfNotNull(e.brandName) + (e.brandName?.split(splitRegex) ?: emptyList()) + e.aliases)
            .map { normalize(it) }.filter { it.length >= 2 }.toSet()

    fun match(name: String?, catalog: List<CatalogEntryDto>): List<CatalogEntryDto> {
        if (name.isNullOrBlank()) return emptyList()
        val index = catalog.map { it to keysOf(it) }
        val whole = normalize(name)
        if (whole.length >= 2) {
            index.filter { (_, keys) -> whole in keys }.map { it.first }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val parts = name.split(splitRegex).map { normalize(it) }.filter { it.length >= 2 }.toSet()
        return index.filter { (_, keys) -> keys.any { it in parts } }.map { it.first }
    }
}
