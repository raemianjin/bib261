package com.example.bibleapp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bibleapp.data.AiStore
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.databinding.FragmentBookmarkBinding

class BookmarkFragment : Fragment() {

    private var _binding: FragmentBookmarkBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBookmarkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.cardBulletin.setOnClickListener {
            val url = BookmarkStore.getBulletinUrl(requireContext())
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        binding.cardBulletinAi.setOnClickListener {
            startActivity(Intent(requireContext(), BulletinAiActivity::class.java))
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        binding.cardBulletinAi.visibility =
            if (AiStore.isAiEnabled(requireContext())) View.VISIBLE else View.GONE
        refreshList()
    }

    private fun refreshList() {
        val ctx = context ?: return
        val bookmarks = BookmarkStore.all(ctx)
        if (bookmarks.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.bookmarkList.visibility = View.GONE
        } else {
            binding.emptyText.visibility = View.GONE
            binding.bookmarkList.visibility = View.VISIBLE
            val adapter = BookmarkAdapter(
                onClick = { bm ->
                    // 탭: 성경 본문으로 이동
                    val intent = Intent(ctx, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK, bm.bookIndex)
                        putExtra(ReaderActivity.EXTRA_CHAPTER, bm.chapter - 1)
                    }
                    startActivity(intent)
                },
                onLongClick = { bm ->
                    AlertDialog.Builder(ctx)
                        .setTitle("북마크 삭제")
                        .setMessage("${bm.bookNameKo} ${bm.chapter}:${bm.verse} 를 삭제하시겠습니까?")
                        .setPositiveButton("삭제") { _, _ ->
                            BookmarkStore.remove(ctx, bm.key)
                            refreshList()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            )
            adapter.submit(bookmarks)
            binding.bookmarkList.layoutManager = LinearLayoutManager(ctx)
            binding.bookmarkList.adapter = adapter
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
