package com.example.bibleapp.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.data.BibleRepository
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.databinding.FragmentBibleBinding
import com.example.bibleapp.util.AppLogger

class BibleFragment : Fragment() {

    private var _binding: FragmentBibleBinding? = null
    private val binding get() = _binding!!
    private lateinit var bookAdapter: BookAdapter
    private lateinit var searchAdapter: SearchResultAdapter
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBibleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bookAdapter = BookAdapter { book -> openBook(book.index, 0) }
        binding.bookList.layoutManager =
            if (com.example.bibleapp.util.DeviceUtil.isTablet(requireContext()))
                androidx.recyclerview.widget.GridLayoutManager(requireContext(),
                    if (com.example.bibleapp.util.DeviceUtil.isWide(requireContext())) 3 else 2)
            else LinearLayoutManager(requireContext())
        binding.bookList.adapter = bookAdapter
        refreshBookList()

        searchAdapter = SearchResultAdapter(
            onHistoryClick = { q ->
                binding.searchEdit.setText(q)
                binding.searchEdit.setSelection(q.length)
                runSearch(q, binding.checkTitleSearch.isChecked, immediate = true)
            },
            onHistoryDelete = { q ->
                BookmarkStore.removeSearchHistory(requireContext(), q)
                searchAdapter.showHistory(BookmarkStore.getSearchHistory(requireContext()))
            },
            onHistoryClearAll = {
                BookmarkStore.clearSearchHistory(requireContext())
                searchAdapter.showHistory(emptyList())
            },
            onVerseClick = { bookIndex, chapter, verse ->
                closeSearchOverlay()
                openBook(bookIndex, chapter, verse)
            },
            onLoadMore = { /* RecyclerView scroll listener 처리 */ }
        )
        binding.searchResultList.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResultList.adapter = searchAdapter

        // 스크롤 끝 감지 → 추가 로드
        binding.searchResultList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(1) && searchAdapter.hasMore()) {
                    searchAdapter.loadMore()
                }
            }
        })

        binding.btnOt.setOnClickListener { scrollToBook(1) }
        binding.btnNt.setOnClickListener { scrollToBook(40) }

        updateVersionBtn()
        binding.versionBtn.setOnClickListener { btn ->
            val popup = PopupMenu(requireContext(), btn)
            popup.menu.add(0, 0, 0, "개역개정")
            popup.menu.add(0, 1, 1, "영어 (KJV)")
            popup.setOnMenuItemClickListener { item ->
                val ctx = requireContext()
                when (item.itemId) {
                    0 -> BibleRepository.switchVersion(BibleRepository.Version.KRV)
                    1 -> BibleRepository.switchVersion(BibleRepository.Version.KJV)
                }
                BookmarkStore.setShowParallel(ctx, false)
                AppLogger.i("Bible", "역본 변경→병행 해제")
                updateVersionBtn(); refreshBookList(); true
            }
            popup.show()
        }

        binding.btnSearch.setOnClickListener { openSearchOverlay() }
        binding.btnSearchClose.setOnClickListener { closeSearchOverlay() }

        binding.btnDoSearch.setOnClickListener {
            val q = binding.searchEdit.text?.toString()?.trim() ?: ""
            if (q.isNotBlank()) runSearch(q, binding.checkTitleSearch.isChecked, immediate = true)
        }

        binding.searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = binding.searchEdit.text?.toString()?.trim() ?: ""
                if (q.isNotBlank()) runSearch(q, binding.checkTitleSearch.isChecked, immediate = true)
                true
            } else false
        }

        // 실시간 검색: 2자 이상부터 400ms 디바운스
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s?.toString()?.trim() ?: ""
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                when {
                    q.length >= 2 -> {
                        // 2자 이상: 400ms 후 자동 검색
                        val r = Runnable {
                            if (isAdded) runSearch(q, binding.checkTitleSearch.isChecked, immediate = false)
                        }
                        searchRunnable = r
                        searchHandler.postDelayed(r, 400)
                    }
                    q.isEmpty() -> {
                        searchAdapter.showHistory(BookmarkStore.getSearchHistory(requireContext()))
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() { super.onResume(); refreshContinueCard() }

    private fun updateVersionBtn() {
        binding.versionBtn.text = when (BibleRepository.currentVersion) {
            BibleRepository.Version.KRV -> "개역개정"
            BibleRepository.Version.KJV -> "영어 (KJV)"
        }
    }

    private fun refreshBookList() { bookAdapter.submit(BibleRepository.allBooks()) }

    private fun scrollToBook(bookIndex: Int) {
        val pos = BibleRepository.allBooks().indexOfFirst { it.index == bookIndex }
        if (pos >= 0) (binding.bookList.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(pos, 0)
    }

    private fun refreshContinueCard() {
        val ctx = context ?: return
        val book = BibleRepository.bookByIndex(BookmarkStore.getLastBook(ctx))
        val ch = BookmarkStore.getLastChapter(ctx)
        if (book != null) {
            binding.continueCard.visibility = View.VISIBLE
            val verse = BookmarkStore.getLastVerse(ctx)
            binding.continueLabel.text =
                if (verse >= 0) "${book.nameKo} ${ch + 1}장 ${verse + 1}절"
                else "${book.nameKo} ${ch + 1}장"
            binding.continueCard.setOnClickListener { openBook(book.index, ch, verse) }
        } else {
            binding.continueCard.visibility = View.GONE
        }
    }

    private fun openSearchOverlay() {
        binding.searchOverlay.visibility = View.VISIBLE
        binding.searchEdit.setText("")
        searchAdapter.showHistory(BookmarkStore.getSearchHistory(requireContext()))
        binding.searchEdit.requestFocus()
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(binding.searchEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchOverlay() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        binding.searchOverlay.visibility = View.GONE
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchEdit.windowToken, 0)
    }

    private fun runSearch(query: String, titleOnly: Boolean, immediate: Boolean) {
        val ctx = context ?: return
        if (!BibleRepository.isLoaded) BibleRepository.load(ctx.applicationContext)
        if (immediate) {
            BookmarkStore.addSearchHistory(ctx, query)
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.searchEdit.windowToken, 0)
        }
        if (titleOnly) {
            searchAdapter.showBookResults(BibleRepository.searchBooks(query))
        } else {
            val results = BibleRepository.searchVerses(query)
            AppLogger.i("Search", "'$query' → ${results.size}건")
            searchAdapter.showResults(results, query)
        }
    }

    private fun openBook(bookIndex: Int, chapter: Int, verse: Int = -1) {
        if (BookmarkStore.isQtMode(requireContext())) {
            startActivity(Intent(requireContext(), QtActivity::class.java).apply {
                putExtra(QtActivity.EXTRA_BOOK, bookIndex)
                putExtra(QtActivity.EXTRA_CHAPTER, chapter)
            })
            return
        }
        startActivity(Intent(requireContext(), ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_BOOK, bookIndex)
            putExtra(ReaderActivity.EXTRA_CHAPTER, chapter)
            if (verse >= 0) putExtra(ReaderActivity.EXTRA_VERSE, verse)
        })
    }


    fun isSearchOpen(): Boolean = _binding?.searchOverlay?.visibility == android.view.View.VISIBLE
    fun closeSearch() = closeSearchOverlay()

    override fun onDestroyView() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        super.onDestroyView(); _binding = null
    }
}
