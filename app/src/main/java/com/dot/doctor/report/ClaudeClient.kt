package com.dot.doctor.report

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Claude Messages API 호출. 이 앱이 휴대폰 밖과 통신하는 유일한 곳.
 * 주소는 고정이며 HTTPS 만 쓴다. 키·요약 내용은 로그로 남기지 않는다.
 */
object ClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val HOST = "api.anthropic.com"

    class ApiException(message: String) : Exception(message)

    suspend fun ask(apiKey: String, model: String, system: String, user: String): String = withContext(Dispatchers.IO) {
        val url = URL(ENDPOINT)
        check(url.protocol == "https" && url.host == HOST)
        val conn = url.openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.instanceFollowRedirects = false   // 다른 곳으로 넘겨지지 않게
            conn.useCaches = false
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")

            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 2500)
                .put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // 오류 메시지만 보여 준다 (보낸 내용은 넣지 않음)
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw ApiException(
                    when (code) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> "API 키가 맞지 않아요"
                        429 -> "요청이 많아 잠시 뒤에 다시 해 주세요"
                        else -> "Claude 응답 오류 ($code)" + (msg?.let { ": $it" } ?: "")
                    }
                )
            }
            val content = JSONObject(text).getJSONArray("content")
            buildString {
                for (i in 0 until content.length()) {
                    val c = content.getJSONObject(i)
                    if (c.optString("type") == "text") append(c.getString("text"))
                }
            }.trim().ifEmpty { throw ApiException("빈 응답을 받았어요") }
        } finally {
            conn.disconnect()
        }
    }
}
