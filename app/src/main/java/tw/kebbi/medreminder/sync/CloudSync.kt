package tw.kebbi.medreminder.sync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import tw.kebbi.medreminder.data.AppData
import tw.kebbi.medreminder.data.Clock
import tw.kebbi.medreminder.data.MedDb
import tw.kebbi.medreminder.data.Repo
import tw.kebbi.medreminder.data.Store
import tw.kebbi.medreminder.engine.EngineEvent
import tw.kebbi.medreminder.engine.ReminderEngine

/**
 * 後台同步（Firebase Firestore）。
 *
 * 資料結構（後台網頁、LINE 拉娃 APP 讀同一份）：
 *   elders/{長者編號}                 長者資料、藥單、作息、今天的服藥狀態
 *   elders/{長者編號}/events/{自動}   服藥事件紀錄（提醒、吃了、漏吃、改藥…）
 *
 * 照服員在後台改藥單或作息時，把 updatedBy 設成 "caregiver"，凱比收到後會更新並告訴長者。
 * Firestore 本身有離線快取：斷網時先存在凱比，連上網會自動補傳。
 */
object CloudSync {
    private const val TAG = "CloudSync"
    private var listener: ListenerRegistration? = null
    private var robotListener: ListenerRegistration? = null

    /** 這台凱比的裝置編號（Android ID）。照服員在後台用它把凱比指定給長者。 */
    var deviceId: String = "unknown"
        private set
    private val db: FirebaseFirestore? by lazy {
        try { FirebaseFirestore.getInstance() } catch (e: Exception) { Log.w(TAG, "Firebase 未設定", e); null }
    }

    /** 匿名登入（後台規則要求登入才能讀寫），再去後台查這台凱比負責哪位長者。 */
    @SuppressLint("HardwareIds")
    fun start(context: Context) {
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        try {
            val auth = FirebaseAuth.getInstance()
            val go = { listenRobot(); listen(Repo.value.elderId) }
            if (auth.currentUser == null) {
                auth.signInAnonymously()
                    .addOnSuccessListener { go() }
                    .addOnFailureListener { Log.w(TAG, "匿名登入失敗：請到 Firebase 主控台開啟 Anonymous 登入", it) }
            } else go()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase 未設定", e)
        }
    }

    private fun elderDoc(elderId: String) = db?.collection("elders")?.document(elderId)

    /** 把目前狀態寫上後台。 */
    fun pushState(d: AppData) {
        if (!d.paired) return
        val doc = elderDoc(d.elderId) ?: return
        val today = d.slots.entries.associate { (s, st) ->
            s.label to mapOf(
                "time" to Clock.fmt(d.schedule.timeOf(s)),
                "status" to st.status.name.lowercase(),
                "takenAt" to st.takenAt,
                "meds" to ReminderEngine.medsIn(d, s).map { it.id },
            )
        }
        val map = mapOf(
            "name" to d.elderName,
            "stationId" to d.stationId,
            "meds" to d.meds,
            "schedule" to mapOf(
                "breakfast" to Clock.fmt(d.schedule.breakfast), "lunch" to Clock.fmt(d.schedule.lunch),
                "dinner" to Clock.fmt(d.schedule.dinner), "sleep" to Clock.fmt(d.schedule.sleep),
            ),
            "configUpdatedAt" to d.configUpdatedAt,
            "today" to mapOf(
                "date" to d.day,
                "taken" to ReminderEngine.takenCount(d),
                "total" to ReminderEngine.counted(d).size,
                "slots" to today,
            ),
            "flowers" to d.flowers,
            "device" to mapOf("lastSeen" to FieldValue.serverTimestamp(), "deviceTime" to Clock.nowMs()),
        )
        doc.set(map, SetOptions.merge())
    }

    /** 記一筆事件。type 例：dose.taken、dose.missed、caregiver.notify、med.add… */
    fun logEvent(d: AppData, type: String, text: String, extra: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, "[$type] $text $extra")
        if (!d.paired) return
        val col = elderDoc(d.elderId)?.collection("events") ?: return
        col.add(
            mapOf(
                "type" to type, "text" to text, "source" to "kebbi",
                "at" to FieldValue.serverTimestamp(), "deviceTime" to Clock.nowMs(),
                "stationId" to d.stationId,
            ) + extra
        )
    }

    fun logEngineEvent(d: AppData, e: EngineEvent) {
        when (e) {
            is EngineEvent.Fire -> logEvent(d, "reminder.fire", "時間到：${e.slot.label}" + if (e.again) "（稍後再提醒）" else "",
                mapOf("slot" to e.slot.label, "meds" to ReminderEngine.medsIn(d, e.slot).map { it.id }))
            is EngineEvent.Repeat -> logEvent(d, "reminder.repeat", "${e.slot.label} 還沒吃，再提醒一次", mapOf("slot" to e.slot.label))
            is EngineEvent.Missed -> {
                // 逾時未服：時間到 30 分鐘還沒吃，還可以晚吃
                logEvent(d, "dose.overdue", "${e.slot.label} 逾時未服（超過 ${tw.kebbi.medreminder.engine.Rules.MISS_AFTER_MIN} 分鐘還沒吃）", mapOf("slot" to e.slot.label,
                    "lateCutoff" to tw.kebbi.medreminder.data.Clock.fmt(kotlin.math.floor(tw.kebbi.medreminder.engine.ReminderEngine.lateCutoffMin(d, e.slot)).toInt())))
                // 後台交給拉娃 LINE 機器人，推播給長者手機、照服員
                logEvent(d, "caregiver.notify", "${d.elderName} ${tw.kebbi.medreminder.engine.ReminderEngine.doseText(d, e.slot, withTime = true)}還沒吃（逾時未服）", mapOf("slot" to e.slot.label, "level" to "warning", "kind" to "overdue"))
            }
            is EngineEvent.Lapsed -> {
                // 正式漏服：過了晚吃期限還是沒吃
                logEvent(d, "dose.missed", "${e.slot.label} 漏服（過了晚吃期限）", mapOf("slot" to e.slot.label))
                logEvent(d, "caregiver.notify", "${d.elderName} ${tw.kebbi.medreminder.engine.ReminderEngine.doseText(d, e.slot, withTime = true)}漏服了", mapOf("slot" to e.slot.label, "level" to "warning", "kind" to "missed"))
            }
            is EngineEvent.Rollover -> logEvent(d, "day.rollover", "換日：${e.newDay}", mapOf("yesterdayAllDone" to e.yesterdayAllDone))
        }
    }

    /**
     * robots/{裝置編號}：後台指定這台凱比給哪位長者。
     *   { elderId:"KB-0013", elderName:"林阿公", stationId:"德高" }
     * 第一次開機時自動建立這筆資料（先用預設長者），照服員之後在後台改 elderId 即可換人。
     */
    private fun listenRobot() {
        robotListener?.remove()
        val doc = db?.collection("robots")?.document(deviceId) ?: return
        val d0 = Repo.value
        doc.set(mapOf("model" to android.os.Build.MODEL, "lastSeen" to FieldValue.serverTimestamp(), "app" to "KebbiMed"), SetOptions.merge())
        robotListener = doc.addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            val elderId = snap.getString("elderId")
            if (elderId.isNullOrBlank()) {
                // 後台還沒指定：先寫入目前（預設）的長者，讓照服員在後台看得到這台凱比
                doc.set(mapOf("elderId" to d0.elderId, "elderName" to d0.elderName, "stationId" to d0.stationId), SetOptions.merge())
                return@addSnapshotListener
            }
            val cur = Repo.value
            val name = snap.getString("elderName") ?: cur.elderName
            val station = snap.getString("stationId") ?: cur.stationId
            if (elderId != cur.elderId || name != cur.elderName || station != cur.stationId) {
                Repo.update(reason = "robot.assign") { it.copy(elderId = elderId, elderName = name, stationId = station, paired = true) }
                listen(elderId)
                logEvent(Repo.value, "device.assign", "機器人（$deviceId）指定給 $name（$elderId）", mapOf("deviceId" to deviceId))
            }
        }
    }

    /** 監聽照服員從後台做的修改。 */
    fun listen(elderId: String) {
        listener?.remove()
        val doc = elderDoc(elderId) ?: return
        listener = doc.addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            if (snap.getString("updatedBy") != "caregiver") return@addSnapshotListener
            val remoteAt = snap.getLong("configUpdatedAt") ?: 0L
            val local = Repo.value
            if (remoteAt <= local.configUpdatedAt) return@addSnapshotListener
            @Suppress("UNCHECKED_CAST")
            val meds = (snap.get("meds") as? List<String>)?.filter { MedDb.get(it) != null } ?: local.meds
            @Suppress("UNCHECKED_CAST")
            val sch = (snap.get("schedule") as? Map<String, Any?>)?.let { m ->
                Store.scheduleFromJson(org.json.JSONObject(m.mapValues { it.value?.toString() }))
            } ?: local.schedule
            val added = meds - local.meds.toSet()
            val removed = local.meds - meds.toSet()
            val notice = buildString {
                append("站點照服員幫您更新了藥。")
                if (added.isNotEmpty()) append("新增：" + added.mapNotNull { MedDb.get(it)?.name }.joinToString("、") + "。")
                if (removed.isNotEmpty()) append("拿掉：" + removed.mapNotNull { MedDb.get(it)?.name }.joinToString("、") + "。")
                if (sch != local.schedule) append("吃藥時間也改了。")
            }
            Repo.update(reason = null) { d ->
                ReminderEngine.syncSlots(d.copy(meds = meds, schedule = sch, configUpdatedAt = remoteAt, pendingRemoteNotice = notice))
            }
            logEvent(Repo.value, "config.applied", "機器人已套用照服員的更新", mapOf("added" to added, "removed" to removed))
            // 回寫一次，讓後台知道已套用（updatedBy 改回 kebbi）
            doc.set(mapOf("updatedBy" to "kebbi"), SetOptions.merge())
        }
    }

    /** 換綁長者時呼叫 */
    fun rebind(elderId: String) = listen(elderId)
}
