package tw.kebbi.medreminder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tw.kebbi.medreminder.engine.AlarmScheduler
import tw.kebbi.medreminder.engine.EngineEvent
import tw.kebbi.medreminder.engine.Notifier
import tw.kebbi.medreminder.engine.ReminderEngine
import tw.kebbi.medreminder.sync.CloudSync

/**
 * 唯一的資料來源。畫面、鬧鐘、後台同步都透過這裡讀寫，避免各自為政。
 */
object Repo {
    private lateinit var store: Store
    private lateinit var appContext: Context
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data

    /** 給畫面訂閱：引擎產生的事件（提醒、漏吃…） */
    val events = MutableStateFlow<List<EngineEvent>>(emptyList())

    fun init(context: Context) {
        if (::store.isInitialized) return
        appContext = context.applicationContext
        store = Store(appContext)
        _data.value = store.load()
    }

    val value: AppData get() = _data.value

    @Synchronized
    fun update(reason: String? = null, f: (AppData) -> AppData): AppData {
        val nd = f(_data.value)
        _data.value = nd
        store.save(nd)
        AlarmScheduler.scheduleNext(appContext, nd)
        Notifier.clearTaken(appContext, nd)
        if (reason != null) CloudSync.pushState(nd)
        return nd
    }

    /** 跑一次提醒引擎；回傳這次的事件。 */
    @Synchronized
    fun tick(): List<EngineEvent> {
        val (nd, ev) = ReminderEngine.tick(_data.value)
        if (nd != _data.value) {
            _data.value = nd
            store.save(nd)
            AlarmScheduler.scheduleNext(appContext, nd)
        }
        if (ev.isNotEmpty()) {
            Notifier.onEvents(appContext, nd, ev)
            ev.forEach { CloudSync.logEngineEvent(nd, it) }
            CloudSync.pushState(nd)
            events.value = events.value + ev
        }
        return ev
    }

    fun consumeEvents(): List<EngineEvent> {
        val e = events.value
        events.value = emptyList()
        return e
    }
}
