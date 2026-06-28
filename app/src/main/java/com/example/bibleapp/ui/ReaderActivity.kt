package com.example.bibleapp.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.R
import com.example.bibleapp.data.*
import com.example.bibleapp.databinding.ActivityReaderBinding
import com.example.bibleapp.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var book: Book
    private var chapter = 0
    private var targetVerse = -1

    private lateinit var verseAdapter: VerseAdapter
    private lateinit var gestureDetector: GestureDetector

    // 병행 표기 상태
    private var parallelOn = false
    private var koPrimary  = true   // true=KO메인, false=EN메인

    // 메모 다이얼로그 상태
    private var noteVerseIndex   = -1          // 현재 열린 메모 구절 (0-based)
    private var activeNoteDialog: AlertDialog? = null
    private var activePhotoContainer: LinearLayout? = null

    // 다중 복사 상태 (같은 책·같은 장 안에서만 구간 복사)
    private var multiBook  = -1
    private var multiChap  = -1
    private var multiStart = -1                // 시작 구절 (0-based), -1이면 비활성

    // 카메라 촬영 임시 경로
    private var pendingCameraPath: String? = null

    // ── TTS(음성 읽기) 상태 ──
    private var ttsManager: com.example.bibleapp.util.TtsManager? = null
    private var ttsReading = false
    private var ttsItems: List<TtsSeg> = emptyList()
    private var ttsIdx = 0
    private data class TtsSeg(val verse: Int, val lang: String, val text: String)

    companion object {
        const val EXTRA_BOOK    = "book_index"
        const val EXTRA_CHAPTER = "chapter_index"
        const val EXTRA_VERSE   = "verse_index"   // 0-based
        private const val REQ_GALLERY = 902
        private const val REQ_CAMERA  = 903
        private const val MULTI_COPY_LIMIT = 100
    }

    // ════════════════════════════════════════════════════
    // onCreate
    // ════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityReaderBinding.inflate(layoutInflater)
            setContentView(binding.root)

            if (!BibleRepository.isLoaded) BibleRepository.load(applicationContext)

            val bookIndex = intent.getIntExtra(EXTRA_BOOK, 1)
            targetVerse   = intent.getIntExtra(EXTRA_VERSE, -1)
            val found = BibleRepository.bookByIndex(bookIndex)
            if (found == null) { finish(); return }
            book    = found
            chapter = intent.getIntExtra(EXTRA_CHAPTER, 0).coerceIn(0, book.chapterCount - 1)

            // 뒤로가기
            binding.btnBack.setOnClickListener { finish() }

            // 병행 초기 상태 복원
            parallelOn = BookmarkStore.getShowParallel(this)
            koPrimary  = BookmarkStore.getKoPrimary(this)
            applyToggleUI()

            // ── 노랑 토글: 병행 ON/OFF
            binding.btnParallel.setOnClickListener {
                parallelOn = !parallelOn
                if (!parallelOn) {
                    // 병행 끄면 swap도 리셋
                    koPrimary = (BibleRepository.currentVersion == BibleRepository.Version.KRV)
                }
                BookmarkStore.setShowParallel(this, parallelOn)
                BookmarkStore.setKoPrimary(this, koPrimary)
                applyToggleUI()
                showChapter()
            }

            // ── 주황 토글: 메인/서브 전환 (병행 ON일 때만)
            binding.btnSwap.setOnClickListener {
                if (!parallelOn) return@setOnClickListener
                koPrimary = !koPrimary
                BookmarkStore.setKoPrimary(this, koPrimary)
                applyToggleUI()
                showChapter()
            }

            // ── 폰트 버튼
            binding.btnFont.setOnClickListener { showReadingSettings() }

            // ── VerseAdapter 초기화
            verseAdapter = VerseAdapter(
                fontSize    = BookmarkStore.getFontSize(this),
                lineSpacing = BookmarkStore.getLineSpacing(this),
                reference   = { "${book.nameKo} ${chapter + 1}장" },
                onGesture   = { index, gesture -> handleVerseGesture(index, gesture) }
            )
            binding.verseList.layoutManager = LinearLayoutManager(this)
            binding.verseList.adapter = verseAdapter

            // ── TTS(음성 읽기) 초기화
            ttsManager = com.example.bibleapp.util.TtsManager(this).also { it.init() }
            binding.btnTts.setOnClickListener { ttsTap() }

            // ── 태블릿(패드): 넓은 화면을 충분히 활용하도록 좌우 여백만 살짝 두고 넓게 표시
            if (com.example.bibleapp.util.DeviceUtil.isTablet(this)) {
                val dp = resources.displayMetrics.density
                val screenPx = resources.displayMetrics.widthPixels
                // 좌우 여백은 화면 폭의 약 5%(24~72dp 범위)만 — 나머지는 본문이 넓게 사용
                val side = (screenPx * 0.05f).toInt()
                    .coerceIn((24 * dp).toInt(), (72 * dp).toInt())
                binding.verseList.setPadding(
                    side, (8 * dp).toInt(), side, (80 * dp).toInt()
                )
                binding.verseList.clipToPadding = false
            }

            // ── 스와이프 제스처 (좌우 장 이동)
            gestureDetector = GestureDetector(this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                        val dx = (e2.x - (e1?.x ?: e2.x))
                        val dy = (e2.y - (e1?.y ?: e2.y))
                        if (abs(dx) > abs(dy) * 1.2f && abs(dx) > 80 && abs(vX) > 200) {
                            if (dx < 0) moveChapterAnim(chapter + 1, true)
                            else        moveChapterAnim(chapter - 1, false)
                            return true
                        }
                        return false
                    }
                })
            binding.verseList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    gestureDetector.onTouchEvent(e); return false
                }
            })

            // ── 스크롤이 멈출 때마다 화면 최상단 절을 마지막 위치로 저장
            binding.verseList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val lm = rv.layoutManager as? LinearLayoutManager ?: return
                        val first = lm.findFirstVisibleItemPosition()
                        if (first >= 0) {
                            BookmarkStore.saveLastPosition(this@ReaderActivity, book.index, chapter)
                            BookmarkStore.saveLastVerse(this@ReaderActivity, first)
                        }
                    }
                }
            })

            binding.btnPrev.setOnClickListener { moveChapterAnim(chapter - 1, false) }
            binding.btnNext.setOnClickListener { moveChapterAnim(chapter + 1, true) }

            buildChapterBar()
            showChapter(scrollToVerse = targetVerse)

        } catch (e: Exception) {
            AppLogger.e("Reader", "onCreate: ${e.message}")
            finish()
        }
    }

    // ════════════════════════════════════════════════════
    // 토글 UI 적용
    // ════════════════════════════════════════════════════
    private fun applyToggleUI() {
        if (parallelOn) {
            // 노랑 활성
            binding.btnParallel.setBackgroundResource(R.drawable.bg_toggle_yellow)
            binding.btnParallel.setTextColor(0xFF222222.toInt())
            // 주황: koPrimary 여부에 따라
            binding.btnSwap.setBackgroundResource(R.drawable.bg_toggle_orange)
            binding.btnSwap.setTextColor(0xFFFFFFFF.toInt())
            binding.btnSwap.alpha = 1f
            binding.btnSwap.isClickable = true
        } else {
            // 노랑 비활성
            binding.btnParallel.setBackgroundResource(R.drawable.bg_toggle_off)
            binding.btnParallel.setTextColor(0xCCFFFFFF.toInt())
            // 주황 비활성
            binding.btnSwap.setBackgroundResource(R.drawable.bg_toggle_off)
            binding.btnSwap.setTextColor(0xCCFFFFFF.toInt())
            binding.btnSwap.alpha = 0.35f
            binding.btnSwap.isClickable = false
        }
    }

    // ════════════════════════════════════════════════════
    // 장 이동 (애니메이션)
    // ════════════════════════════════════════════════════
    private fun moveChapterAnim(target: Int, toRight: Boolean) {
        if (ttsReading) stopReading()
        val w = binding.verseList.width.toFloat().takeIf { it > 0 } ?: 800f
        when {
            target in 0 until book.chapterCount -> {
                val outX = if (toRight) -w * 0.35f else w * 0.35f
                val inX  = if (toRight)  w * 0.35f else -w * 0.35f
                ObjectAnimator.ofFloat(binding.verseList, "translationX", 0f, outX)
                    .apply { duration = 140 }.start()
                binding.verseList.postDelayed({
                    chapter = target
                    showChapter()
                    binding.verseList.translationX = inX
                    ObjectAnimator.ofFloat(binding.verseList, "translationX", inX, 0f)
                        .apply { duration = 140 }.start()
                }, 140)
            }
            target < 0 -> {
                val prev = BibleRepository.bookByIndex(book.index - 1) ?: return
                book = prev; chapter = book.chapterCount - 1
                buildChapterBar(); showChapter()
                Toast.makeText(this, book.nameKo, Toast.LENGTH_SHORT).show()
            }
            else -> {
                val next = BibleRepository.bookByIndex(book.index + 1) ?: return
                book = next; chapter = 0
                buildChapterBar(); showChapter()
                Toast.makeText(this, book.nameKo, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ════════════════════════════════════════════════════
    // 장 칩 바 빌드
    // ════════════════════════════════════════════════════
    private fun buildChapterBar() {
        binding.chapterBar.removeAllViews()
        val dp = resources.displayMetrics.density
        for (c in 0 until book.chapterCount) {
            val chip = TextView(this).apply {
                text = (c + 1).toString(); textSize = 13f; gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chapter_chip)
                val hPad = (10 * dp).toInt(); val vPad = (5 * dp).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (5 * dp).toInt() }
                setOnClickListener { moveChapterAnim(c, c > chapter) }
            }
            binding.chapterBar.addView(chip)
        }
    }

    // ════════════════════════════════════════════════════
    // 장 표시 (핵심)
    // ════════════════════════════════════════════════════
    private fun showChapter(scrollToVerse: Int = -1) {
        binding.toolbarTitle.text = "${book.nameKo} ${chapter + 1}장"

        val krvList = BibleRepository.getKrvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        val kjvList = BibleRepository.getKjvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()

        val primaryVerses: List<String>
        val secondaryVerses: List<String>

        when {
            !parallelOn -> {
                // 병행 OFF: 현재 버전 단일 표시
                primaryVerses   = if (BibleRepository.currentVersion == BibleRepository.Version.KRV) krvList else kjvList
                secondaryVerses = emptyList()
            }
            koPrimary -> {
                // 병행 ON, 한국어 메인
                primaryVerses   = krvList
                secondaryVerses = kjvList
            }
            else -> {
                // 병행 ON, 영어 메인
                primaryVerses   = kjvList
                secondaryVerses = krvList
            }
        }

        // 메모가 있는 구절 번호 (1-based) 수집
        val chapterNoted = NoteStore.all(this)
            .filter { it.bookIndex == book.index && it.chapter == chapter + 1 }
            .map { it.verse }
            .toSet()

        val chapterAiNoted = AiNoteStore.firstVersesIn(this, book.index, chapter + 1)
        verseAdapter.submit(primaryVerses, bookmarkedVerses(), chapterNoted, chapterAiNoted)
        // 다중 복사 시작점 하이라이트: 같은 책·같은 장일 때만 표시
        verseAdapter.setMultiStart(
            if (multiStart >= 0 && book.index == multiBook && chapter == multiChap) multiStart else null
        )
        verseAdapter.setShowKjv(
            parallelOn && secondaryVerses.isNotEmpty() && secondaryVerses !== primaryVerses,
            secondaryVerses
        )

        // 마지막 위치 저장
        BookmarkStore.saveLastPosition(this, book.index, chapter)

        // 칩 하이라이트 + 스크롤
        highlightChip()
        binding.chapterScroll.post {
            val child = binding.chapterBar.getChildAt(chapter) ?: return@post
            val cx = child.left - (binding.chapterScroll.width / 2) + (child.width / 2)
            binding.chapterScroll.smoothScrollTo(cx.coerceAtLeast(0), 0)
        }

        // ── 절 위치로 스크롤 (이어서 읽기 or 검색 결과)
        if (scrollToVerse in 0 until primaryVerses.size) {
            BookmarkStore.saveLastVerse(this, scrollToVerse)
            binding.verseList.post {
                val lm = binding.verseList.layoutManager as LinearLayoutManager
                val offset = (binding.verseList.height * 0.20f).toInt()
                lm.scrollToPositionWithOffset(scrollToVerse, offset)
                // 깜빡임 하이라이트
                binding.verseList.postDelayed({
                    val vh = binding.verseList.findViewHolderForAdapterPosition(scrollToVerse)
                    vh?.itemView?.animate()
                        ?.setDuration(180)?.alpha(0.25f)
                        ?.withEndAction {
                            vh.itemView.animate().setDuration(280).alpha(1f).start()
                        }?.start()
                }, 80)
            }
        } else {
            // 일반 진입: 기존 저장 위치를 덮어쓰지 않음 (스크롤 리스너가 관리)
            binding.verseList.scrollToPosition(0)
        }
    }

    private fun highlightChip() {
        for (i in 0 until binding.chapterBar.childCount) {
            val tv = binding.chapterBar.getChildAt(i) as? TextView ?: continue
            if (i == chapter) {
                tv.setTextColor(getColor(R.color.white))
                tv.setBackgroundColor(getColor(R.color.indigo))
            } else {
                tv.setBackgroundResource(R.drawable.bg_chapter_chip)
                tv.setTextColor(getColor(R.color.ink_soft))
            }
        }
    }

    private fun bookmarkedVerses(): Set<Int> {
        val prefix = "${book.index}-${chapter + 1}-"
        return BookmarkStore.all(this).filter { it.key.startsWith(prefix) }
            .map { it.verse }.toSet()
    }

    // ════════════════════════════════════════════════════
    // 북마크 토글
    // ════════════════════════════════════════════════════
    private fun toggleBookmark(verseIndex: Int) {
        val krvList = BibleRepository.getKrvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        val kjvList = BibleRepository.getKjvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        val primList = when {
            !parallelOn -> if (BibleRepository.currentVersion == BibleRepository.Version.KRV) krvList else kjvList
            koPrimary   -> krvList
            else        -> kjvList
        }
        val text = primList.getOrNull(verseIndex) ?: return
        val bm = Bookmark(book.index, book.nameKo, chapter + 1, verseIndex + 1, text)
        val added = BookmarkStore.toggle(this, bm)
        showChapter()
        Toast.makeText(this,
            if (added) R.string.bookmarked else R.string.bookmark_removed,
            Toast.LENGTH_SHORT).show()
    }

    // ════════════════════════════════════════════════════
    // 구절 제스처 → 동작 디스패치
    // ════════════════════════════════════════════════════
    /** 현재 메인(표시 중) 역본의 구절 리스트 */
    private fun primaryList(): List<String> {
        val krvList = BibleRepository.getKrvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        val kjvList = BibleRepository.getKjvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        return when {
            !parallelOn -> if (BibleRepository.currentVersion == BibleRepository.Version.KRV) krvList else kjvList
            koPrimary   -> krvList
            else        -> kjvList
        }
    }

    private fun handleVerseGesture(index: Int, gesture: VerseGesture) {
        val slot = when (gesture) {
            VerseGesture.SINGLE -> 0
            VerseGesture.DOUBLE -> 1
            VerseGesture.TRIPLE -> 2
            VerseGesture.LONG   -> 3
        }
        when (VerseAction.from(BookmarkStore.getGestureAction(this, slot))) {
            VerseAction.NONE      -> { /* 아무 동작 없음 */ }
            VerseAction.MENU      -> showVerseMenu(index)
            VerseAction.MULTICOPY -> multiCopyToggle(index)
            VerseAction.BOOKMARK  -> toggleBookmark(index)
            VerseAction.COPY_ONE  -> copyOneVerse(index)
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("verse", text))
    }

    private fun copyOneVerse(index: Int) {
        val text = primaryList().getOrNull(index) ?: return
        val ref  = "${book.nameKo} ${chapter + 1}장 ${index + 1}절"
        copyToClipboard("$ref\n$text")
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ── 다중 복사: 시작/끝 토글 (세번 탭 또는 팝업 버튼 공용) ──
    private fun multiCopyToggle(index: Int) {
        if (multiStart < 0) {
            // 시작점 지정
            multiBook = book.index; multiChap = chapter; multiStart = index
            verseAdapter.setMultiStart(index)
            Toast.makeText(this,
                "다중 복사 시작: ${book.nameKo} ${chapter + 1}:${index + 1}\n끝 구절에서 한 번 더 실행하세요",
                Toast.LENGTH_SHORT).show()
            return
        }
        // 끝점 — 시작점과 같은 책·장이 아니면 시작점을 여기로 재설정
        if (book.index != multiBook || chapter != multiChap) {
            multiBook = book.index; multiChap = chapter; multiStart = index
            verseAdapter.setMultiStart(index)
            Toast.makeText(this, "다른 장이라 시작점을 여기로 변경했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val from = minOf(multiStart, index)
        var to   = maxOf(multiStart, index)
        var count = to - from + 1
        var capped = false
        if (count > MULTI_COPY_LIMIT) {
            to = from + MULTI_COPY_LIMIT - 1
            count = MULTI_COPY_LIMIT
            capped = true
        }
        val list = primaryList()
        val sb = StringBuilder()
        sb.append("${book.nameKo} ${chapter + 1}:${from + 1}-${to + 1}\n")
        for (v in from..to) list.getOrNull(v)?.let { sb.append("${v + 1} ").append(it).append("\n") }
        copyToClipboard(sb.toString().trimEnd())
        resetMultiCopy()
        Toast.makeText(this,
            if (capped) "${count}구절 복사됨 (100구절 제한)" else "${count}구절 복사됨",
            Toast.LENGTH_SHORT).show()
    }

    private fun resetMultiCopy() {
        multiStart = -1; multiBook = -1; multiChap = -1
        verseAdapter.setMultiStart(null)
    }

    // ════════════════════════════════════════════════════
    // 구절 팝업 메뉴 (하단 시트)
    // ════════════════════════════════════════════════════
    private fun showVerseMenu(index: Int) {
        val ref      = "${book.nameKo} ${chapter + 1}장 ${index + 1}절"
        val text     = primaryList().getOrNull(index) ?: ""
        val fullText = "$ref\n$text"
        val isBm     = bookmarkedVerses().contains(index + 1)
        val hasNote  = NoteStore.get(this, "${book.index}-${chapter + 1}-${index + 1}") != null

        val view = layoutInflater.inflate(R.layout.dialog_verse_menu, null)
        view.findViewById<TextView>(R.id.menu_ref).text = ref

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 제목 우측 다중복사 버튼
        val mcBtn = view.findViewById<TextView>(R.id.menu_multicopy)
        val mcActive = multiStart >= 0 && book.index == multiBook && chapter == multiChap
        mcBtn.text = if (mcActive) "여기까지 복사" else "다중복사"
        mcBtn.setOnClickListener { multiCopyToggle(index); dialog.dismiss() }

        view.findViewById<TextView>(R.id.menu_copy).setOnClickListener {
            copyToClipboard(fullText)
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menu_share).setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, fullText)
                }, "공유"))
            dialog.dismiss()
        }
        val bmTv = view.findViewById<TextView>(R.id.menu_bookmark)
        bmTv.text = if (isBm) "북마크 제거" else "북마크 추가"
        bmTv.setOnClickListener { toggleBookmark(index); dialog.dismiss() }

        val noteTv = view.findViewById<TextView>(R.id.menu_note)
        noteTv.text = if (hasNote) "메모 보기 / 수정" else "메모 추가"
        noteTv.setOnClickListener { openNoteDialog(index); dialog.dismiss() }

        view.findViewById<TextView>(R.id.menu_search).setOnClickListener {
            val q = Uri.encode(fullText.take(100))
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=$q")))
            dialog.dismiss()
        }

        // AI 메뉴: 'AI 기능 사용'이 켜져 있을 때만 노출
        val aiEnabled = com.example.bibleapp.data.AiStore.isAiEnabled(this)
        val aiTv = view.findViewById<TextView>(R.id.menu_ai)
        val aiViewTv = view.findViewById<TextView>(R.id.menu_ai_view)
        if (aiEnabled) {
            aiTv.visibility = View.VISIBLE
            aiTv.setOnClickListener { dialog.dismiss(); sendVersesToAi(index) }
            val aiKey = "${book.index}-${chapter + 1}-${index + 1}"
            if (AiNoteStore.get(this, aiKey) != null) {
                aiViewTv.visibility = View.VISIBLE
                aiViewTv.setOnClickListener { dialog.dismiss(); showAiNoteDialog(index + 1) }
            } else aiViewTv.visibility = View.GONE
        } else {
            aiTv.visibility = View.GONE
            aiViewTv.visibility = View.GONE
        }

        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            val lp = attributes
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.y = (16 * resources.displayMetrics.density).toInt()
            attributes = lp
        }
    }

    // ════════════════════════════════════════════════════
    // 메모 다이얼로그
    // ════════════════════════════════════════════════════
    private fun openNoteDialog(verseIndex: Int) {
        noteVerseIndex = verseIndex
        val verseNum = verseIndex + 1
        val noteKey  = "${book.index}-${chapter + 1}-$verseNum"
        val existing = NoteStore.get(this, noteKey)
        val now = System.currentTimeMillis()
        val dtFmt = SimpleDateFormat("yyyy년 MM월 dd일 HH:mm:ss", Locale.KOREA)

        val dv = layoutInflater.inflate(R.layout.dialog_note, null)
        val editTv   = dv.findViewById<EditText>(R.id.note_edit)
        val countTv  = dv.findViewById<TextView>(R.id.note_count)
        val dtTv     = dv.findViewById<TextView>(R.id.note_datetime)
        val refTv    = dv.findViewById<TextView>(R.id.note_ref)
        val photoBox = dv.findViewById<LinearLayout>(R.id.photo_container)
        val btnGal   = dv.findViewById<Button>(R.id.btn_gallery)
        val btnCam   = dv.findViewById<Button>(R.id.btn_camera)
        val btnPub   = dv.findViewById<Button>(R.id.btn_publish)
        val btnCopy  = dv.findViewById<Button>(R.id.btn_copy_all)
        val btnDel   = dv.findViewById<Button>(R.id.btn_delete_note)

        activePhotoContainer = photoBox

        refTv.text = "${book.nameKo} ${chapter + 1}:$verseNum"
        // 첫 줄: 일시 (별도 라인). 신규는 현재 시각, 기존은 최종수정 시각
        dtTv.text = if (existing != null)
            dtFmt.format(Date(existing.updatedAt))
        else
            dtFmt.format(Date(now))

        if (existing != null) editTv.setText(existing.text)
        countTv.text = "${editTv.text.length} / 1000"

        editTv.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                countTv.text = "${s?.length ?: 0} / 1000"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshPhotos(photoBox, existing?.photoPaths ?: emptyList(), noteKey)

        // 갤러리에서만 사진 추가
        btnGal.setOnClickListener {
            val cur = NoteStore.get(this, noteKey)
            if ((cur?.photoPaths?.size ?: 0) >= 4) {
                Toast.makeText(this, "사진은 최대 4장까지 가능합니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivityForResult(
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), REQ_GALLERY)
        }

        // 카메라로 직접 촬영해서 바로 첨부
        btnCam.setOnClickListener {
            val cur = NoteStore.get(this, noteKey)
            if ((cur?.photoPaths?.size ?: 0) >= 4) {
                Toast.makeText(this, "사진은 최대 4장까지 가능합니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            launchCamera(noteKey)
        }

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("note", editTv.text.toString()))
            Toast.makeText(this, "메모가 복사됐습니다", Toast.LENGTH_SHORT).show()
        }

        btnDel.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("메모 삭제")
                .setMessage("이 구절의 메모와 사진을 모두 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    NoteStore.delete(this, noteKey)
                    activeNoteDialog?.dismiss()
                    showChapter()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 저장 처리 헬퍼
        fun persistNote(): Boolean {
            val txt = editTv.text.toString()
            val photos = NoteStore.get(this, noteKey)?.photoPaths ?: emptyList()
            return if (txt.isNotBlank() || photos.isNotEmpty()) {
                NoteStore.save(this, VerseNote(
                    bookIndex   = book.index,
                    bookNameKo  = book.nameKo,
                    chapter     = chapter + 1,
                    verse       = verseNum,
                    text        = txt,
                    createdAt   = existing?.createdAt ?: now,
                    updatedAt   = System.currentTimeMillis(),
                    photoPaths  = photos
                ))
                true
            } else false
        }

        // 발행 — 저장 후 뷰잉 장표로 이동
        btnPub.setOnClickListener {
            if (persistNote()) {
                activeNoteDialog?.dismiss()
                showChapter()
                startActivity(Intent(this, NoteViewActivity::class.java).apply {
                    putExtra(NoteViewActivity.EXTRA_KEY, noteKey)
                })
            } else {
                Toast.makeText(this, "메모나 사진을 입력하세요", Toast.LENGTH_SHORT).show()
            }
        }

        activeNoteDialog = AlertDialog.Builder(this)
            .setView(dv)
            .setPositiveButton("저장") { _, _ ->
                persistNote()
                showChapter()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ── 사진 썸네일 갱신
    private fun refreshPhotos(container: LinearLayout, paths: List<String>, noteKey: String) {
        container.removeAllViews()
        val dp     = resources.displayMetrics.density
        val size   = (76 * dp).toInt()
        val margin = (8 * dp).toInt()
        val btnSz  = (24 * dp).toInt()

        val valid = paths.filter { File(it).exists() }

        for (path in valid) {
            // 사진 + 하단 컨트롤(회전/삭제)을 담는 세로 컨테이너
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = margin }
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF12152A.toInt())
                try { setImageBitmap(BitmapFactory.decodeFile(path)) } catch (_: Exception) {}
            }

            // 컨트롤 바: 회전 / 삭제
            val ctrl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() }
                gravity = Gravity.CENTER
            }

            val rotateBtn = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
                    .apply { marginEnd = (10 * dp).toInt() }
                text = "↻"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(0xFFC8CEE5.toInt())
                setBackgroundColor(0x33FFFFFF)
                setOnClickListener {
                    if (NoteStore.rotatePhoto(path)) {
                        // 같은 파일을 다시 디코딩하여 갱신
                        BitmapFactory.decodeFile(path)?.let { iv.setImageBitmap(it) }
                        // 메모 updatedAt 갱신
                        NoteStore.get(this@ReaderActivity, noteKey)?.let { cur ->
                            NoteStore.save(this@ReaderActivity,
                                cur.copy(updatedAt = System.currentTimeMillis()))
                        }
                    } else {
                        Toast.makeText(this@ReaderActivity, "회전 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val delBtn = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
                text = "✕"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(0xFFEF5350.toInt())
                setBackgroundColor(0x33FFFFFF)
                setOnClickListener {
                    File(path).delete()
                    val cur = NoteStore.get(this@ReaderActivity, noteKey) ?: return@setOnClickListener
                    val updated = cur.copy(
                        photoPaths = cur.photoPaths.filter { it != path },
                        updatedAt  = System.currentTimeMillis()
                    )
                    if (updated.text.isNotBlank() || updated.photoPaths.isNotEmpty())
                        NoteStore.save(this@ReaderActivity, updated)
                    else
                        NoteStore.delete(this@ReaderActivity, noteKey)
                    refreshPhotos(container, updated.photoPaths, noteKey)
                }
            }

            ctrl.addView(rotateBtn)
            ctrl.addView(delBtn)
            col.addView(iv)
            col.addView(ctrl)
            container.addView(col)
        }

        // 사진이 하나도 없을 때 안내 (+ 기호 대신 텍스트)
        if (valid.isEmpty()) {
            val hint = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, size)
                gravity = Gravity.CENTER_VERTICAL
                text = "첨부된 사진 없음"
                textSize = 12f
                setTextColor(0xFF7A82A0.toInt())
            }
            container.addView(hint)
        }
    }

    // ════════════════════════════════════════════════════
    // 사진 결과 처리
    // ════════════════════════════════════════════════════
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || noteVerseIndex < 0) return

        val noteKey = "${book.index}-${chapter + 1}-${noteVerseIndex + 1}"
        val existing = NoteStore.get(this, noteKey)
        val photos   = existing?.photoPaths?.toMutableList() ?: mutableListOf()
        if (photos.size >= 4) return

        if (requestCode == REQ_GALLERY) {
            val uri = data?.data ?: return
            val dest = NoteStore.newPhotoFile(this, noteKey)
            try {
                contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(dest).use { out -> inp.copyTo(out) }
                }
                photos.add(dest.absolutePath)
                val note = existing?.copy(photoPaths = photos, updatedAt = System.currentTimeMillis())
                    ?: VerseNote(book.index, book.nameKo, chapter + 1, noteVerseIndex + 1,
                        "", System.currentTimeMillis(), System.currentTimeMillis(), photos)
                NoteStore.save(this, note)
                activePhotoContainer?.let { refreshPhotos(it, photos, noteKey) }
            } catch (e: Exception) {
                Toast.makeText(this, "사진 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQ_CAMERA) {
            val path = pendingCameraPath
            pendingCameraPath = null
            if (path == null) return
            val f = File(path)
            if (!f.exists() || f.length() == 0L) {
                Toast.makeText(this, "촬영된 사진을 찾지 못했습니다", Toast.LENGTH_SHORT).show()
                f.delete()
                return
            }
            try {
                // 카메라 사진 회전(EXIF) 보정
                NoteStore.normalizeOrientation(path)
                photos.add(path)
                val note = existing?.copy(photoPaths = photos, updatedAt = System.currentTimeMillis())
                    ?: VerseNote(book.index, book.nameKo, chapter + 1, noteVerseIndex + 1,
                        "", System.currentTimeMillis(), System.currentTimeMillis(), photos)
                NoteStore.save(this, note)
                activePhotoContainer?.let { refreshPhotos(it, photos, noteKey) }
            } catch (e: Exception) {
                Toast.makeText(this, "사진 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 카메라 앱으로 촬영 → FileProvider URI에 직접 저장 (CAMERA 권한 불필요)
    private fun launchCamera(noteKey: String) {
        try {
            val dest = NoteStore.newPhotoFile(this, noteKey)
            pendingCameraPath = dest.absolutePath
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dest)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, REQ_CAMERA)
            } else {
                pendingCameraPath = null
                Toast.makeText(this, "카메라 앱을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            pendingCameraPath = null
            Toast.makeText(this, "카메라 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ════════════════════════════════════════════════════
    // 읽기 설정 (폰트 + 줄간격)
    // ════════════════════════════════════════════════════
    private fun showReadingSettings() {
        val fontSizes = floatArrayOf(13f, 15f, 17f, 19f, 22f, 26f)
        val lineGaps  = floatArrayOf(2f, 4f, 6f, 9f, 12f, 16f)

        val v = layoutInflater.inflate(R.layout.dialog_reading_settings, null)
        val preview  = v.findViewById<TextView>(R.id.preview_text)
        val seekFont = v.findViewById<android.widget.SeekBar>(R.id.seek_font)
        val seekLine = v.findViewById<android.widget.SeekBar>(R.id.seek_line)

        seekFont.max = fontSizes.size - 1
        seekLine.max = lineGaps.size - 1
        seekFont.progress = fontSizes.indexOfFirst { it == BookmarkStore.getFontSize(this) }.coerceAtLeast(2)
        seekLine.progress = lineGaps.indexOfFirst { it == BookmarkStore.getLineSpacing(this) }.coerceAtLeast(2)

        fun upd() {
            preview.textSize = fontSizes[seekFont.progress]
            preview.setLineSpacing(lineGaps[seekLine.progress] * resources.displayMetrics.density, 1f)
        }
        upd()

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) = upd()
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        }
        seekFont.setOnSeekBarChangeListener(listener)
        seekLine.setOnSeekBarChangeListener(listener)

        AlertDialog.Builder(this).setTitle("읽기 설정").setView(v)
            .setPositiveButton("적용") { _, _ ->
                val fs = fontSizes[seekFont.progress]
                val ls = lineGaps[seekLine.progress]
                BookmarkStore.setFontSize(this, fs)
                BookmarkStore.setLineSpacing(this, ls)
                verseAdapter.setFontSize(fs)
                verseAdapter.setLineSpacing(ls)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ════════════════════════════════════════════════════
    // 음성 읽기 (TTS) — 한국어/영어, 여성/남성, 시작 확인 · 즉시 중단 · 이어 읽기
    // ════════════════════════════════════════════════════
    private fun ttsTap() {
        if (ttsReading) stopReading() else showTtsStartDialog()
    }

    private fun showTtsStartDialog() {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f
            setTextColor(getColor(R.color.ink_soft))
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val langs = listOf("both" to "한·영", "ko" to "한국어", "en" to "영어")
        val gens  = listOf("female" to "여성", "male" to "남성")
        val curLang = BookmarkStore.getTtsLang(this)
        val curGen  = BookmarkStore.getTtsGender(this)
        val langGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        langs.forEachIndexed { i, (v, t) ->
            langGroup.addView(RadioButton(this).apply { id = 1000 + i; text = t; isChecked = (v == curLang) })
        }
        val genGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        gens.forEachIndexed { i, (v, t) ->
            genGroup.addView(RadioButton(this).apply { id = 2000 + i; text = t; isChecked = (v == curGen) })
        }
        val rates = listOf(0.8f to "느리게", 1.0f to "보통", 1.25f to "빠르게", 1.5f to "매우 빠르게")
        val curRate = BookmarkStore.getTtsRate(this)
        val rateGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rates.forEachIndexed { i, (v, t) ->
            rateGroup.addView(RadioButton(this).apply { id = 3000 + i; text = t; isChecked = (v == curRate) })
        }
        if (rateGroup.checkedRadioButtonId == -1) (rateGroup.getChildAt(1) as RadioButton).isChecked = true
        root.addView(label("언어")); root.addView(langGroup)
        root.addView(label("목소리")); root.addView(genGroup)
        root.addView(label("읽기 속도")); root.addView(rateGroup)

        AlertDialog.Builder(this)
            .setTitle("음성 읽기")
            .setMessage("현재 보이는 위치부터 읽어 드립니다. 기기에 설치된 음성을 사용하며 오프라인에서도 동작합니다.")
            .setView(root)
            .setPositiveButton("시작") { _, _ ->
                val li = langGroup.checkedRadioButtonId - 1000
                val gi = genGroup.checkedRadioButtonId - 2000
                val lang = langs.getOrElse(li) { langs[0] }.first
                val gen  = gens.getOrElse(gi) { gens[0] }.first
                BookmarkStore.setTtsLang(this, lang)
                BookmarkStore.setTtsGender(this, gen)
                val ri = rateGroup.checkedRadioButtonId - 3000
                BookmarkStore.setTtsRate(this, rates.getOrElse(ri) { rates[1] }.first)
                val first = (binding.verseList.layoutManager as? LinearLayoutManager)
                    ?.findFirstVisibleItemPosition() ?: 0
                startReading(first.coerceAtLeast(0))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startReading(fromVerse: Int) {
        val mgr = ttsManager ?: return
        if (!mgr.isReady()) {
            Toast.makeText(this, "음성 엔진을 준비 중입니다. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val krv = BibleRepository.getKrvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        val kjv = BibleRepository.getKjvBook(book.index)?.chapters?.getOrNull(chapter) ?: emptyList()
        val lang = BookmarkStore.getTtsLang(this)
        val items = ArrayList<TtsSeg>()
        val n = maxOf(krv.size, kjv.size)
        for (v in fromVerse until n) {
            if (lang == "ko" || lang == "both") krv.getOrNull(v)?.takeIf { it.isNotBlank() }?.let { items.add(TtsSeg(v, "ko", it)) }
            if (lang == "en" || lang == "both") kjv.getOrNull(v)?.takeIf { it.isNotBlank() }?.let { items.add(TtsSeg(v, "en", it)) }
        }
        if (items.isEmpty()) { Toast.makeText(this, "읽을 내용이 없습니다", Toast.LENGTH_SHORT).show(); return }
        ttsItems = items; ttsIdx = 0; ttsReading = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateTtsButton()
        speakNext()
    }

    private fun speakNext() {
        if (!ttsReading) return
        if (ttsIdx >= ttsItems.size) { onChapterReadDone(); return }
        val seg = ttsItems[ttsIdx]
        highlightReading(seg.verse)
        val gen = BookmarkStore.getTtsGender(this)
        val rate = BookmarkStore.getTtsRate(this)
        ttsManager?.speak(seg.text, seg.lang, gen, rate) {
            if (ttsReading) { ttsIdx++; speakNext() }
        }
    }

    private fun highlightReading(v: Int) {
        verseAdapter.setReadingPosition(v)
        (binding.verseList.layoutManager as? LinearLayoutManager)?.let { lm ->
            val offset = (binding.verseList.height * 0.25f).toInt()
            lm.scrollToPositionWithOffset(v, offset)
        }
    }

    private fun onChapterReadDone() {
        val hasNextInBook = chapter + 1 < book.chapterCount
        val nextBook = if (!hasNextInBook) BibleRepository.bookByIndex(book.index + 1) else null
        if (!hasNextInBook && nextBook == null) {
            stopReading(); Toast.makeText(this, "성경 전체를 다 읽었습니다", Toast.LENGTH_SHORT).show(); return
        }
        val label = if (hasNextInBook) "${book.nameKo} ${chapter + 2}장" else "${nextBook!!.nameKo} 1장"
        AlertDialog.Builder(this)
            .setTitle("이어서 읽기")
            .setMessage("${book.nameKo} ${chapter + 1}장을 다 읽었습니다.\n다음 ($label)을 이어서 읽을까요?")
            .setPositiveButton("이어 읽기") { _, _ -> ttsGoNextChapter() }
            .setNegativeButton("그만") { _, _ -> stopReading() }
            .setOnCancelListener { stopReading() }
            .show()
    }

    private fun ttsGoNextChapter() {
        if (chapter + 1 < book.chapterCount) {
            chapter += 1
            showChapter()
        } else {
            val next = BibleRepository.bookByIndex(book.index + 1) ?: run { stopReading(); return }
            book = next; chapter = 0
            buildChapterBar(); showChapter()
        }
        binding.verseList.post { startReading(0) }
    }

    private fun stopReading() {
        ttsReading = false
        ttsManager?.stop()
        verseAdapter.setReadingPosition(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateTtsButton()
    }

    /** 다이얼로그 본문이 잘 보이도록 테마의 onSurface(가독 높은) 색을 해석 */
    private fun readableTextColor(): Int {
        val t = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, t, true)
        return if (t.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT)
            t.data else getColor(R.color.note_text)
    }

    private fun updateTtsButton() {
        if (ttsReading) {
            binding.btnTts.text = "■"
            binding.btnTts.setTextColor(0xFFFFFFFF.toInt())
            binding.btnTts.setBackgroundResource(R.drawable.bg_toggle_orange)
        } else {
            binding.btnTts.text = "TTS"
            binding.btnTts.setTextColor(0xFF222222.toInt())
            binding.btnTts.setBackgroundResource(R.drawable.bg_toggle_off)
        }
    }

    // ════════════════════════════════════════════════════
    // AI 연동 (구절 → 프롬프트 → 결과 → AI 메모 저장)
    // ════════════════════════════════════════════════════
    private fun aiRange(index: Int): Pair<Int, Int> {
        return if (multiStart >= 0 && book.index == multiBook && chapter == multiChap)
            minOf(multiStart, index) to maxOf(multiStart, index)
        else index to index
    }

    private fun sendVersesToAi(index: Int) {
        if (com.example.bibleapp.data.AiStore.getKey(this).isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("AI 키 필요")
                .setMessage("먼저 설정 > AI 설정에서 API 키를 입력/저장하세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        val (from, to) = aiRange(index)
        val list = primaryList()
        val ref = "${book.nameKo} ${chapter + 1}:${from + 1}" + if (to > from) "-${to + 1}" else ""
        val sb = StringBuilder()
        for (v in from..to) list.getOrNull(v)?.let { sb.append("${v + 1} ").append(it).append("\n") }
        val prompt = com.example.bibleapp.data.AiStore.getVersePrompt(this) + "\n\n[$ref]\n" + sb.toString().trimEnd()

        val progress = AlertDialog.Builder(this).setMessage("AI에게 묻는 중…").setCancelable(false).create()
        progress.show()
        Thread {
            val result = runCatching { com.example.bibleapp.util.AiClient.complete(this, prompt) }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { showAiResultDialog(ref, from + 1, to + 1, it) }
                    .onFailure {
                        AlertDialog.Builder(this).setTitle("AI 오류")
                            .setMessage(it.message ?: "알 수 없는 오류").setPositiveButton("확인", null).show()
                    }
            }
        }.start()
    }

    private fun showAiResultDialog(ref: String, vFrom: Int, vTo: Int, text: String) {
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val tv = TextView(this).apply {
            setText(text); textSize = 16f
            isFocusable = true; isFocusableInTouchMode = true; setTextIsSelectable(true)
            setLineSpacing(5 * dp, 1f); setPadding(pad, (8 * dp).toInt(), pad, 0)
            setTextColor(readableTextColor())
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("AI · $ref")
            .setView(scroll)
            .setPositiveButton("AI 메모로 저장") { _, _ -> saveAiMemoFlow(vFrom, vTo, text) }
            .setNeutralButton("복사") { _, _ ->
                copyToClipboard(text); Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun saveAiMemoFlow(vFrom: Int, vTo: Int, text: String) {
        val key = "${book.index}-${chapter + 1}-$vFrom"
        val existing = AiNoteStore.get(this, key)
        if (existing == null) { doSaveAiMemo(vFrom, vTo, text); return }
        // 덮어쓰기 확인 — 기존 내용 확인 후 결정
        AlertDialog.Builder(this)
            .setTitle("기존 AI 메모 있음")
            .setMessage("이 구절에 이미 AI 메모가 있습니다.\n기존을 삭제하고 새 내용으로 덮어쓸까요?")
            .setPositiveButton("덮어쓰기") { _, _ -> doSaveAiMemo(vFrom, vTo, text) }
            .setNeutralButton("기존 내용 확인") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("기존 AI 메모 내용")
                    .setMessage(existing.text)
                    .setPositiveButton("이대로 덮어쓰기") { _, _ -> doSaveAiMemo(vFrom, vTo, text) }
                    .setNegativeButton("취소(유지)", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doSaveAiMemo(vFrom: Int, vTo: Int, text: String) {
        AiNoteStore.save(this, com.example.bibleapp.data.AiNote(
            bookIndex = book.index, bookNameKo = book.nameKo, chapter = chapter + 1,
            verseFrom = vFrom, verseTo = vTo, text = text, updatedAt = System.currentTimeMillis()
        ))
        resetMultiCopy()
        showChapter()
        Toast.makeText(this, "AI 메모 저장됨 (${book.nameKo} ${chapter + 1}:$vFrom${if (vTo > vFrom) "-$vTo" else ""})", Toast.LENGTH_SHORT).show()
    }

    /** AI 메모 보기/수정 (첫 구절 번호 1-based) */
    private fun showAiNoteDialog(verseFrom: Int) {
        val key = "${book.index}-${chapter + 1}-$verseFrom"
        val note = AiNoteStore.get(this, key) ?: return
        val dp = resources.displayMetrics.density
        val edit = EditText(this).apply {
            setText(note.text); textSize = 15f
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
            setLineSpacing(4 * dp, 1f); gravity = Gravity.TOP
        }
        val scroll = ScrollView(this).apply { addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("AI 메모 · ${note.reference}")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                AiNoteStore.save(this, note.copy(text = edit.text.toString(), updatedAt = System.currentTimeMillis()))
                showChapter()
                Toast.makeText(this, "수정됨", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("삭제") { _, _ ->
                AiNoteStore.delete(this, key); showChapter()
                Toast.makeText(this, "삭제됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    override fun onDestroy() {
        ttsManager?.shutdown()
        super.onDestroy()
    }
}
