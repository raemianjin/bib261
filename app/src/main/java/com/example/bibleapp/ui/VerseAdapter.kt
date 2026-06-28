package com.example.bibleapp.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.R
import com.example.bibleapp.databinding.ItemVerseBinding

class VerseAdapter(
    private var fontSize: Float,
    private var lineSpacing: Float,
    private val reference: () -> String,   // "창세기 1장" 형태
    private val onGesture: (verseIndex: Int, gesture: VerseGesture) -> Unit,
    private var showKjv: Boolean = false,
    private var kjvVerses: List<String> = emptyList()
) : RecyclerView.Adapter<VerseAdapter.VH>() {

    private var verses: List<String> = emptyList()
    private var bookmarked: Set<Int> = emptySet()
    private var noted: Set<Int> = emptySet()
    private var aiNoted: Set<Int> = emptySet()

    /** 다중 복사 시작점으로 표시할 구절 (0-based, null이면 표시 없음) */
    private var multiStartIndex: Int? = null

    /** TTS 현재 읽는 구절 (0-based, null이면 없음) */
    private var readingIndex: Int? = null
    fun setReadingPosition(i: Int?) {
        if (readingIndex == i) return
        val old = readingIndex
        readingIndex = i
        old?.let { notifyItemChanged(it) }
        i?.let { notifyItemChanged(it) }
    }

    // ── 연속 탭(1/2/3회) 판별용 상태 ──
    private val tapHandler = Handler(Looper.getMainLooper())
    private var tapRunnable: Runnable? = null
    private var tapVerse = -1
    private var tapCount = 0
    private val TAP_WINDOW_MS = 280L

    fun submit(verses: List<String>, bookmarkedVerses: Set<Int> = emptySet(), notedVerses: Set<Int> = emptySet(), aiNotedVerses: Set<Int> = emptySet()) {
        this.verses = verses
        this.bookmarked = bookmarkedVerses
        this.noted = notedVerses
        this.aiNoted = aiNotedVerses
        notifyDataSetChanged()
    }

    fun setFontSize(size: Float) { fontSize = size; notifyDataSetChanged() }
    fun setLineSpacing(v: Float) { lineSpacing = v; notifyDataSetChanged() }
    fun setShowKjv(show: Boolean, kjv: List<String>) {
        showKjv = show; kjvVerses = kjv; notifyDataSetChanged()
    }

    /** 다중 복사 시작점 하이라이트 갱신 */
    fun setMultiStart(index: Int?) {
        if (multiStartIndex == index) return
        multiStartIndex = index
        notifyDataSetChanged()
    }

    fun verseText(index: Int): String? = verses.getOrNull(index)
    fun count(): Int = verses.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVerseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = verses.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(position, verses[position])
    }

    // ── 탭 카운트 누적 후 디스패치 ──
    private fun registerTap(index: Int) {
        if (index != tapVerse) { tapVerse = index; tapCount = 0 }
        tapCount += 1
        tapRunnable?.let { tapHandler.removeCallbacks(it) }
        val captured = index
        val r = Runnable {
            val g = when (tapCount) {
                1 -> VerseGesture.SINGLE
                2 -> VerseGesture.DOUBLE
                else -> VerseGesture.TRIPLE   // 3회 이상은 모두 트리플로 처리
            }
            tapCount = 0; tapVerse = -1
            onGesture(captured, g)
        }
        tapRunnable = r
        tapHandler.postDelayed(r, TAP_WINDOW_MS)
    }

    inner class VH(private val b: ItemVerseBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(index: Int, text: String) {
            val verseNum = index + 1
            b.verseNum.text = verseNum.toString()
            b.verseText.text = text
            b.verseText.textSize = fontSize
            b.verseText.setLineSpacing(lineSpacing * b.root.context.resources.displayMetrics.density, 1f)

            if (showKjv && index < kjvVerses.size) {
                b.verseTextKjv.visibility = View.VISIBLE
                b.verseTextKjv.text = kjvVerses[index]
                b.verseTextKjv.textSize = (fontSize * 0.80f).coerceAtLeast(11f)
            } else {
                b.verseTextKjv.visibility = View.GONE
            }

            val ctx = b.root.context
            val isBm    = bookmarked.contains(verseNum)
            val hasNote = noted.contains(verseNum)
            val isMultiStart = multiStartIndex == index

            b.verseRoot.setBackgroundColor(
                when {
                    readingIndex == index -> 0x33F5D062.toInt()   // 읽는 중(반투명 골드)
                    isMultiStart -> ctx.getColor(R.color.multicopy_start)
                    isBm         -> ctx.getColor(R.color.verse_highlight)
                    else         -> android.graphics.Color.TRANSPARENT
                }
            )

            b.noteDot.visibility = if (hasNote) View.VISIBLE else View.GONE
            b.noteDotAi.visibility = if (aiNoted.contains(verseNum)) View.VISIBLE else View.GONE

            b.root.setOnClickListener { registerTap(index) }
            b.root.setOnLongClickListener {
                // 진행 중이던 탭 카운트는 취소하고 롱프레스 처리
                tapRunnable?.let { tapHandler.removeCallbacks(it) }
                tapCount = 0; tapVerse = -1
                onGesture(index, VerseGesture.LONG)
                true
            }
        }
    }
}
