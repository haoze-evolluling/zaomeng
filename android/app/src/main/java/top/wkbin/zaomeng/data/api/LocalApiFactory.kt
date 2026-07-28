package top.wkbin.zaomeng.data.api

import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class LocalApiFactory {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun create(baseUrl: String, token: String): ZaomengApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(1, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                val acceptsEventStream = chain.request().url.encodedPath.endsWith("/reply/stream")
                val authenticated = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header(
                        "Accept",
                        if (acceptsEventStream) "text/event-stream" else "application/json",
                    )
                    .build()
                chain.proceed(authenticated)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZaomengApi::class.java)
    }
}
