package com.example.bibleapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.bibleapp.data.BibleRepository
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.data.NoteStore
import com.example.bibleapp.data.VerseNote
import com.example.bibleapp.databinding.ActivityNoteViewBinding
import com.example.bibleapp.util.DeviceUtil
import java.io.File
import java.io.FileOutputStream

class NoteViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteViewBinding
    private lateinit var note: VerseNote
    private var verseText: String = ""
    private var template = NoteCardRenderer.Template.CLASSIC

    companion object {
        const val EXTRA_KEY = "note_key"   // "bookIndex-chapter-verse"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!BibleRepository.isLoaded) BibleRepository.load(applicationContext)

        val key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        note = NoteStore.get(this, key) ?: run { finish(); return }

        verseText = try {
            BibleRepository.bookByIndex(note.bookIndex)
                ?.chapters?.getOrNull(note.chapter - 1)
                ?.getOrNull(note.verse - 1) ?: ""
        } catch (e: Exception) { "" }

        // 마지막 사용 템플릿 복원
        template = NoteCardRenderer.Template.values()
            .getOrElse(BookmarkStore.getNoteTemplate(this)) { NoteCardRenderer.Template.CLASSIC }

        buildTemplateChips()
        renderPreview()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { chooseFormatAndShare() }
    }

    // ── 템플릿 칩 ────────────────────────────────────────
    private fun buildTemplateChips() {
        binding.templateBar.removeAllViews()
        val dp = resources.displayMetrics.density
        NoteCardRenderer.Template.values().forEach { t ->
            val chip = TextView(this).apply {
                text = t.label
                textSize = 13f
                gravity = Gravity.CENTER
                val hPad = (16 * dp).toInt(); val vPad = (8 * dp).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
                setOnClickListener {
                    template = t
                    BookmarkStore.setNoteTemplate(this@NoteViewActivity, t.ordinal)
                    styleChips()
                    renderPreview()
                }
            }
            binding.templateBar.addView(chip)
        }
        styleChips()
    }

    private fun styleChips() {
        for (i in 0 until binding.templateBar.childCount) {
            val tv = binding.templateBar.getChildAt(i) as? TextView ?: continue
            val selected = i == template.ordinal
            if (selected) {
                tv.setBackgroundResource(com.example.bibleapp.R.drawable.bg_toggle_yellow)
                tv.setTextColor(0xFF222222.toInt())
                tv.setTypeface(tv.typeface, Typeface.BOLD)
            } else {
                tv.setBackgroundResource(com.example.bibleapp.R.drawable.bg_toggle_off)
                tv.setTextColor(0xCCFFFFFF.toInt())
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            }
        }
    }

    // ── 미리보기 렌더 ────────────────────────────────────
    private fun footerText(): String =
        BookmarkStore.getShareSignature(this).trim().ifBlank { "한영Bible" }

    private fun renderPreview() {
        binding.previewHost.removeAllViews()
        val card = NoteCardRenderer.build(
            this, note, verseText, template,
            NoteCardRenderer.ShareFormat.SCREEN, forCapture = false,
            footerText = footerText()
        )
        // 태블릿에서는 넓은 화면을 살려 카드 폭을 키우고 가운데 정렬, 폰은 꽉 채움
        val dp = resources.displayMetrics.density
        val maxW = (820 * dp).toInt()
        val lp = LinearLayout.LayoutParams(
            if (DeviceUtil.isTablet(this)) maxW else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        binding.previewHost.addView(card, lp)
    }

    // ── 공유: 폰용 / 패드용 선택 후 이미지 생성 ────────────
    private fun chooseFormatAndShare() {
        val options = arrayOf(
            "폰용 (세로 1080px)",
            "패드용 (가로 1600px)"
        )
        val defaultIdx = if (DeviceUtil.isTablet(this)) 1 else 0
        var picked = defaultIdx
        AlertDialog.Builder(this)
            .setTitle("공유 형식 선택")
            .setSingleChoiceItems(options, defaultIdx) { _, which -> picked = which }
            .setPositiveButton("공유") { d, _ ->
                d.dismiss()
                val fmt = if (picked == 1) NoteCardRenderer.ShareFormat.TABLET
                else NoteCardRenderer.ShareFormat.PHONE
                shareAsImage(fmt)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun shareAsImage(format: NoteCardRenderer.ShareFormat) {
        try {
            // 화면에 붙이지 않은 별도 카드를 고정 픽셀 폭으로 그려서 캡처
            val card = NoteCardRenderer.build(
                this, note, verseText, template, format, forCapture = true,
                footerText = footerText()
            )
            val bmp: Bitmap = NoteCardRenderer.renderToBitmap(card, format)

            val dir = File(cacheDir, "shared").also { it.mkdirs() }
            val tag = if (format == NoteCardRenderer.ShareFormat.TABLET) "pad" else "phone"
            val file = File(dir, "note_${tag}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "메모 장표 공유"))
        } catch (e: Exception) {
            Toast.makeText(this, "공유 준비 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
