package com.example.bibleapp.data

import android.content.Context
import com.example.bibleapp.util.AppLogger
import org.json.JSONObject

/**
 * QT 단락(소단락) 구조 제공.
 *  - assets/qt_outline.json 에 정의된 장: 그 그룹/제목/요약 사용
 *  - 정의가 없는 장: 약 10절 단위로 자동 분할(제목은 "n–m절", 요약 없음)
 * 두란노/생명의삶 등 저작권 본문은 포함하지 않으며, 요약은 자체 작성한 원문.
 */
object QtOutline {

    data class Section(
        val title: String,
        val from: Int,          // 1-based, inclusive
        val to: Int,            // 1-based, inclusive
        val summary: String
    )

    private var cache: MutableMap<String, List<Section>>? = null

    private fun ensure(ctx: Context) {
        if (cache != null) return
        val m = HashMap<String, List<Section>>()
        try {
            val raw = ctx.assets.open("qt_outline.json").bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = root.getJSONArray(k)
                val secs = ArrayList<Section>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    secs.add(Section(
                        o.optString("title"),
                        o.optInt("from", 1),
                        o.optInt("to", 1),
                        o.optString("summary")
                    ))
                }
                m[k] = secs
            }
        } catch (e: Exception) {
            AppLogger.e("QtOutline", "load 실패: ${e.message}")
        }
        cache = m
    }

    /** 해당 장의 단락 목록. 우선순위: 사용자 지정 > 번들 자료 > 장 전체 1단락. */
    fun sections(ctx: Context, book: Int, chapter: Int, verseCount: Int): List<Section> {
        if (verseCount <= 0) return emptyList()

        // 1) 사용자가 직접 나눈 단락
        val custom = QtLayoutStore.get(ctx, book, chapter)
        if (!custom.isNullOrEmpty()) {
            return custom.mapNotNull { s ->
                val from = s.from.coerceIn(1, verseCount)
                val to = s.to.coerceIn(from, verseCount)
                Section(s.title, from, to, s.summary)
            }
        }

        // 2) 번들 단락 자료
        ensure(ctx)
        val defined = cache?.get("$book-$chapter")
        if (!defined.isNullOrEmpty()) {
            return defined.mapNotNull { s ->
                val from = s.from.coerceIn(1, verseCount)
                val to = s.to.coerceIn(from, verseCount)
                Section(s.title, from, to, s.summary)
            }
        }

        // 3) 폴백: 장 전체를 한 단락으로 (임의 분할하지 않음 → 사용자가 '단락 편집'으로 조정)
        return listOf(Section("", 1, verseCount, ""))
    }

    /** 단락 자료가 정의된 장인지 */
    fun hasOutline(ctx: Context, book: Int, chapter: Int): Boolean {
        ensure(ctx)
        return cache?.get("$book-$chapter")?.isNotEmpty() == true
    }
}
