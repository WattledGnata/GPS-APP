// @IgnoreFormatCheck
package com.blazepush.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 构造 [LivetimingApi]：OkHttp（Bearer token interceptor）+ Retrofit + Gson。
 *
 * token / baseUrl 默认取 [BuildConfig]（local.properties 注入，缺值占位 PLACEHOLDER_TOKEN）；
 * 单测可显式传入覆盖（配 MockWebServer baseUrl）。
 *
 * **不手动加 `Accept-Encoding: gzip`**：OkHttp 默认自动带 gzip 并**透明解压**；手动设该头会
 * 关掉自动解压、需自己处理压缩流（doc §1 "HTTP client 通常默认带 + 自动解压"）。
 */
object LivetimingClient {
    fun create(
        baseUrl: String = BuildConfig.LIVETIMING_BASE_URL,
        token: String = BuildConfig.LIVETIMING_TOKEN,
    ): LivetimingApi {
        val authInterceptor = Interceptor { chain ->
            val authed = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
            chain.proceed(authed)
        }
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LivetimingApi::class.java)
    }
}
