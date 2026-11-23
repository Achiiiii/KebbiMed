package tw.kebbi.medreminder.sync

import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import tw.kebbi.medreminder.BuildConfig
import tw.kebbi.medreminder.api.ApiConventions
import tw.kebbi.medreminder.api.ApiJson
import tw.kebbi.medreminder.api.KebbiApi
import tw.kebbi.medreminder.data.Repo
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 建立 [KebbiApi]：
 * - `BuildConfig.API_BASE_URL` 是空的 → 假後端（第三方後端完成前）。
 * - 有網址 → Retrofit 版本（介面的註記就是 HTTP 方法和路徑，不用另外寫串接程式）。
 */
object ApiFactory {
    fun create(context: Context): Pair<KebbiApi, FakeBackend?> {
        val url = BuildConfig.API_BASE_URL
        if (url.isBlank()) {
            val fake = FakeBackend(File(context.filesDir, "fake_backend.json"))
            return fake to fake
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // 照片原圖可能好幾 MB
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(if (url.endsWith("/")) url else "$url/")
            .client(client)
            .addConverterFactory(ApiJson.asConverterFactory("application/json; charset=utf-8".toMediaType()))
            .build()
        return retrofit.create(KebbiApi::class.java) to null
    }
}

/** 幫凱比的每個請求加上 `Authorization: Bearer <token>`（登記、換 token 除外）。 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val path = req.url.encodedPath
        val token = Repo.value.device.accessToken
        if (token == null || path.endsWith("devices/register") || path.endsWith("devices/token/refresh")) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header(ApiConventions.HEADER_AUTHORIZATION, "Bearer $token").build())
    }
}
