package tw.kebbi.medreminder.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 把 AppData 存成 JSON 放在 SharedPreferences。資料量很小，不需要資料庫。 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("kebbi_med", Context.MODE_PRIVATE)

    fun load(): AppData = try {
        prefs.getString(KEY, null)?.let { fromJson(JSONObject(it)) } ?: AppData()
    } catch (e: Exception) {
        AppData()
    }

    fun save(d: AppData) {
        prefs.edit().putString(KEY, toJson(d).toString()).apply()
    }

    companion object {
        private const val KEY = "app_data_v1"

        fun scheduleToJson(s: Schedule) = JSONObject()
            .put("breakfast", Clock.fmt(s.breakfast)).put("lunch", Clock.fmt(s.lunch))
            .put("dinner", Clock.fmt(s.dinner)).put("sleep", Clock.fmt(s.sleep))

        fun scheduleFromJson(o: JSONObject?): Schedule {
            if (o == null) return Schedule.NORMAL
            fun m(k: String, d: Int) = o.optString(k, "").let { if (it.isBlank()) d else Clock.parse(it) }
            return Schedule(m("breakfast", 480), m("lunch", 750), m("dinner", 1110), m("sleep", 1320))
        }

        fun slotStateToJson(s: SlotState) = JSONObject()
            .put("status", s.status.name).put("second", s.secondReminder)
            .put("snoozeUntil", s.snoozeUntil ?: JSONObject.NULL)
            .put("remindedAt", s.remindedAt ?: JSONObject.NULL)
            .put("takenAt", s.takenAt ?: JSONObject.NULL)
            .put("note", s.note)
            .put("lapsed", s.lapsed)

        fun slotStateFromJson(o: JSONObject) = SlotState(
            status = runCatching { DoseStatus.valueOf(o.getString("status")) }.getOrDefault(DoseStatus.PENDING),
            secondReminder = o.optBoolean("second", false),
            snoozeUntil = if (o.isNull("snoozeUntil")) null else o.optInt("snoozeUntil"),
            remindedAt = if (o.isNull("remindedAt")) null else o.optString("remindedAt"),
            takenAt = if (o.isNull("takenAt")) null else o.optString("takenAt"),
            note = o.optString("note", ""),
            lapsed = o.optBoolean("lapsed", false),
        )

        fun toJson(d: AppData): JSONObject {
            val slots = JSONObject()
            d.slots.forEach { (k, v) -> slots.put(k.label, slotStateToJson(v)) }
            return JSONObject()
                .put("paired", d.paired).put("stationId", d.stationId).put("elderId", d.elderId)
                .put("elderName", d.elderName).put("setupDone", d.setupDone)
                .put("meds", JSONArray(d.meds)).put("schedule", scheduleToJson(d.schedule))
                .put("day", d.day ?: JSONObject.NULL).put("slots", slots).put("flowers", d.flowers)
                .put("configUpdatedAt", d.configUpdatedAt)
                .put("pendingRemoteNotice", d.pendingRemoteNotice ?: JSONObject.NULL)
        }

        fun fromJson(o: JSONObject): AppData {
            val meds = o.optJSONArray("meds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val slots = mutableMapOf<Slot, SlotState>()
            o.optJSONObject("slots")?.let { so ->
                so.keys().forEach { k -> Slot.fromLabel(k)?.let { slots[it] = slotStateFromJson(so.getJSONObject(k)) } }
            }
            return AppData(
                paired = true,
                stationId = o.optString("stationId", "德高"),
                elderId = o.optString("elderId", "KB-0013"),
                elderName = o.optString("elderName", "林阿公"),
                setupDone = true,
                meds = meds.filter { MedDb.get(it) != null },
                schedule = scheduleFromJson(o.optJSONObject("schedule")),
                day = if (o.isNull("day")) null else o.optString("day"),
                slots = slots,
                flowers = o.optInt("flowers", 0),
                configUpdatedAt = o.optLong("configUpdatedAt", 0L),
                pendingRemoteNotice = if (o.isNull("pendingRemoteNotice")) null else o.optString("pendingRemoteNotice"),
            )
        }
    }
}
