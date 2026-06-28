package com.example.bibleapp.util

import android.content.Context
import android.net.Uri
import com.example.bibleapp.data.AiNote
import com.example.bibleapp.data.AiNoteStore
import com.example.bibleapp.data.AiStore
import com.example.bibleapp.data.BibleRepository
import com.example.bibleapp.data.Bookmark
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.data.NoteStore
import com.example.bibleapp.data.QtEntry
import com.example.bibleapp.data.QtSection
import com.example.bibleapp.data.QtStore
import com.example.bibleapp.data.VerseNote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 설정·메모·QT 기록을 한 파일로 내보내고 다시 가져오는 유틸.
 *  - 백업(.zip): backup.json(설정/메모/QT) + photos/(사진 원본)  → 재설치 복원용
 *  - CSV(.zip): notes.csv + qt.csv (텍스트만)                    → 표 형태 확인용
 * 모든 작업은 try/catch로 감싸 안정성을 확보한다. (워커 스레드에서 호출 권장)
 */
object BackupManager {

    private const val BACKUP_VERSION = 1
    private val PREF_FILES = listOf("bookmark_store", "note_store", "qt_store")

    // ───────────────────────── 내보내기: 전체 백업 zip ─────────────────────────
    fun exportBackupZip(ctx: Context): File {
        val dir = File(ctx.cacheDir, "shared").also { it.mkdirs() }
        val out = File(dir, "bible_backup_${stamp()}.zip")

        val root = JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("exportedAt", System.currentTimeMillis())
            val prefsObj = JSONObject()
            for (name in PREF_FILES) prefsObj.put(name, dumpPrefs(ctx, name))
            put("prefs", prefsObj)
        }

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 사진 원본 (파일명만 보존)
            for (f in allPhotoFiles(ctx)) {
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("photos/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    // ───────────────────────── 내보내기: CSV zip ─────────────────────────
    fun exportCsvZip(ctx: Context): File {
        val dir = File(ctx.cacheDir, "shared").also { it.mkdirs() }
        val out = File(dir, "bible_csv_${stamp()}.zip")

        val notes = StringBuilder("book,chapter,verse,reference,text,createdAt,updatedAt\n")
        for (n in NoteStore.all(ctx)) {
            notes.append(csv(n.bookNameKo)).append(',')
                .append(n.chapter).append(',').append(n.verse).append(',')
                .append(csv(n.reference)).append(',')
                .append(csv(n.text)).append(',')
                .append(csv(fmt(n.createdAt))).append(',')
                .append(csv(fmt(n.updatedAt))).append('\n')
        }
        val qt = StringBuilder("book,chapter,range,meditation,prayer,qtDate\n")
        for (e in QtStore.all(ctx)) {
            for (s in e.sections) {
                if (s.isEmpty()) continue
                qt.append(csv(e.bookNameKo)).append(',')
                    .append(e.chapter).append(',')
                    .append(csv(s.range)).append(',')
                    .append(csv(s.meditation)).append(',')
                    .append(csv(s.prayer)).append(',')
                    .append(csv(fmt(e.updatedAt))).append('\n')
            }
        }

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            // 엑셀 한글 깨짐 방지 BOM
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            zip.putNextEntry(ZipEntry("notes.csv")); zip.write(bom); zip.write(notes.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("qt.csv"));    zip.write(bom); zip.write(qt.toString().toByteArray(Charsets.UTF_8));    zip.closeEntry()
        }
        return out
    }

    // ───────────────────────── 가져오기: 백업 zip 복원 ─────────────────────────
    /** @return 복원 요약 메시지 */
    fun importBackupZip(ctx: Context, uri: Uri): String {
        var backupJson: String? = null
        val restoredPhotos = ArrayList<String>()

        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "backup.json") {
                        backupJson = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (name.startsWith("photos/") && !entry.isDirectory) {
                        val base = name.substringAfterLast('/')
                        if (base.isNotBlank()) {
                            val targetDir = if (base.startsWith("qt_"))
                                QtStore.photoDir(ctx) else NoteStore.photoDir(ctx)
                            val dest = File(targetDir, base)
                            FileOutputStream(dest).use { zip.copyTo(it) }
                            restoredPhotos.add(dest.absolutePath)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalStateException("파일을 열 수 없습니다")

        val json = backupJson ?: throw IllegalStateException("backup.json 없음")
        val root = JSONObject(json)
        val prefs = root.optJSONObject("prefs") ?: JSONObject()

        // 1) bookmark_store(설정/북마크) 그대로 복원
        prefs.optJSONObject("bookmark_store")?.let { restorePrefs(ctx, "bookmark_store", it) }

        // 2) 메모/QT: 사진 경로를 현재 기기 경로로 재작성 후 저장
        val noteRaw = prefs.optJSONObject("note_store")?.optJSONObject("notes")?.optString("v", "[]") ?: "[]"
        val notes = NoteStore.parse(noteRaw).map { n ->
            n.copy(photoPaths = remap(ctx, n.photoPaths))
        }
        NoteStore.replaceAll(ctx, notes)

        val qtRaw = prefs.optJSONObject("qt_store")?.optJSONObject("entries")?.optString("v", "[]") ?: "[]"
        val qts = QtStore.parse(qtRaw).map { e ->
            e.copy(sections = e.sections.map { s -> s.copy(photoPaths = remap(ctx, s.photoPaths)) })
        }
        QtStore.replaceAll(ctx, qts)

        return "가져오기 완료 · 메모 ${notes.size}건, QT ${qts.size}건, 사진 ${restoredPhotos.size}장"
    }

    // ───────────────────────── 내부 헬퍼 ─────────────────────────
    /** 백업에 담긴 사진 경로(절대경로)를 현재 기기의 실제 파일 경로로 보정 */
    private fun remap(ctx: Context, paths: List<String>): List<String> = paths.mapNotNull { p ->
        val base = p.substringAfterLast('/')
        if (base.isBlank()) return@mapNotNull null
        val dir = if (base.startsWith("qt_")) QtStore.photoDir(ctx) else NoteStore.photoDir(ctx)
        val f = File(dir, base)
        if (f.exists()) f.absolutePath else null
    }

    private fun allPhotoFiles(ctx: Context): List<File> {
        val a = NoteStore.photoDir(ctx).listFiles()?.toList() ?: emptyList()
        val b = QtStore.photoDir(ctx).listFiles()?.toList() ?: emptyList()
        return a + b
    }

    /** SharedPreferences 한 파일을 타입 보존 JSON으로 직렬화 */
    private fun dumpPrefs(ctx: Context, name: String): JSONObject {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val obj = JSONObject()
        for ((k, v) in sp.all) {
            val cell = JSONObject()
            when (v) {
                is Boolean -> { cell.put("t", "b"); cell.put("v", v) }
                is Int     -> { cell.put("t", "i"); cell.put("v", v) }
                is Long    -> { cell.put("t", "l"); cell.put("v", v) }
                is Float   -> { cell.put("t", "f"); cell.put("v", v.toDouble()) }
                is String  -> { cell.put("t", "s"); cell.put("v", v) }
                is Set<*>  -> { cell.put("t", "set"); cell.put("v", JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            obj.put(k, cell)
        }
        return obj
    }

    private fun restorePrefs(ctx: Context, name: String, obj: JSONObject) {
        val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val cell = obj.optJSONObject(k) ?: continue
            when (cell.optString("t")) {
                "b" -> ed.putBoolean(k, cell.optBoolean("v"))
                "i" -> ed.putInt(k, cell.optInt("v"))
                "l" -> ed.putLong(k, cell.optLong("v"))
                "f" -> ed.putFloat(k, cell.optDouble("v").toFloat())
                "s" -> ed.putString(k, cell.optString("v"))
                "set" -> {
                    val a = cell.optJSONArray("v") ?: JSONArray()
                    val s = HashSet<String>(); for (i in 0 until a.length()) s.add(a.getString(i))
                    ed.putStringSet(k, s)
                }
            }
        }
        ed.apply()
    }

    private fun csv(s: String): String {
        val needsQuote = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        val esc = s.replace("\"", "\"\"")
        return if (needsQuote) "\"$esc\"" else esc
    }

    private fun fmt(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(ms))

    // ───────────────────────── 교환 형식 (.json) : 아이폰 웹앱과 호환 ─────────────────────────
    // 공용 스키마(웹 형식 기준):
    // {format:"hanyoung-bible-interchange", v:1, exportedAt, source:"android",
    //  settings:{version:"krv|kjv|both", theme:"dark|light", font:int},
    //  bookmarks:["b-c-v",...],
    //  notes:{"b-c-v":{text, ts, photos:["data:image/jpeg;base64,...",...]}},
    //  qt:{"b-c":{updatedAt, sections:[{range,med,pray,photos:[...]}]}},
    //  layout:{"b-c":[{from,to,title,summary}]}}
    fun exportInterchangeJson(ctx: Context, includeKeys: Boolean = false): File {
        val dir = File(ctx.cacheDir, "shared").also { it.mkdirs() }
        val out = File(dir, "영한Bible_${stamp()}.json")
        val root = JSONObject()
        root.put("format", "hanyoung-bible-interchange")
        root.put("v", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("source", "android")

        root.put("settings", JSONObject().apply {
            put("version", when {
                BookmarkStore.getShowParallel(ctx) -> "both"
                BibleRepository.currentVersion == BibleRepository.Version.KJV -> "kjv"
                else -> "krv"
            })
            put("theme", if (BookmarkStore.getThemeMode(ctx) == 1) "light" else "dark")
            put("font", BookmarkStore.getFontSize(ctx).toInt())
        })

        val bm = JSONArray()
        for (b in BookmarkStore.all(ctx)) bm.put(b.key)
        root.put("bookmarks", bm)

        val notes = JSONObject()
        for (n in NoteStore.all(ctx)) {
            notes.put(n.key, JSONObject().apply {
                put("text", n.text)
                put("ts", if (n.updatedAt > 0) n.updatedAt else n.createdAt)
                put("photos", photosToDataUris(n.photoPaths))
            })
        }
        root.put("notes", notes)

        val qt = JSONObject()
        for (e in QtStore.all(ctx)) {
            if (e.isEmpty()) continue
            val secs = JSONArray()
            for (s in e.sections) {
                if (s.isEmpty()) continue
                secs.put(JSONObject().apply {
                    put("range", s.range)
                    put("med", s.meditation)
                    put("pray", s.prayer)
                    put("photos", photosToDataUris(s.photoPaths))
                })
            }
            if (secs.length() == 0) continue
            qt.put(e.key, JSONObject().apply { put("updatedAt", e.updatedAt); put("sections", secs) })
        }
        root.put("qt", qt)

        root.put("layout", layoutToJson(ctx))

        // AI: 프롬프트 + AI 메모 (+ 선택적 키)
        root.put("ai", JSONObject().apply {
            put("provider", AiStore.getProvider(ctx))
            put("model", AiStore.getModel(ctx))
            put("versePrompt", AiStore.getVersePrompt(ctx))
            put("bulletinPrompt", AiStore.getBulletinPrompt(ctx))
            val an = JSONArray()
            for (a in AiNoteStore.all(ctx)) an.put(JSONObject().apply {
                put("bookIndex", a.bookIndex); put("bookNameKo", a.bookNameKo)
                put("chapter", a.chapter); put("verseFrom", a.verseFrom); put("verseTo", a.verseTo)
                put("text", a.text); put("updatedAt", a.updatedAt)
            })
            put("notes", an)
            if (includeKeys) put("keys", JSONObject().apply {
                put("gemini", AiStore.getKey(ctx, "gemini"))
                put("groq", AiStore.getKey(ctx, "groq"))
            })
        })

        FileOutputStream(out).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        return out
    }

    fun importInterchangeJson(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("파일을 열 수 없습니다")
        val root = JSONObject(text)
        if (!(root.has("format") || root.has("notes") || root.has("qt") || root.has("bookmarks")))
            throw IllegalStateException("호환 백업(.json) 파일이 아닙니다")

        var photoCount = 0

        // 북마크
        var bmCount = 0
        root.optJSONArray("bookmarks")?.let { arr ->
            val list = ArrayList<Bookmark>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).split("-")
                if (p.size != 3) continue
                val b = p[0].toIntOrNull() ?: continue
                val c = p[1].toIntOrNull() ?: continue
                val v = p[2].toIntOrNull() ?: continue
                val book = BibleRepository.bookByIndex(b)
                val nameKo = book?.nameKo ?: b.toString()
                val txt = book?.chapters?.getOrNull(c - 1)?.getOrNull(v - 1) ?: ""
                list.add(Bookmark(b, nameKo, c, v, txt)); bmCount++
            }
            replaceBookmarks(ctx, list)
        }

        // 메모
        var noteCount = 0
        root.optJSONObject("notes")?.let { obj ->
            val list = ArrayList<VerseNote>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val p = key.split("-"); if (p.size != 3) continue
                val b = p[0].toIntOrNull() ?: continue
                val c = p[1].toIntOrNull() ?: continue
                val v = p[2].toIntOrNull() ?: continue
                val o = obj.optJSONObject(key) ?: continue
                val ts = o.optLong("ts", System.currentTimeMillis())
                val photos = dataUrisToFiles(ctx, o.optJSONArray("photos"), NoteStore.photoDir(ctx), "note")
                photoCount += photos.size
                val nameKo = BibleRepository.bookByIndex(b)?.nameKo ?: b.toString()
                list.add(VerseNote(b, nameKo, c, v, o.optString("text"), ts, ts, photos)); noteCount++
            }
            NoteStore.replaceAll(ctx, list)
        }

        // QT
        var qtCount = 0
        root.optJSONObject("qt")?.let { obj ->
            val list = ArrayList<QtEntry>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val p = key.split("-"); if (p.size != 2) continue
                val b = p[0].toIntOrNull() ?: continue
                val c = p[1].toIntOrNull() ?: continue
                val eo = obj.optJSONObject(key) ?: continue
                val secArr = eo.optJSONArray("sections") ?: JSONArray()
                val secs = ArrayList<QtSection>()
                for (i in 0 until secArr.length()) {
                    val so = secArr.optJSONObject(i) ?: continue
                    val photos = dataUrisToFiles(ctx, so.optJSONArray("photos"), QtStore.photoDir(ctx), "qt")
                    photoCount += photos.size
                    secs.add(QtSection(so.optString("range"), so.optString("med"), so.optString("pray"), photos))
                }
                val nameKo = BibleRepository.bookByIndex(b)?.nameKo ?: b.toString()
                list.add(QtEntry(b, nameKo, c, secs, eo.optLong("updatedAt", System.currentTimeMillis()))); qtCount++
            }
            QtStore.replaceAll(ctx, list)
        }

        // 단락 레이아웃
        root.optJSONObject("layout")?.let { lay ->
            ctx.getSharedPreferences("qt_layout", Context.MODE_PRIVATE).edit()
                .putString("layouts", lay.toString()).apply()
        }

        // AI: 프롬프트 + AI 메모 (+ 키)
        root.optJSONObject("ai")?.let { a ->
            if (a.has("provider")) AiStore.setProvider(ctx, a.optString("provider", "gemini"))
            if (a.has("model")) AiStore.setModel(ctx, a.optString("model"))
            if (a.has("versePrompt")) AiStore.setVersePrompt(ctx, a.optString("versePrompt"))
            if (a.has("bulletinPrompt")) AiStore.setBulletinPrompt(ctx, a.optString("bulletinPrompt"))
            a.optJSONArray("notes")?.let { arr ->
                val list = ArrayList<AiNote>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(AiNote(
                        o.optInt("bookIndex"), o.optString("bookNameKo"),
                        o.optInt("chapter"), o.optInt("verseFrom"),
                        o.optInt("verseTo", o.optInt("verseFrom")),
                        o.optString("text"), o.optLong("updatedAt")
                    ))
                }
                AiNoteStore.replaceAll(ctx, list)
            }
            a.optJSONObject("keys")?.let { k ->
                if (k.optString("gemini").isNotBlank()) AiStore.setKey(ctx, "gemini", k.optString("gemini"))
                if (k.optString("groq").isNotBlank()) AiStore.setKey(ctx, "groq", k.optString("groq"))
            }
        }

        // 설정(가능한 범위)
        root.optJSONObject("settings")?.let { s ->
            when (s.optString("version")) {
                "both" -> BookmarkStore.setShowParallel(ctx, true)
                "kjv" -> { BookmarkStore.setShowParallel(ctx, false); BibleRepository.switchVersion(BibleRepository.Version.KJV) }
                "krv" -> { BookmarkStore.setShowParallel(ctx, false); BibleRepository.switchVersion(BibleRepository.Version.KRV) }
            }
            if (s.has("font")) BookmarkStore.setFontSize(ctx, s.optInt("font", 17).toFloat())
            if (s.has("theme")) BookmarkStore.setThemeMode(ctx, if (s.optString("theme") == "light") 1 else 2)
        }

        return "가져오기 완료 · 북마크 ${bmCount}건, 메모 ${noteCount}건, QT ${qtCount}건, 사진 ${photoCount}장"
    }

    /** 사진 파일들을 data URI(base64) 배열로 */
    private fun photosToDataUris(paths: List<String>): JSONArray {
        val arr = JSONArray()
        for (p in paths) {
            val f = File(p)
            if (!f.exists()) continue
            try {
                val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                arr.put("data:image/jpeg;base64,$b64")
            } catch (e: Exception) { /* skip */ }
        }
        return arr
    }

    /** data URI(base64) 배열을 실제 파일로 복원하고 경로 목록 반환 */
    private fun dataUrisToFiles(ctx: Context, arr: JSONArray?, dir: File, tag: String): List<String> {
        if (arr == null) return emptyList()
        dir.mkdirs()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isBlank()) continue
            val b64 = if (s.startsWith("data:")) s.substringAfter(",", "") else s
            if (b64.isBlank()) continue
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val prefix = if (tag == "qt") "qt" else "note"
                val f = File(dir, "${prefix}_imp_${System.currentTimeMillis()}_$i.jpg")
                FileOutputStream(f).use { it.write(bytes) }
                out.add(f.absolutePath)
            } catch (e: Exception) { /* skip */ }
        }
        return out
    }

    private fun replaceBookmarks(ctx: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().apply {
            put("bookIndex", b.bookIndex); put("bookNameKo", b.bookNameKo)
            put("chapter", b.chapter); put("verse", b.verse); put("text", b.text)
        })
        ctx.getSharedPreferences("bookmark_store", Context.MODE_PRIVATE).edit()
            .putString("bookmarks", arr.toString()).apply()
    }

    private fun layoutToJson(ctx: Context): JSONObject {
        val raw = ctx.getSharedPreferences("qt_layout", Context.MODE_PRIVATE).getString("layouts", "{}") ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
}
