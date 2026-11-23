package tw.kebbi.medreminder.api

import kotlinx.serialization.json.Json

/**
 * 凱比和後端交換 JSON 的設定。
 * - `ignoreUnknownKeys`：後端之後多加欄位，舊版凱比不會壞掉。
 * - `explicitNulls = false`：值是 null 的欄位不輸出。
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
