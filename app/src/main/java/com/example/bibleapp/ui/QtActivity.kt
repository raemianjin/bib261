package com.example.bibleapp.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.bibleapp.R
import com.example.bibleapp.data.BibleRepository
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.data.NoteStore
import com.example.bibleapp.data.QtEntry
import com.example.bibleapp.data.QtLayoutStore
import com.example.bibleapp.data.QtOutline
import com.example.bibleapp.data.QtSection
import com.example.bibleapp.data.QtStore
import com.example.bibleapp.databinding.ActivityQtBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QtActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQtBinding
    private var bookIndex = 1
    private var chapter = 1                 // 1-based
    private var entryUpdatedAt = 0L
    private var currentSections: List<QtOutline.Section> = emptyList()

    private val dateFmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private val dp get() = resources.displayMetrics.density

    /** 단락별 입력 위젯 묶음 */
    private class SectionHolder(
        val range: String,
        val medCheck: CheckBox,
        val medEdit: EditText,
        val prayerCheck: CheckBox,
        val prayerEdit: EditText,
        val photoRow: LinearLayout,
        val photos: MutableList<String>
    )
    private val holders = ArrayList<SectionHolder>()
    private var pendingSection = -1
    private var pendingCameraPath: String? = null

    companion object {
        const val EXTRA_BOOK = "book_index"
        const val EXTRA_CHAPTER = "chapter_index"   // 0-based
        private const val REQ_GALLERY = 911
        private const val REQ_CAMERA = 912
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQtBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookIndex = intent.getIntExtra(EXTRA_BOOK, 1).coerceAtLeast(1)
        chapter = intent.getIntExtra(EXTRA_CHAPTER, 0) + 1

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveEntry(bumpDate = true, toast = true) }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnDivide.setOnClickListener { showDivideDialog() }

        buildChapterChips()
        renderChapter()
    }

    private fun book() = BibleRepository.bookByIndex(bookIndex)

    private fun primaryVerses(): List<String> {
        val krv = BibleRepository.getKrvBook(bookIndex)?.chapters?.getOrNull(chapter - 1)
        val kjv = BibleRepository.getKjvBook(bookIndex)?.chapters?.getOrNull(chapter - 1)
        return when {
            BibleRepository.currentVersion == BibleRepository.Version.KRV && !krv.isNullOrEmpty() -> krv
            !kjv.isNullOrEmpty() -> kjv
            else -> krv ?: emptyList()
        }
    }

    private fun getThemeOnSurface(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
        return if (tv.data != 0) tv.data else getColor(R.color.ink)
    }

    private fun versesFontSize(): Float = BookmarkStore.getFontSize(this).coerceIn(13f, 22f)

    // ── 장 칩 ──
    private fun buildChapterChips() {
        val b = book() ?: return
        binding.chapterBar.removeAllViews()
        val done = QtStore.doneMap(this, bookIndex)
        for (ch in 1..b.chapterCount) {
            val chip = TextView(this).apply {
                text = if (done.containsKey(ch)) "✓$ch" else "$ch"
                textSize = 13f
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * dp).toInt() }
                setBackgroundResource(R.drawable.bg_chapter_chip)
                val selected = ch == chapter
                setTextColor(if (selected) getColor(R.color.white) else getColor(R.color.ink_soft))
                if (selected) setBackgroundColor(getColor(R.color.indigo))
                setOnClickListener {
                    if (ch != chapter) {
                        saveEntry(bumpDate = false, toast = false)
                        chapter = ch; renderChapter(); buildChapterChips()
                    }
                }
            }
            binding.chapterBar.addView(chip)
        }
    }

    // ── 장 렌더 ──
    private fun renderChapter() {
        val b = book() ?: return
        binding.qtTitle.text = "${b.nameKo} ${chapter}장 · QT"

        val verses = primaryVerses()
        val entry = QtStore.get(this, "$bookIndex-$chapter")
        entryUpdatedAt = entry?.updatedAt ?: 0L
        binding.qtDate.text = if (entry != null && !entry.isEmpty() && entry.updatedAt > 0)
            "최근 QT ${dateFmt.format(Date(entry.updatedAt))}" else "기록 없음"

        binding.sectionsHost.removeAllViews()
        holders.clear()

        val sections = QtOutline.sections(this, bookIndex, chapter, verses.size)
        currentSections = sections
        for (sec in sections) {
            val range = "${sec.from}-${sec.to}"
            val existing = entry?.section(range)
            binding.sectionsHost.addView(buildSectionCard(sec, range, verses, existing))
        }
    }

    private fun buildSectionCard(
        sec: QtOutline.Section, range: String, verses: List<String>, existing: QtSection?
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_qt_card)
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        }

        // 헤더: (제목 — 의미 있을 때만) + 절 범위 + 제목편집(✎)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (sec.title.isNotBlank()) {
            headerText.addView(TextView(this).apply {
                text = sec.title
                textSize = 16f
                setTextColor(getColor(R.color.note_accent))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        headerText.addView(TextView(this).apply {
            text = "${sec.from}–${sec.to}절"
            textSize = 12f
            setTextColor(getColor(R.color.ink_soft))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        headerRow.addView(headerText)
        headerRow.addView(TextView(this).apply {
            text = "✎ 제목"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.ink_soft))
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            setBackgroundResource(R.drawable.bg_chapter_chip)
            setOnClickListener { showRenameDialog(range) }
        })
        card.addView(headerRow)
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt())
        })
        // 요약(있을 때만)
        if (sec.summary.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = sec.summary
                textSize = 13f
                setTextColor(getColor(R.color.ink_soft))
                setLineSpacing((3 * dp), 1f)
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
        }
        // 구절
        val sb = StringBuilder()
        for (v in sec.from..sec.to) verses.getOrNull(v - 1)?.let {
            sb.append(v).append(". ").append(it).append("\n")
        }
        card.addView(TextView(this).apply {
            text = sb.toString().trimEnd()
            textSize = versesFontSize()
            setTextColor(getThemeOnSurface())
            setLineSpacing((5 * dp), 1f)
            setPadding(0, 0, 0, (10 * dp).toInt())
        })

        // 묵상
        val medEdit = makeInput("이 단락을 통해 깨달은 것을 적어 보세요")
        val medCheck = makeCheck("묵상 기록", medEdit)
        card.addView(medCheck); card.addView(medEdit)

        // 기도
        val prayerEdit = makeInput("이 단락으로 드리는 기도를 적어 보세요")
        val prayerCheck = makeCheck("기도 기록", prayerEdit)
        card.addView(prayerCheck.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * dp).toInt() })
        card.addView(prayerEdit)

        // 사진
        val photoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val photoScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(photoRow)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
        }
        card.addView(photoScroll)

        val holderIndex = holders.size
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() }
        }
        addRow.addView(makeSmallButton("+ 사진") { pickGallery(holderIndex) })
        addRow.addView(makeSmallButton("촬영") { launchCamera(holderIndex) }
            .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = (8 * dp).toInt() })
        card.addView(addRow)

        val photos = ArrayList(existing?.photoPaths?.filter { File(it).exists() } ?: emptyList())
        val holder = SectionHolder(range, medCheck, medEdit, prayerCheck, prayerEdit, photoRow, photos)
        holders.add(holder)

        // 기존 내용 채우기
        existing?.let {
            medEdit.setText(it.meditation); prayerEdit.setText(it.prayer)
            medCheck.isChecked = it.meditation.isNotBlank()
            prayerCheck.isChecked = it.prayer.isNotBlank()
        }
        medEdit.visibility = if (medCheck.isChecked) View.VISIBLE else View.GONE
        prayerEdit.visibility = if (prayerCheck.isChecked) View.VISIBLE else View.GONE
        refreshPhotoRow(holder)
        return card
    }

    private fun makeInput(hint: String) = EditText(this).apply {
        setBackgroundResource(R.drawable.bg_note_input)
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        textSize = 15f; minLines = 2; maxLines = 12
        gravity = Gravity.TOP
        setTextColor(getColor(R.color.note_text))
        setHintTextColor(getColor(R.color.note_subtle))
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4 * dp).toInt() }
        visibility = View.GONE
    }

    private fun makeCheck(label: String, target: EditText) = CheckBox(this).apply {
        text = label; textSize = 14f
        setTextColor(getColor(R.color.note_accent))
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnCheckedChangeListener { _, on ->
            target.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun makeSmallButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(getColor(R.color.ink_soft))
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        setBackgroundResource(R.drawable.bg_chapter_chip)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    // ── 저장/삭제 ──
    private fun collectEntry(now: Long): QtEntry {
        val secs = holders.map { h ->
            QtSection(
                h.range,
                if (h.medCheck.isChecked) h.medEdit.text.toString().trim() else "",
                if (h.prayerCheck.isChecked) h.prayerEdit.text.toString().trim() else "",
                ArrayList(h.photos)
            )
        }.filter { !it.isEmpty() }
        return QtEntry(bookIndex, book()?.nameKo ?: "", chapter, secs, now)
    }

    private fun saveEntry(bumpDate: Boolean, toast: Boolean) {
        val now = if (bumpDate || entryUpdatedAt <= 0) System.currentTimeMillis() else entryUpdatedAt
        val entry = collectEntry(now)
        QtStore.save(this, entry)
        entryUpdatedAt = if (entry.isEmpty()) 0L else now
        binding.qtDate.text = if (entry.isEmpty()) "기록 없음"
            else "최근 QT ${dateFmt.format(Date(now))}"
        buildChapterChips()
        if (toast) Toast.makeText(
            this,
            if (entry.isEmpty()) "기록 내용이 없습니다" else "QT 저장됨 · ${dateFmt.format(Date(now))}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun persistNow() = saveEntry(bumpDate = false, toast = false)

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("QT 기록 삭제")
            .setMessage("${book()?.nameKo} ${chapter}장의 모든 묵상·기도·사진 기록을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                QtStore.delete(this, "$bookIndex-$chapter")
                entryUpdatedAt = 0L
                renderChapter(); buildChapterChips()
                Toast.makeText(this, "삭제되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null).show()
    }

    // ── 사진 ──
    private fun pickGallery(section: Int) {
        if ((holders.getOrNull(section)?.photos?.size ?: 0) >= 6) {
            Toast.makeText(this, "단락당 사진은 최대 6장", Toast.LENGTH_SHORT).show(); return
        }
        pendingSection = section
        startActivityForResult(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), REQ_GALLERY)
    }

    private fun launchCamera(section: Int) {
        if ((holders.getOrNull(section)?.photos?.size ?: 0) >= 6) {
            Toast.makeText(this, "단락당 사진은 최대 6장", Toast.LENGTH_SHORT).show(); return
        }
        pendingSection = section
        try {
            val h = holders[section]
            val dest = QtStore.newPhotoFile(this, "$bookIndex-$chapter-${h.range}")
            pendingCameraPath = dest.absolutePath
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dest)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) startActivityForResult(intent, REQ_CAMERA)
            else { pendingCameraPath = null; Toast.makeText(this, "카메라 앱이 없습니다", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            pendingCameraPath = null
            Toast.makeText(this, "카메라 실행 실패", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val h = holders.getOrNull(pendingSection) ?: return

        if (requestCode == REQ_GALLERY) {
            val uri = data?.data ?: return
            val dest = QtStore.newPhotoFile(this, "$bookIndex-$chapter-${h.range}")
            try {
                contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(dest).use { inp.copyTo(it) } }
                h.photos.add(dest.absolutePath); persistNow(); refreshPhotoRow(h)
            } catch (e: Exception) {
                Toast.makeText(this, "사진 저장 실패", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_CAMERA) {
            val path = pendingCameraPath; pendingCameraPath = null
            if (path == null) return
            val f = File(path)
            if (!f.exists() || f.length() == 0L) { runCatching { f.delete() }; return }
            try {
                NoteStore.normalizeOrientation(path)
                h.photos.add(path); persistNow(); refreshPhotoRow(h)
            } catch (e: Exception) {
                Toast.makeText(this, "사진 저장 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPhotoRow(h: SectionHolder) {
        h.photoRow.removeAllViews()
        val size = (72 * dp).toInt(); val margin = (8 * dp).toInt(); val btnSz = (24 * dp).toInt()
        h.photos.filter { File(it).exists() }.forEach { path ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = margin }
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF12152A.toInt())
                try { setImageBitmap(BitmapFactory.decodeFile(path)) } catch (_: Exception) {}
            }
            val ctrl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() }
            }
            val rotateBtn = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply { marginEnd = (10 * dp).toInt() }
                text = "↻"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(0xFFC8CEE5.toInt()); setBackgroundColor(0x33FFFFFF)
                setOnClickListener {
                    if (NoteStore.rotatePhoto(path)) {
                        BitmapFactory.decodeFile(path)?.let { iv.setImageBitmap(it) }; persistNow()
                    } else Toast.makeText(this@QtActivity, "회전 실패", Toast.LENGTH_SHORT).show()
                }
            }
            val delBtn = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
                text = "✕"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(0xFFEF5350.toInt()); setBackgroundColor(0x33FFFFFF)
                setOnClickListener {
                    runCatching { File(path).delete() }
                    h.photos.remove(path); persistNow(); refreshPhotoRow(h)
                }
            }
            ctrl.addView(rotateBtn); ctrl.addView(delBtn)
            col.addView(iv); col.addView(ctrl)
            h.photoRow.addView(col)
        }
    }

    // ── 단락 편집 ──
    private fun showDivideDialog() {
        val verseCount = primaryVerses().size
        if (verseCount <= 1) {
            Toast.makeText(this, "나눌 절이 없습니다", Toast.LENGTH_SHORT).show(); return
        }
        val currentEnds = currentSections.map { it.to }.dropLast(1)
        val input = EditText(this).apply {
            setText(currentEnds.joinToString(", "))
            hint = "예: 9, 13, 20"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("단락 나누기 (1–${verseCount}절)")
            .setMessage("각 단락의 '끝 절' 번호를 쉼표로 입력하세요.\n비우면 장 전체가 한 단락이 됩니다.\n(다시 나누면 이전 묵상이 새 단락에 보이지 않을 수 있어요.)")
            .setView(input)
            .setPositiveButton("적용") { _, _ ->
                saveEntry(bumpDate = false, toast = false)
                val ends = input.text.toString().split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
                QtLayoutStore.setSplits(this, bookIndex, chapter, ends, verseCount)
                renderChapter()
                Toast.makeText(this, "단락을 다시 나눴습니다", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("기본값") { _, _ ->
                saveEntry(bumpDate = false, toast = false)
                QtLayoutStore.clear(this, bookIndex, chapter)
                renderChapter()
                Toast.makeText(this, "기본 단락으로 되돌렸습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showRenameDialog(range: String) {
        val cur = currentSections.find { "${it.from}-${it.to}" == range }
        val input = EditText(this).apply {
            setText(cur?.title ?: "")
            hint = "단락 제목 (비우면 표시 안 함)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("단락 제목 (${range.replace("-", "–")}절)")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                saveEntry(bumpDate = false, toast = false)
                QtLayoutStore.setTitle(this, bookIndex, chapter, currentSections, range, input.text.toString().trim())
                renderChapter()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) saveEntry(bumpDate = false, toast = false)
    }
}
