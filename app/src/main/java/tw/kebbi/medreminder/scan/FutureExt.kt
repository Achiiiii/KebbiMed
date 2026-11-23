package tw.kebbi.medreminder.scan

import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 回傳的 Task 轉成 suspend。
 * 註：`com.google.android.gms.tasks.Task` 只是 ML Kit 內附的函式庫類別，執行時不需要 Google Play 服務。
 */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** CameraX 的 ListenableFuture 轉成 suspend。 */
suspend fun <T> ListenableFuture<T>.await(executor: Executor): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, executor)
    cont.invokeOnCancellation { cancel(false) }
}
