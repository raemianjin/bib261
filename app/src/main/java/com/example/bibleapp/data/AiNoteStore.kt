package com.example.bibleapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** AI 메모 1건 (여러 구절이면 verseFrom=첫 구절, verseTo=끝 구절). key는 첫 구절 기준. */
data class AiNote(
    val bookIndex: Int,
    val bookNameKo: String,
    val chapter: Int,        // 1-based
    val verseFrom: Int,      // 1-based
    val verseTo: Int,        // 1-based
    val text: String,
    val updatedAt: Long
) {
    val key: String get() = "$bookIndex-$chapter-$verseFrom"
    val reference: String
        get() = if (verseTo > verseFrom) "$bookNameKo $chapter:$verseFrom-$verseTo"
                else "$bookNameKo $chapter:$verseFrom"
}

/** AI 메모 저장소 (사용자 메모와 별도). */
object AiNoteStore {
    private const val PREF = "ai_note_store"
    private const val KEY = "ai_notes"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<AiNote> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return parse(raw)
    }

    fun parse(raw: String): MutableList<AiNote> = try {
        val arr = JSONArray(raw)
        ArrayList<AiNote>(arr.length()).also { list ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(AiNote(
                    o.optInt("bookIndex"), o.optString("bookNameKo"),
                    o.optInt("chapter"), o.optInt("verseFrom"),
                    o.optInt("verseTo", o.optInt("verseFrom")),
                    o.optString("text"), o.optLong("updatedAt")
                ))
            }
        }
    } catch (e: Exception) { ArrayList() }

    private fun persist(ctx: Context, list: List<AiNote>) {
        prefs(ctx).edit().putString(KEY, toJson(list)).apply()
    }

    fun toJson(list: List<AiNote>): String {
        val arr = JSONArray()
        for (n in list) arr.put(JSONObject().apply {
            put("bookIndex", n.bookIndex); put("bookNameKo", n.bookNameKo)
            put("chapter", n.chapter); put("verseFrom", n.verseFrom); put("verseTo", n.verseTo)
            put("text", n.text); put("updatedAt", n.updatedAt)
        })
        return arr.toString()
    }

    fun get(ctx: Context, key: String): AiNote? = all(ctx).find { it.key == key }

    fun save(ctx: Context, note: AiNote) {
        val list = all(ctx)
        list.removeAll { it.key == note.key }
        list.add(note)
        persist(ctx, list)
    }

    fun delete(ctx: Context, key: String) {
        val list = all(ctx)
        if (list.removeAll { it.key == key }) persist(ctx, list)
    }

    /** 가져오기: 전체 교체 */
    fun replaceAll(ctx: Context, list: List<AiNote>) = persist(ctx, list)

    /** 특정 장의 '첫 구절' 번호 집합 (연두색 점 표시용) */
    fun firstVersesIn(ctx: Context, bookIndex: Int, chapter: Int): Set<Int> =
        all(ctx).filter { it.bookIndex == bookIndex && it.chapter == chapter }
            .map { it.verseFrom }.toSet()
}
