package com.aali.ebookreader

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BookAdapter(
    private val onClick: (BookItem) -> Unit,
    private val onLongClick: (BookItem) -> Unit
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    private var items: List<BookItem> = emptyList()

    fun submit(list: List<BookItem>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.imgCover)
        val title: TextView = v.findViewById(R.id.txtTitle)
        val meta: TextView = v.findViewById(R.id.txtMeta)
        val badge: TextView = v.findViewById(R.id.txtBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.badge.text = item.format
        val sizeMb = item.file.length() / 1024.0 / 1024.0
        holder.meta.text = "%.1f MB".format(sizeMb)
        if (item.cover != null && item.cover.exists()) {
            val bmp = BitmapFactory.decodeFile(item.cover.absolutePath)
            if (bmp != null) {
                holder.cover.setImageBitmap(bmp)
                holder.cover.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                holder.cover.setImageResource(R.drawable.ic_book)
                holder.cover.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        } else {
            holder.cover.setImageResource(R.drawable.ic_book)
            holder.cover.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
