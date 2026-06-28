package com.example.bibleapp.util

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import java.util.Locale

/**
 * 표준 android.speech.tts.TextToSpeech 래퍼 (APP_TTS_GUIDE 원칙).
 *  - 합성은 기기 내장 엔진 → 권한 0개, 오프라인 동작.
 *  - (a) 언어 데이터 확인 → 없으면 해당 언어만 스킵하고 안내(무음 방지).
 *  - (b) 네트워크 불요(오프라인) 음성 우선 선택.
 *  - 여성/남성: 음성 이름 휴리스틱 + 피치 차이로 어느 엔진에서도 구분되게.
 *  - 한 번에 한 구절씩 말하고 onDone 콜백으로 다음 구절을 잇는다(QUEUE_FLUSH).
 */
class TtsManager(private val activity: Activity) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var seq = 0
    private var pendingDone: (() -> Unit)? = null
    private var currentId: String = ""
    private val warned = HashSet<String>()

    fun init(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(activity) { status ->
            ready = (status == TextToSpeech.SUCCESS)
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { fire(id) }
                    @Deprecated("deprecated in API level 21")
                    override fun onError(id: String?) { fire(id) }
                    override fun onError(id: String?, errorCode: Int) { fire(id) }
                })
            }
            activity.runOnUiThread { onReady?.invoke() }
        }
    }

    private fun fire(id: String?) {
        if (id != null && id == currentId) {
            val d = pendingDone
            pendingDone = null
            activity.runOnUiThread { d?.invoke() }
        }
    }

    fun isReady() = ready

    /** lang: "ko" | "en", gender: "female" | "male" */
    fun speak(text: String, lang: String, gender: String, rate: Float, onDone: () -> Unit) {
        val engine = tts
        if (!ready || engine == null || text.isBlank()) { onDone(); return }
        val locale = if (lang == "ko") Locale.KOREAN else Locale.ENGLISH

        // (a) 언어 데이터 확인
        val avail = try { engine.isLanguageAvailable(locale) }
                    catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (warned.add(lang)) {
                val ko = (lang == "ko")
                Toast.makeText(activity,
                    (if (ko) "한국어" else "영어") + " 음성이 없어 건너뜁니다. 설정 > 접근성/언어 > TTS에서 음성을 설치하세요.",
                    Toast.LENGTH_LONG).show()
            }
            onDone(); return   // 해당 언어만 스킵 — 낭독은 계속
        }
        engine.language = locale

        // (b) 오프라인 음성 우선 + 성별 선택
        pickVoice(engine, locale, gender)
        engine.setPitch(if (gender == "male") 0.82f else 1.06f)
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))

        val id = "u" + (++seq)
        currentId = id
        pendingDone = onDone
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) { pendingDone = null; onDone() }
    }

    private fun pickVoice(engine: TextToSpeech, locale: Locale, gender: String) {
        try {
            val all: Set<Voice> = engine.voices ?: return
            val cands = all.filter {
                it.locale != null &&
                it.locale.language == locale.language &&
                !it.isNetworkConnectionRequired          // 오프라인 음성만
            }
            if (cands.isEmpty()) return
            fun has(v: Voice, kw: String) = v.name.contains(kw, ignoreCase = true)
            fun isFemale(v: Voice) = has(v, "female") || has(v, "woman") || has(v, "yuna") || has(v, "sora")
            fun isMale(v: Voice)   = (has(v, "male") && !has(v, "female")) || has(v, "man")
            val pick = if (gender == "male") cands.firstOrNull { isMale(it) }
                       else                  cands.firstOrNull { isFemale(it) }
            val chosen = pick ?: cands.maxByOrNull { it.quality }
            if (chosen != null) engine.voice = chosen
        } catch (e: Exception) { /* 음성 열거 실패 시 기본 음성 유지 */ }
    }

    fun stop() {
        pendingDone = null
        currentId = ""
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        tts = null
        ready = false
    }
}
