package com.example.bibleapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.data.Bookmark
import com.example.bibleapp.databinding.ItemBookmarkBinding

class BookmarkAdapter(
    private val onClick: (Bookmark) -> Unit,
    private val onLongClick: (Bookmark) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    private val items = ArrayList<Bookmark>()

    fun submit(list: List<Bookmark>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemBookmarkBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(bm: Bookmark) {
            b.refText.text = bm.reference
            b.verseText.text = bm.text
            b.root.setOnClickListener { onClick(bm) }
            b.root.setOnLongClickListener { onLongClick(bm); true }
        }
    }
}
