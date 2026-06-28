package com.example.bibleapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 사용자가 직접 조정한 단락 구분 저장소.
 *  - 키: "book-chapter", 값: [{from,to,title}]
 *  - 우선순위: 사용자 지정 > 번들 단락(qt_outline.json) > 폴백(장 전체 1단락)
 */
object QtLayoutStore {

    private const val PREF = "qt_layout"
    private const val KEY = "layouts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun root(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun saveRoot(ctx: Context, root: JSONObject) =
        prefs(ctx).edit().putString(KEY, root.toString()).apply()

    /** 사용자 지정 단락 (없으면 null) */
    fun get(ctx: Context, book: Int, chapter: Int): List<QtOutline.Section>? {
        val arr = root(ctx).optJSONArray("$book-$chapter") ?: return null
        if (arr.length() == 0) return null
        val out = ArrayList<QtOutline.Section>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(QtOutline.Section(o.optString("title"), o.optInt("from", 1), o.optInt("to", 1), o.optString("summary")))
        }
        return out
    }

    fun setSections(ctx: Context, book: Int, chapter: Int, secs: List<QtOutline.Section>) {
        val r = root(ctx)
        val arr = JSONArray()
        for (s in secs) arr.put(JSONObject().apply {
            put("from", s.from); put("to", s.to); put("title", s.title); put("summary", s.summary)
        })
        r.put("$book-$chapter", arr)
        saveRoot(ctx, r)
    }

    fun clear(ctx: Context, book: Int, chapter: Int) {
        val r = root(ctx); r.remove("$book-$chapter"); saveRoot(ctx, r)
    }

    /** 절 끝 번호 목록(분할점)으로 단락 구성. ends=[] 이면 장 전체 1단락 */
    fun setSplits(ctx: Context, book: Int, chapter: Int, ends: List<Int>, verseCount: Int) {
        val cleaned = ends.filter { it in 1 until verseCount }.toSortedSet().toList()
        val secs = ArrayList<QtOutline.Section>()
        var start = 1
        for (e in cleaned) { secs.add(QtOutline.Section("", start, e, "")); start = e + 1 }
        secs.add(QtOutline.Section("", start, verseCount, ""))
        setSections(ctx, book, chapter, secs)
    }

    /** 특정 단락 제목 변경 (현재 표시 중인 단락을 기준으로 사용자 레이아웃을 만들고 제목만 교체) */
    fun setTitle(ctx: Context, book: Int, chapter: Int,
                 base: List<QtOutline.Section>, range: String, title: String) {
        val current = get(ctx, book, chapter) ?: base
        val updated = current.map {
            if ("${it.from}-${it.to}" == range) it.copy(title = title) else it
        }
        setSections(ctx, book, chapter, updated)
    }
}
