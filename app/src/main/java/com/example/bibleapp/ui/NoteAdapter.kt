package com.example.bibleapp.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bibleapp.R
import com.example.bibleapp.data.NoteStore
import com.example.bibleapp.data.VerseNote
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteAdapter(
    private val onClick: (VerseNote) -> Unit
) : RecyclerView.Adapter<NoteAdapter.VH>() {

    private val items = mutableListOf<VerseNote>()
    var multiSelect = false
        set(v) { field = v; if (!v) selected.clear(); notifyDataSetChanged() }
    val selected = mutableSetOf<String>()
    var onSelectionChange: ((Int) -> Unit)? = null

    private val dtFmt = SimpleDateFormat("yy.MM.dd HH:mm", Locale.KOREA)

    fun submit(list: List<VerseNote>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun getAll(): List<VerseNote> = items.toList()

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = items[pos]
        val bytes = NoteStore.totalBytes(n)
        val sizeStr = when {
            bytes > 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
            bytes > 1024      -> "${bytes / 1024}KB"
            else              -> "${bytes}B"
        }

        h.ref.text     = n.reference
        h.preview.text = if (n.text.isNotBlank()) n.text.take(100) else "(사진만)"
        h.size.text    = sizeStr
        h.date.text    = dtFmt.format(Date(n.updatedAt))

        // 사진 썸네일
        h.photosRow.removeAllViews()
        val validPhotos = n.photoPaths.filter { File(it).exists() }
        if (validPhotos.isNotEmpty()) {
            h.photosRow.visibility = View.VISIBLE
            val dp   = h.itemView.resources.displayMetrics.density
            val size = (44 * dp).toInt()
            validPhotos.take(4).forEach { path ->
                val iv = ImageView(h.itemView.context).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size)
                        .apply { marginEnd = (4 * dp).toInt() }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try { setImageBitmap(BitmapFactory.decodeFile(path)) } catch (_: Exception) {}
                }
                h.photosRow.addView(iv)
            }
        } else {
            h.photosRow.visibility = View.GONE
        }

        // 멀티선택
        h.checkbox.visibility = if (multiSelect) View.VISIBLE else View.GONE
        h.checkbox.setOnCheckedChangeListener(null)   // 재활용 방지
        h.checkbox.isChecked = selected.contains(n.key)

        fun toggleSelect() {
            if (selected.contains(n.key)) selected.remove(n.key)
            else selected.add(n.key)
            h.checkbox.isChecked = selected.contains(n.key)
            onSelectionChange?.invoke(selected.size)
        }

        // 체크박스 직접 탭
        h.checkbox.setOnClickListener { toggleSelect() }

        // 행 전체 탭
        h.itemView.setOnClickListener {
            if (multiSelect) toggleSelect() else onClick(n)
        }
        h.itemView.setOnLongClickListener {
            if (!multiSelect) onClick(n)
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ref:       TextView     = v.findViewById(R.id.note_ref)
        val preview:   TextView     = v.findViewById(R.id.note_preview)
        val size:      TextView     = v.findViewById(R.id.note_size)
        val date:      TextView     = v.findViewById(R.id.note_date)
        val photosRow: LinearLayout = v.findViewById(R.id.note_photos_row)
        val checkbox:  CheckBox     = v.findViewById(R.id.note_check)
    }
}
