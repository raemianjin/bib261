package com.example.bibleapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.data.NoteStore
import com.example.bibleapp.data.VerseNote
import com.example.bibleapp.databinding.FragmentNoteBinding

class NoteFragment : Fragment() {

    private var _b: FragmentNoteBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: NoteAdapter

    private val PAGE = 50
    private var allNotes: List<VerseNote> = emptyList()
    private var displayed = 0
    private var sortBySize = false

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentNoteBinding.inflate(inf, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = NoteAdapter { note -> openViewer(note) }
        adapter.onSelectionChange = { cnt ->
            b.selectedCount.text = "${cnt}개 선택됨"
        }

        b.noteList.layoutManager =
            if (com.example.bibleapp.util.DeviceUtil.isTablet(requireContext())) {
                val span = if (com.example.bibleapp.util.DeviceUtil.isWide(requireContext())) 3 else 2
                androidx.recyclerview.widget.GridLayoutManager(requireContext(), span)
            } else {
                LinearLayoutManager(requireContext())
            }
        b.noteList.adapter = adapter
        if (b.noteList.layoutManager is LinearLayoutManager &&
            b.noteList.layoutManager !is androidx.recyclerview.widget.GridLayoutManager) {
            b.noteList.addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }

        // 스크롤 끝 → 다음 50개
        b.noteList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(1) && displayed < allNotes.size) {
                    displayed = minOf(displayed + PAGE, allNotes.size)
                    adapter.submit(allNotes.take(displayed))
                }
            }
        })

        // 정렬 토글
        b.btnSortSize.setOnClickListener {
            sortBySize = !sortBySize
            b.btnSortSize.text = if (sortBySize) "최신순" else "용량순"
            refresh()
        }

        // 멀티선택 모드
        b.btnMultiselect.setOnClickListener {
            val entering = !adapter.multiSelect
            adapter.multiSelect = entering
            b.btnMultiselect.text = if (entering) "취소" else "선택"
            b.multiActionBar.visibility = if (entering) View.VISIBLE else View.GONE
            if (entering) b.selectedCount.text = "0개 선택됨"
        }

        // 선택 삭제
        b.btnDeleteSelected.setOnClickListener {
            val keys = adapter.selected.toSet()
            if (keys.isEmpty()) { Toast.makeText(requireContext(), "선택된 항목이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setTitle("${keys.size}개 메모 삭제")
                .setMessage("선택한 메모와 첨부 사진을 모두 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    NoteStore.deleteMultiple(requireContext(), keys)
                    adapter.multiSelect = false
                    b.btnMultiselect.text = "선택"
                    b.multiActionBar.visibility = View.GONE
                    refresh()
                }
                .setNegativeButton("취소", null).show()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val ctx  = context ?: return
        val raw  = NoteStore.all(ctx)
        allNotes = if (sortBySize) raw.sortedByDescending { NoteStore.totalBytes(it) }
                   else            raw.sortedByDescending { it.updatedAt }

        displayed = minOf(PAGE, allNotes.size)

        if (allNotes.isEmpty()) {
            b.emptyNote.visibility = View.VISIBLE
            b.noteList.visibility  = View.GONE
        } else {
            b.emptyNote.visibility = View.GONE
            b.noteList.visibility  = View.VISIBLE
            adapter.submit(allNotes.take(displayed))
        }

        val totalBytes = allNotes.sumOf { NoteStore.totalBytes(it) }
        val totalStr = when {
            totalBytes > 1_048_576 -> "%.1fMB".format(totalBytes / 1_048_576.0)
            totalBytes > 1024      -> "${totalBytes / 1024}KB"
            else                   -> "${totalBytes}B"
        }
        b.noteSummary.text = "총 ${allNotes.size}개 · $totalStr"
    }

    // ── 목록 클릭 → 뷰잉 장표(NoteViewActivity)
    private fun openViewer(note: VerseNote) {
        startActivity(Intent(requireContext(), NoteViewActivity::class.java).apply {
            putExtra(NoteViewActivity.EXTRA_KEY, note.key)
        })
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
