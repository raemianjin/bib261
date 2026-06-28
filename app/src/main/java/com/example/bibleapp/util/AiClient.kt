package com.example.bibleapp.util

import android.content.Context
import com.example.bibleapp.data.AiStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 제공자 추상화 AI 클라이언트. 표준 라이브러리(HttpURLConnection)+org.json 만 사용.
 *  - 현재: Google Gemini (generateContent)
 *  - 추후: Groq (OpenAI 호환 chat/completions) — 키만 넣으면 동작하도록 미리 구현
 * 모든 호출은 동기(블로킹)이므로 반드시 워커 스레드에서 호출할 것.
 */
object AiClient {

    /** 프롬프트를 보내고 결과 텍스트를 반환. 실패 시 예외(메시지 포함). */
    fun complete(ctx: Context, userText: String): String {
        val provider = AiStore.getProvider(ctx)
        val key = AiStore.getKey(ctx, provider)
        if (key.isBlank())
            throw IllegalStateException("API 키가 없습니다. 설정 > AI 설정에서 키를 입력/저장하세요.")
        val model = AiStore.getModel(ctx)
        return when (provider) {
            "groq" -> callGroq(key, model, userText)
            else   -> callGemini(key, model, userText)
        }
    }

    /** 키 유효성 점검용 짧은 호출. 성공 시 응답 일부 반환, 실패 시 예외. */
    fun test(ctx: Context): String = complete(ctx, "한 단어로 'OK'라고만 답해줘.")

    /** 저장 전, 입력한 특정 키로 직접 검증. */
    fun testKey(provider: String, model: String, key: String): String {
        if (key.isBlank()) throw IllegalStateException("키가 비어 있습니다")
        val msg = "한 단어로 'OK'라고만 답해줘."
        return if (provider == "groq") callGroq(key, model, msg) else callGemini(key, model, msg)
    }

    /**
     * 이미지(JPEG base64) + 프롬프트를 Gemini 비전 모델에 보내 텍스트 응답을 받는다.
     * 주보 스크린샷에서 '가장 크고 가운데 있는 성경 구절'을 인식해 요약하는 데 사용.
     * 비전은 Gemini 키가 필요(없으면 예외).
     */
    fun completeWithImage(ctx: Context, prompt: String, jpegBase64: String): String {
        val provider = com.example.bibleapp.data.AiStore.getBulletinProvider(ctx)
        val model = com.example.bibleapp.data.AiStore.getBulletinModel(ctx)
        return if (provider == "groq") {
            val key = com.example.bibleapp.data.AiStore.getKey(ctx, "groq")
            if (key.isBlank())
                throw IllegalStateException("주보 요약(Groq)에는 Groq API 키가 필요합니다. 설정 > AI 설정에서 입력하세요.")
            callGroqVision(key, model, prompt, jpegBase64)
        } else {
            val key = com.example.bibleapp.data.AiStore.getKey(ctx, "gemini")
            if (key.isBlank())
                throw IllegalStateException("주보 요약(Gemini)에는 Gemini API 키가 필요합니다. 설정 > AI 설정에서 입력하세요.")
            callGeminiVision(key, model, prompt, jpegBase64)
        }
    }

    private fun callGeminiVision(key: String, model: String, prompt: String, b64: String): String {
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))
        parts.put(JSONObject().put("inline_data", JSONObject().apply {
            put("mime_type", "image/jpeg"); put("data", b64)
        }))
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().put("temperature", 0.4))
        }
        val resp = httpPost(endpoint, body.toString(), null)
        val json = JSONObject(resp)
        val cands = json.optJSONArray("candidates")
            ?: throw IllegalStateException(json.optJSONObject("error")?.optString("message") ?: "응답이 비었습니다")
        if (cands.length() == 0) throw IllegalStateException("응답이 비었습니다 (이미지를 인식하지 못했을 수 있습니다)")
        val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("응답 형식 오류")
        val sb = StringBuilder()
        for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
        return sb.toString().trim().ifBlank { "(빈 응답)" }
    }

    private fun callGroqVision(key: String, model: String, prompt: String, b64: String): String {
        val endpoint = "https://api.groq.com/openai/v1/chat/completions"
        val content = JSONArray()
        content.put(JSONObject().apply { put("type", "text"); put("text", prompt) })
        content.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        })
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", content)
            }))
            put("temperature", 0.4)
        }
        val resp = httpPost(endpoint, body.toString(), "Bearer $key")
        val json = JSONObject(resp)
        val choices = json.optJSONArray("choices")
            ?: throw IllegalStateException(json.optJSONObject("error")?.optString("message") ?: "응답 오류")
        if (choices.length() == 0) throw IllegalStateException("응답이 비었습니다")
        return choices.getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    private fun callGemini(key: String, model: String, userText: String): String {
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", userText)))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.7))
        }
        val resp = httpPost(endpoint, body.toString(), null)
        val json = JSONObject(resp)
        val cands = json.optJSONArray("candidates")
            ?: throw IllegalStateException(json.optJSONObject("error")?.optString("message") ?: "응답이 비었습니다")
        if (cands.length() == 0) throw IllegalStateException("응답이 비었습니다 (안전 필터로 차단되었을 수 있습니다)")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("응답 형식 오류")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim().ifBlank { "(빈 응답)" }
    }

    private fun callGroq(key: String, model: String, userText: String): String {
        val endpoint = "https://api.groq.com/openai/v1/chat/completions"
        val useModel = if (model.startsWith("gemini")) "llama-3.3-70b-versatile" else model
        val body = JSONObject().apply {
            put("model", useModel)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", userText)
            }))
            put("temperature", 0.7)
        }
        val resp = httpPost(endpoint, body.toString(), "Bearer $key")
        val json = JSONObject(resp)
        val choices = json.optJSONArray("choices")
            ?: throw IllegalStateException(json.optJSONObject("error")?.optString("message") ?: "응답 오류")
        if (choices.length() == 0) throw IllegalStateException("응답이 비었습니다")
        return choices.getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    private fun httpPost(urlStr: String, body: String, auth: String?): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (auth != null) conn.setRequestProperty("Authorization", auth)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                throw IllegalStateException("HTTP $code · ${msg ?: text.take(180)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
