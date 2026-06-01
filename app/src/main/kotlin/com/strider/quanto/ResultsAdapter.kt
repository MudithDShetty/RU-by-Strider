package com.strider.quanto

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.strider.quanto.databinding.ItemResultBinding

class ResultsAdapter(
    private val onItemClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, ResultsAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(val binding: ItemResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result  = getItem(position)
        val file    = result.file
        val context = holder.itemView.context

        with(holder.binding) {
            tvFileName.text = file.name
            tvFilePath.text = file.metadata.typeLabel + " · " +
                    (file.path.substringBeforeLast("/").substringAfterLast("/"))
            tvFileSize.text = file.displaySize + " · " + file.metadata.ageBucket
            tvScore.text    = "${result.scorePercent}%"
            tvExtension.text = file.extension.uppercase().take(4)

            // Extension badge color
            val badgeColor = extColor(file.extension)
            (tvExtension.background as? GradientDrawable)?.setColor(badgeColor)

            // Top result gets highlighted card
            root.setBackgroundResource(
                if (position == 0) R.drawable.bg_file_card_top else R.drawable.bg_file_card
            )

            root.setOnClickListener { onItemClick(result) }
        }
    }

    private fun extColor(ext: String): Int = when (ext.lowercase()) {
        "pdf"                        -> Color.parseColor("#DC3545")
        "doc", "docx"                -> Color.parseColor("#2B579A")
        "xls", "xlsx", "csv"         -> Color.parseColor("#217346")
        "ppt", "pptx"                -> Color.parseColor("#D24726")
        "jpg", "jpeg", "png",
        "heic", "webp", "gif"        -> Color.parseColor("#0D9E76")
        "mp3", "aac", "flac",
        "wav", "m4a"                 -> Color.parseColor("#7C3AED")
        "mp4", "mkv", "avi", "mov"   -> Color.parseColor("#E67E22")
        "py", "js", "ts", "kt",
        "java", "cpp", "c", "h"      -> Color.parseColor("#1A1530")
        "zip", "rar", "7z",
        "tar", "gz", "apk"           -> Color.parseColor("#888888")
        else                         -> Color.parseColor("#C41E3A")
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(a: SearchResult, b: SearchResult) =
                a.file.path == b.file.path
            override fun areContentsTheSame(a: SearchResult, b: SearchResult) =
                a == b
        }
    }
}