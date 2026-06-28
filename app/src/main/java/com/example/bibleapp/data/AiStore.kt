package com.example.bibleapp.data

import android.content.Context

/**
 * AI 연동 설정 저장소 (제공자/모델/키/프롬프트).
 * 키는 제공자별로 따로 저장 → 추후 Groq 등 추가 대비.
 */
object AiStore {
    private const val PREF = "ai_store"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    const val DEF_VERSE_PROMPT =
        "다음 성경 구절을 묵상할 수 있도록, 핵심 의미와 오늘의 적용점을 3가지로 친절하게 설명해줘. 한국어로, 따뜻한 어조로 답해줘."
    const val DEF_BULLETIN_PROMPT =
        "다음은 이번 주 주보의 말씀 구절 목록입니다. 구절들을 주제별로 묶어 정리하고, 전체 흐름을 한 문단으로 요약해줘. 한국어로 답해줘."
    private const val DEF_MODEL = "gemini-2.0-flash"

    /** 모델 셀렉트바용 목록 (제공자별) */
    val GEMINI_MODELS = listOf(
        "gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite",
        "gemini-1.5-flash", "gemini-1.5-flash-8b", "gemini-1.5-pro"
    )
    val GROQ_MODELS = listOf(
        "llama-3.3-70b-versatile", "llama-3.1-8b-instant",
        "meta-llama/llama-4-scout-17b-16e-instruct", "gemma2-9b-it"
    )
    fun modelsFor(provider: String): List<String> =
        if (provider == "groq") GROQ_MODELS else GEMINI_MODELS

    // 비전(이미지) 가능 모델 — 주보 요약(이미지 인식)에 사용
    val GEMINI_VISION = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")
    val GROQ_VISION = listOf(
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "meta-llama/llama-4-maverick-17b-128e-instruct"
    )
    fun visionModelsFor(provider: String): List<String> =
        if (provider == "groq") GROQ_VISION else GEMINI_VISION

    // 주보 요약 기능이 사용할 AI(별도 지정) — 기본 Gemini
    fun getBulletinProvider(ctx: Context): String = p(ctx).getString("bulletin_provider", "gemini") ?: "gemini"
    fun setBulletinProvider(ctx: Context, v: String) = p(ctx).edit().putString("bulletin_provider", v).apply()
    fun getBulletinModel(ctx: Context): String = p(ctx).getString("bulletin_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
    fun setBulletinModel(ctx: Context, v: String) = p(ctx).edit().putString("bulletin_model", v.ifBlank { "gemini-2.0-flash" }).apply()

    /** AI 기능 사용 여부 (마스터 스위치) — 꺼져 있으면 구절 메뉴 등에서 AI 숨김 */
    fun isAiEnabled(ctx: Context): Boolean = p(ctx).getBoolean("ai_enabled", false)
    fun setAiEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("ai_enabled", on).apply()

    fun getProvider(ctx: Context): String = p(ctx).getString("provider", "gemini") ?: "gemini"
    fun setProvider(ctx: Context, v: String) = p(ctx).edit().putString("provider", v).apply()

    fun getModel(ctx: Context): String = p(ctx).getString("model", DEF_MODEL) ?: DEF_MODEL
    fun setModel(ctx: Context, v: String) = p(ctx).edit().putString("model", v.ifBlank { DEF_MODEL }).apply()

    fun getKey(ctx: Context, provider: String = getProvider(ctx)): String =
        p(ctx).getString("key_$provider", "") ?: ""
    fun setKey(ctx: Context, provider: String, v: String) =
        p(ctx).edit().putString("key_$provider", v.trim()).apply()

    fun getVersePrompt(ctx: Context): String =
        p(ctx).getString("verse_prompt", DEF_VERSE_PROMPT) ?: DEF_VERSE_PROMPT
    fun setVersePrompt(ctx: Context, v: String) =
        p(ctx).edit().putString("verse_prompt", v.ifBlank { DEF_VERSE_PROMPT }).apply()

    fun getBulletinPrompt(ctx: Context): String =
        p(ctx).getString("bulletin_prompt", DEF_BULLETIN_PROMPT) ?: DEF_BULLETIN_PROMPT
    fun setBulletinPrompt(ctx: Context, v: String) =
        p(ctx).edit().putString("bulletin_prompt", v.ifBlank { DEF_BULLETIN_PROMPT }).apply()

    /** 키를 앞 4글자만 남기고 *로 가린 표시용 문자열 */
    fun maskedKey(ctx: Context, provider: String = getProvider(ctx)): String {
        val k = getKey(ctx, provider)
        if (k.isBlank()) return "(미설정)"
        val head = k.take(4)
        return head + "*".repeat((k.length - 4).coerceIn(4, 16))
    }

    fun providerLabel(provider: String): String = when (provider) {
        "groq" -> "Groq"
        else   -> "Google Gemini"
    }
}
