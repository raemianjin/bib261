package com.example.bibleapp.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.R
import com.example.bibleapp.data.Book
import com.example.bibleapp.data.SearchResult

sealed class SearchItem {
    data class SectionHeader(val title: String, val showClear: Boolean = false) : SearchItem()
    data class HistoryItem(val query: String) : SearchItem()
    data class BookItem(val book: Book) : SearchItem()
    data class VerseItem(val result: SearchResult, val highlight: String) : SearchItem()
}

class SearchResultAdapter(
    private val onHistoryClick: (String) -> Unit,
    private val onHistoryDelete: (String) -> Unit,
    private val onHistoryClearAll: () -> Unit,
    private val onVerseClick: (bookIndex: Int, chapter: Int, verse: Int) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchItem>()

    // 페이지네이션
    private var allResults: List<SearchResult> = emptyList()
    private var displayedCount = 0
    private val PAGE_SIZE = 100
    private var currentQuery = ""

    companion object {
        private const val T_HEADER  = 0
        private const val T_HISTORY = 1
        private const val T_BOOK    = 2
        private const val T_VERSE   = 3
    }

    fun showHistory(history: List<String>) {
        allResults = emptyList(); displayedCount = 0; currentQuery = ""
        items.clear()
        if (history.isNotEmpty()) {
            items.add(SearchItem.SectionHeader("최근 검색어", showClear = true))
            history.forEach { items.add(SearchItem.HistoryItem(it)) }
        }
        notifyDataSetChanged()
    }

    fun showBookResults(books: List<Book>) {
        allResults = emptyList(); displayedCount = 0; currentQuery = ""
        items.clear()
        if (books.isEmpty()) items.add(SearchItem.SectionHeader("검색 결과 없음"))
        else {
            items.add(SearchItem.SectionHeader("성경 제목 ${books.size}건"))
            books.forEach { items.add(SearchItem.BookItem(it)) }
        }
        notifyDataSetChanged()
    }

    fun showResults(results: List<SearchResult>, query: String) {
        allResults = results
        currentQuery = query
        displayedCount = minOf(PAGE_SIZE, results.size)
        rebuildResultItems()
    }

    // 추가 로드 (스크롤 끝 도달 시)
    fun loadMore(): Boolean {
        if (displayedCount >= allResults.size) return false
        val prev = displayedCount
        displayedCount = minOf(displayedCount + PAGE_SIZE, allResults.size)
        rebuildResultItems()
        return true
    }

    fun hasMore() = displayedCount < allResults.size
    fun totalCount() = allResults.size
    fun shownCount() = displayedCount

    private fun rebuildResultItems() {
        items.clear()
        val headerText = if (allResults.isEmpty()) "검색 결과 없음"
            else if (allResults.size > displayedCount) "구절 검색 결과 ${displayedCount}건 표시 중 (총 ${allResults.size}건)"
            else "구절 검색 결과 ${allResults.size}건"
        items.add(SearchItem.SectionHeader(headerText))
        for (i in 0 until displayedCount) {
            items.add(SearchItem.VerseItem(allResults[i], currentQuery))
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(pos: Int) = when (items[pos]) {
        is SearchItem.SectionHeader -> T_HEADER
        is SearchItem.HistoryItem   -> T_HISTORY
        is SearchItem.BookItem      -> T_BOOK
        is SearchItem.VerseItem     -> T_VERSE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_HEADER  -> HeaderVH(inf.inflate(R.layout.item_search_header, parent, false))
            T_HISTORY -> HistoryVH(inf.inflate(R.layout.item_search_history, parent, false))
            T_BOOK    -> BookVH(inf.inflate(R.layout.item_search_result, parent, false))
            else      -> VerseVH(inf.inflate(R.layout.item_search_result, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        when (val item = items[pos]) {
            is SearchItem.SectionHeader -> (holder as HeaderVH).bind(item)
            is SearchItem.HistoryItem   -> (holder as HistoryVH).bind(item.query)
            is SearchItem.BookItem      -> (holder as BookVH).bind(item.book)
            is SearchItem.VerseItem     -> (holder as VerseVH).bind(item.result, item.highlight)
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv   = view.findViewById<TextView>(R.id.header_text)
        private val btnX = view.findViewById<TextView>(R.id.btn_clear_all)
        fun bind(item: SearchItem.SectionHeader) {
            tv.text = item.title
            if (item.showClear) {
                btnX.visibility = View.VISIBLE
                btnX.setOnClickListener { onHistoryClearAll() }
            } else {
                btnX.visibility = View.GONE
            }
        }
    }

    inner class HistoryVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv   = view.findViewById<TextView>(R.id.history_text)
        private val btnX = view.findViewById<TextView>(R.id.btn_delete)
        fun bind(query: String) {
            tv.text = query
            itemView.setOnClickListener { onHistoryClick(query) }
            btnX.setOnClickListener { onHistoryDelete(query) }
        }
    }

    inner class BookVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ref  = view.findViewById<TextView>(R.id.result_ref)
        private val text = view.findViewById<TextView>(R.id.result_text)
        fun bind(book: Book) {
            ref.text = "${book.nameKo}  (${book.nameEn})"
            text.text = "${book.chapterCount}장 · ${if (book.isOldTestament) "구약" else "신약"}"
            itemView.setOnClickListener { onVerseClick(book.index, 0, -1) }
        }
    }

    inner class VerseVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ref  = view.findViewById<TextView>(R.id.result_ref)
        private val text = view.findViewById<TextView>(R.id.result_text)
        fun bind(result: SearchResult, highlight: String) {
            ref.text = "${result.bookName} ${result.chapter}:${result.verse}"
            val spannable = SpannableString(result.text)
            val lower = result.text.lowercase()
            val q = highlight.lowercase()
            val color = ref.context.resources.getColor(R.color.gold, null)
            var idx = lower.indexOf(q)
            while (idx >= 0 && q.isNotEmpty()) {
                spannable.setSpan(ForegroundColorSpan(color), idx, idx + q.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                idx = lower.indexOf(q, idx + 1)
            }
            text.text = spannable
            itemView.setOnClickListener {
                onVerseClick(result.bookIndex, result.chapter - 1, result.verse - 1)
            }
        }
    }
}
