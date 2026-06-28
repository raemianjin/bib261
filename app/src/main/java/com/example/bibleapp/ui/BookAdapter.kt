package com.example.bibleapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.data.Book
import com.example.bibleapp.databinding.ItemBookBinding
import com.example.bibleapp.databinding.ItemSectionHeaderBinding

/**
 * 구약/신약 섹션 헤더와 책 아이템을 함께 보여주는 어댑터.
 */
class BookAdapter(
    private val onClick: (Book) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val book: Book) : Row()
    }

    private val rows = ArrayList<Row>()

    fun submit(books: List<Book>) {
        rows.clear()
        val old = books.filter { it.isOldTestament }
        val new = books.filter { !it.isOldTestament }
        if (old.isNotEmpty()) {
            rows.add(Row.Header("구약 OLD TESTAMENT"))
            old.forEach { rows.add(Row.Item(it)) }
        }
        if (new.isNotEmpty()) {
            rows.add(Row.Header("신약 NEW TESTAMENT"))
            new.forEach { rows.add(Row.Item(it)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemVH(ItemBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.title)
            is Row.Item -> (holder as ItemVH).bind(row.book)
        }
    }

    inner class HeaderVH(private val b: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(title: String) { b.sectionTitle.text = title }
    }

    inner class ItemVH(private val b: ItemBookBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(book: Book) {
            b.bookIndex.text = book.index.toString()
            b.bookNameKo.text = book.nameKo
            b.bookNameEn.text = "${book.nameEn} · ${book.chapterCount}장"
            b.root.setOnClickListener { onClick(book) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
