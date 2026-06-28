package com.example.bibleapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** QT(묵상) 기록 저장소 — 단락(섹션)별 묵상/기도/사진 보존 */
object QtStore {

    private const val PREF = "qt_store"
    private const val KEY_ENTRIES = "entries"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<QtEntry> {
        val raw = prefs(ctx).getString(KEY_ENTRIES, "[]") ?: "[]"
        return parse(raw)
    }

    /** 백업/가져오기용 파싱 */
    fun parse(raw: String): MutableList<QtEntry> {
        return try {
            val arr = JSONArray(raw)
            ArrayList<QtEntry>(arr.length()).also { list ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val secs = ArrayList<QtSection>()
                    val sa = o.optJSONArray("sections")
                    if (sa != null) for (j in 0 until sa.length()) {
                        val s = sa.getJSONObject(j)
                        val photos = ArrayList<String>()
                        val pa = s.optJSONArray("photos")
                        if (pa != null) for (k in 0 until pa.length()) photos.add(pa.getString(k))
                        secs.add(QtSection(
                            s.optString("range"),
                            s.optString("meditation"),
                            s.optString("prayer"),
                            photos
                        ))
                    }
                    list.add(QtEntry(
                        o.optInt("bookIndex"), o.optString("bookNameKo"),
                        o.optInt("chapter"), secs, o.optLong("updatedAt")
                    ))
                }
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun toJsonString(list: List<QtEntry>): String {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply {
            put("bookIndex", e.bookIndex); put("bookNameKo", e.bookNameKo)
            put("chapter", e.chapter); put("updatedAt", e.updatedAt)
            val sa = JSONArray()
            for (s in e.sections) if (!s.isEmpty()) sa.put(JSONObject().apply {
                put("range", s.range)
                put("meditation", s.meditation); put("prayer", s.prayer)
                val pa = JSONArray(); s.photoPaths.forEach { pa.put(it) }; put("photos", pa)
            })
            put("sections", sa)
        })
        return arr.toString()
    }

    fun allJson(ctx: Context): String = toJsonString(all(ctx))

    private fun persist(ctx: Context, list: List<QtEntry>) {
        prefs(ctx).edit().putString(KEY_ENTRIES, toJsonString(list)).apply()
    }

    fun replaceAll(ctx: Context, list: List<QtEntry>) = persist(ctx, list)

    fun get(ctx: Context, key: String): QtEntry? = all(ctx).find { it.key == key }

    fun save(ctx: Context, entry: QtEntry) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.key == entry.key }
        if (entry.isEmpty()) {
            if (idx >= 0) { list.removeAt(idx); persist(ctx, list) }
            return
        }
        if (idx >= 0) list[idx] = entry else list.add(0, entry)
        persist(ctx, list)
    }

    fun delete(ctx: Context, key: String) {
        val list = all(ctx)
        val e = list.find { it.key == key } ?: return
        e.sections.forEach { s -> s.photoPaths.forEach { runCatching { File(it).delete() } } }
        persist(ctx, list.filter { it.key != key })
    }

    /** 한 권에서 QT 기록이 있는 장 → 마지막 저장시각 */
    fun doneMap(ctx: Context, book: Int): Map<Int, Long> =
        all(ctx).filter { it.bookIndex == book && !it.isEmpty() }
            .associate { it.chapter to it.updatedAt }

    fun doneCount(ctx: Context): Int = all(ctx).count { !it.isEmpty() }

    fun photoDir(ctx: Context): File =
        File(ctx.filesDir, "qt_photos").also { it.mkdirs() }

    fun newPhotoFile(ctx: Context, tag: String): File =
        File(photoDir(ctx), "qt_${tag}_${System.currentTimeMillis()}.jpg")
}
