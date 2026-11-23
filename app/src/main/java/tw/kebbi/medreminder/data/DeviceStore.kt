package tw.kebbi.medreminder.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import tw.kebbi.medreminder.api.ApiJson

/** 裝置設定和後端給的資料，整包存成 JSON 放在 SharedPreferences（資料量很小）。 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("kebbi_device", Context.MODE_PRIVATE)
    private val app = context.applicationContext

    @SuppressLint("HardwareIds")
    fun load(): DeviceData {
        val d = prefs.getString(KEY, null)?.let { runCatching { ApiJson.decodeFromString(DeviceData.serializer(), it) }.getOrNull() }
            ?: DeviceData()
        if (d.hardwareId.isNotBlank()) return d
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return d.copy(hardwareId = androidId).also { save(it) }
    }

    fun save(d: DeviceData) {
        prefs.edit().putString(KEY, ApiJson.encodeToString(DeviceData.serializer(), d)).commit()
    }

    companion object { private const val KEY = "device_v1" }
}
