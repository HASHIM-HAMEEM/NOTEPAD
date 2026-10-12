package com.hashim.notepad.sync

import com.hashim.notepad.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val code: Int, val body: ByteArray) {
    val ok get() = code in 200..299
    val text get() = String(body, Charsets.UTF_8)
}

class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** Minimal blocking HTTP client for Supabase REST/Auth/Storage; no third-party dependencies. */
object SupabaseHttp {
    val baseUrl: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val configured get() = baseUrl.isNotEmpty() && anonKey.isNotEmpty()

    fun request(
        method: String,
        path: String,
        token: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val conn = URL(if (path.startsWith("http")) path else baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Authorization", "Bearer ${token ?: anonKey}")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    fun json(method: String, path: String, token: String?, json: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse =
        request(method, path, token, json?.toByteArray(Charsets.UTF_8), "application/json", headers)

    fun HttpResponse.orThrow(): HttpResponse {
        if (!ok) throw HttpException(code, text.take(300))
        return this
    }
}
