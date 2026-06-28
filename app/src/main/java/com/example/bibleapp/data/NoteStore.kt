package com.example.bibleapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object NoteStore {

    private const val PREF = "note_store"
    private const val KEY_NOTES = "notes"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ─── CRUD ───
    fun all(ctx: Context): MutableList<VerseNote> {
        val raw = prefs(ctx).getString(KEY_NOTES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return ArrayList<VerseNote>(arr.length()).also { list ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val photos = ArrayList<String>()
                val pa = o.optJSONArray("photos")
                if (pa != null) for (j in 0 until pa.length()) photos.add(pa.getString(j))
                list.add(VerseNote(
                    o.getInt("bookIndex"), o.getString("bookNameKo"),
                    o.getInt("chapter"), o.getInt("verse"),
                    o.getString("text"), o.getLong("createdAt"), o.getLong("updatedAt"),
                    photos
                ))
            }
        }
    }

    private fun persist(ctx: Context, list: List<VerseNote>) {
        val arr = JSONArray()
        for (n in list) arr.put(JSONObject().apply {
            put("bookIndex", n.bookIndex); put("bookNameKo", n.bookNameKo)
            put("chapter", n.chapter); put("verse", n.verse); put("text", n.text)
            put("createdAt", n.createdAt); put("updatedAt", n.updatedAt)
            val pa = JSONArray(); n.photoPaths.forEach { pa.put(it) }; put("photos", pa)
        })
        prefs(ctx).edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    /** 백업용: 현재 메모 전체를 JSON 문자열로 */
    fun allJson(ctx: Context): String {
        val arr = JSONArray()
        for (n in all(ctx)) arr.put(JSONObject().apply {
            put("bookIndex", n.bookIndex); put("bookNameKo", n.bookNameKo)
            put("chapter", n.chapter); put("verse", n.verse); put("text", n.text)
            put("createdAt", n.createdAt); put("updatedAt", n.updatedAt)
            val pa = JSONArray(); n.photoPaths.forEach { pa.put(it) }; put("photos", pa)
        })
        return arr.toString()
    }

    /** 가져오기용: JSON 문자열을 메모 리스트로 파싱 */
    fun parse(raw: String): MutableList<VerseNote> {
        return try {
            val arr = JSONArray(raw)
            ArrayList<VerseNote>(arr.length()).also { list ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val photos = ArrayList<String>()
                    val pa = o.optJSONArray("photos")
                    if (pa != null) for (j in 0 until pa.length()) photos.add(pa.getString(j))
                    list.add(VerseNote(
                        o.optInt("bookIndex"), o.optString("bookNameKo"),
                        o.optInt("chapter"), o.optInt("verse"),
                        o.optString("text"), o.optLong("createdAt"), o.optLong("updatedAt"),
                        photos
                    ))
                }
            }
        } catch (e: Exception) { mutableListOf() }
    }

    /** 가져오기용: 전체 교체 */
    fun replaceAll(ctx: Context, list: List<VerseNote>) = persist(ctx, list)

    fun get(ctx: Context, key: String): VerseNote? = all(ctx).find { it.key == key }
    fun save(ctx: Context, note: VerseNote) {
        val list = all(ctx)
        val idx = list.indexOfFirst { it.key == note.key }
        if (idx >= 0) list[idx] = note else list.add(0, note)
        persist(ctx, list)
    }

    fun delete(ctx: Context, key: String) {
        val list = all(ctx)
        val note = list.find { it.key == key } ?: return
        // 사진 파일도 삭제
        note.photoPaths.forEach { File(it).delete() }
        persist(ctx, list.filter { it.key != key })
    }

    fun deleteMultiple(ctx: Context, keys: Set<String>) {
        val list = all(ctx)
        keys.forEach { k -> list.find { it.key == k }?.photoPaths?.forEach { File(it).delete() } }
        persist(ctx, list.filter { it.key !in keys })
    }

    // ─── 사진 경로 생성 ───
    fun photoDir(ctx: Context): File =
        File(ctx.filesDir, "memo_photos").also { it.mkdirs() }

    fun newPhotoFile(ctx: Context, noteKey: String): File {
        val safe = noteKey.replace("-", "_")
        return File(photoDir(ctx), "${safe}_${System.currentTimeMillis()}.jpg")
    }

    // ─── 통계 ───
    fun totalBytes(note: VerseNote): Long =
        note.photoPaths.sumOf { File(it).length() } + note.text.length * 2L

    fun hasNotes(ctx: Context, keys: Set<String>): Set<String> {
        val all = all(ctx).map { it.key }.toSet()
        return keys.intersect(all)
    }

    /** 사진을 시계방향 90도 회전하여 같은 파일에 다시 저장 */
    fun rotatePhoto(path: String): Boolean {
        return try {
            val src = android.graphics.BitmapFactory.decodeFile(path) ?: return false
            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
            val rotated = android.graphics.Bitmap.createBitmap(
                src, 0, 0, src.width, src.height, matrix, true)
            java.io.FileOutputStream(File(path)).use {
                rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            if (rotated != src) src.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 카메라 촬영 사진의 EXIF 방향값을 읽어 실제 픽셀을 회전시키고
     * 방향값을 정상(=1)으로 기록한다. 회전이 필요 없으면 아무 것도 하지 않는다.
     */
    fun normalizeOrientation(path: String) {
        try {
            val exif = android.media.ExifInterface(path)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return
            }
            val src = android.graphics.BitmapFactory.decodeFile(path) ?: return
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = android.graphics.Bitmap.createBitmap(
                src, 0, 0, src.width, src.height, matrix, true)
            java.io.FileOutputStream(File(path)).use {
                rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
            }
            if (rotated != src) src.recycle()
            // EXIF 방향값을 정상으로 표시 (이미 픽셀을 돌렸으므로)
            try {
                val ex2 = android.media.ExifInterface(path)
                ex2.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL.toString())
                ex2.saveAttributes()
            } catch (_: Exception) {}
        } catch (_: Exception) {
            // 보정 실패해도 원본 사용 (치명적이지 않음)
        }
    }
}
