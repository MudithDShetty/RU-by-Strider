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

    private var shareMode = false
    private var selectedPath: String? = null

    fun setShareMode(enabled: Boolean) {
        if (shareMode == enabled) return
        shareMode = enabled
        if (!enabled) selectedPath = null
        notifyDataSetChanged()
    }

    fun setShareSelection(path: String?) {
        if (selectedPath == path) return
        selectedPath = path
        notifyDataSetChanged()
    }

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

            tvScore.text = if (HYBRID_DEV_MODE) {
                val branch = when {
                    result.denseRank >= 0 && result.bm25Rank >= 0 -> "HYBRID"
                    result.bm25Rank >= 0 -> "LEXICAL"
                    result.denseRank >= 0 -> "GRANITE"
                    else -> "?"
                }
                val catTag = if (result.categoryMatched) " ✓cat" else ""
                val rankTag = buildString {
                    if (result.denseRank >= 0) append(" d#${result.denseRank + 1}")
                    if (result.bm25Rank >= 0) append(" l#${result.bm25Rank + 1}")
                }
                "${result.scorePercent}% [$branch$rankTag]$catTag"
            } else {
                "${result.scorePercent}%"
            }

            tvExtension.text = file.extension.uppercase().take(4)

            val badgeColor = extColor(file.extension)
            (tvExtension.background as? GradientDrawable)?.setColor(badgeColor)

            root.setBackgroundResource(
                when {
                    shareMode && file.path == selectedPath -> R.drawable.bg_file_card_selected
                    !shareMode && position == 0 -> R.drawable.bg_file_card_top
                    else -> R.drawable.bg_file_card
                }
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
        /** Debug builds only — fusion badges and golden eval logging. */
        val HYBRID_DEV_MODE: Boolean = BuildConfig.DEBUG

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(a: SearchResult, b: SearchResult) =
                a.file.path == b.file.path
            override fun areContentsTheSame(a: SearchResult, b: SearchResult) =
                a == b
        }
    }
}
