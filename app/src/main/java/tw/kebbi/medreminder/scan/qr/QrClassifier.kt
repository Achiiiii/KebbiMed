package tw.kebbi.medreminder.scan.qr

/** QR 內容的粗分類（M1）。完整欄位解析在 M2 的 QrParser。 */
enum class QrKind(val label: String) {
    /** 格式 A：純文字藥品說明（右側大 QR），含「每次」。 */
    A("A 藥品說明"),

    /** 格式 B：分號分隔 5 欄（藥碼旁小 QR）。 */
    B("B 調劑資料"),

    /** 其他（網址等），視為無法解析。 */
    OTHER("其他"),
}

/**
 * 純 Kotlin，不依賴 android.*。
 * 個資規則：只回傳種類，不保留、不輸出原始字串。
 */
object QrClassifier {

    private val formatBPrefix = Regex("^\\d{8};")

    fun classify(raw: String?): QrKind {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return QrKind.OTHER
        if (formatBPrefix.containsMatchIn(s) && s.split(';').size == FORMAT_B_FIELD_COUNT) return QrKind.B
        val idx = s.indexOf("每次")
        if (idx > 0 && s.substring(0, idx).isNotBlank()) return QrKind.A
        return QrKind.OTHER
    }

    /**
     * 判斷字串是否像是編碼錯誤（例如 Big5 被當成 UTF-8 或 ISO-8859-1 解碼）。
     * 用於 M1 檢查醫院 QR 的中文編碼。
     */
    fun looksGarbled(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains('�')) return true
        // Big5／UTF-8 位元組被當成 ISO-8859-1 解碼時，會出現大量 U+0080..U+00FF 的字元
        val latin1Supplement = s.count { it in '\u0080'..'ÿ' }
        return latin1Supplement >= 3 && latin1Supplement * 5 >= s.length
    }

    const val FORMAT_B_FIELD_COUNT = 5
}
