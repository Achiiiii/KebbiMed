package tw.kebbi.medreminder.scan.qr

import tw.kebbi.medreminder.scan.TimeSlot
import tw.kebbi.medreminder.scan.UsageMethod
import tw.kebbi.medreminder.scan.FrequencyDictionary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** 格式 A（藥品說明，純文字）解析結果。 */
data class QrAInfo(
    /** 「每次」之前的文字。 */
    val drugName: String,
    val dosePerTime: Int?,
    val method: UsageMethod?,
    val timesPerDay: Int?,
    val slots: Set<TimeSlot>,
    /** 完整 A 文字（只有藥品資訊，無個資），供詳細頁顯示臨床用途／副作用。 */
    val text: String,
) {
    override fun toString(): String =
        "QrAInfo(nameLen=${drugName.length}, dose=$dosePerTime, method=$method, times=$timesPerDay, slots=$slots)"
}

/**
 * 格式 B（分號 5 欄）解析結果。
 * 個資規則：第 3、4 欄（病歷號、身分證字號）在解析當下丟棄，這裡沒有對應欄位。
 */
data class QrBInfo(
    val dispenseDate: LocalDate,
    val drugCode: String,
    val quantity: Int,
)

/** 一張圖所有 QR 的解析結果。 */
data class QrParseResult(
    val a: QrAInfo?,
    val b: QrBInfo?,
    /** 解析成功的 A 有幾個（一張照片預設只有一個藥袋；>1 時使用第一個，並提示）。 */
    val aCount: Int,
    val bCount: Int,
    /** 解析失敗或其他格式（例如網址）的數量。 */
    val unparsedCount: Int,
) {
    val anyParsed: Boolean get() = a != null || b != null
}

/** QR 內容解析（純 Kotlin）。比對不到就不預填，不要猜。 */
object QrParser {

    private val dosePattern = Regex("每次(\\d+)")
    private val bDate: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)

    fun parseA(raw: String?): QrAInfo? {
        val text = raw?.trim().orEmpty()
        val idx = text.indexOf("每次")
        if (idx <= 0) return null
        val name = text.substring(0, idx).trim()
        if (name.isEmpty()) return null
        val freq = FrequencyDictionary.match(text, FrequencyDictionary.QR_RULES)
        val method = when {
            text.contains("口服") -> UsageMethod.ORAL
            text.contains("外用") -> UsageMethod.TOPICAL
            else -> null
        }
        return QrAInfo(
            drugName = name,
            dosePerTime = dosePattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
            method = method,
            timesPerDay = freq.timesPerDay,
            slots = freq.slots,
            text = text,
        )
    }

    /** 只取第 0、1、4 欄；第 2、3 欄不指派給任何變數。 */
    fun parseB(raw: String?): QrBInfo? {
        val text = raw?.trim().orEmpty()
        val fields = text.split(';')
        if (fields.size != QrClassifier.FORMAT_B_FIELD_COUNT) return null
        val date = try {
            LocalDate.parse(fields[0].trim(), bDate)
        } catch (_: DateTimeParseException) {
            return null
        }
        val code = fields[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val quantity = fields[4].trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return QrBInfo(dispenseDate = date, drugCode = code, quantity = quantity)
    }

    /** 解析一張圖上所有 QR 文字。A、B 各取第一個解析成功的。 */
    fun parseAll(texts: List<String>): QrParseResult {
        var a: QrAInfo? = null
        var b: QrBInfo? = null
        var aCount = 0
        var bCount = 0
        var unparsed = 0
        texts.distinct().forEach { t ->
            when (QrClassifier.classify(t)) {
                QrKind.A -> parseA(t)?.let { aCount++; if (a == null) a = it } ?: unparsed++
                QrKind.B -> parseB(t)?.let { bCount++; if (b == null) b = it } ?: unparsed++
                QrKind.OTHER -> unparsed++
            }
        }
        return QrParseResult(a, b, aCount, bCount, unparsed)
    }
}
