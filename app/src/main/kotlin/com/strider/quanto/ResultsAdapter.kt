package com.strider.quanto

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.strider.quanto.databinding.ItemResultBinding

class ResultsAdapter(
    private val onItemClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, ResultsAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var shareMode = false
    private var selectedPath: String? = null
    private var query: String = ""

    init {
        setHasStableIds(true)
    }

    fun setQuery(raw: String) {
        query = raw.trim()
    }

    fun shareModeEnabled(): Boolean = shareMode

    fun setShareMode(enabled: Boolean) {
        if (shareMode == enabled) return
        shareMode = enabled
        if (!enabled) selectedPath = null
        notifyDataSetChanged()
    }

    fun setShareSelection(path: String?) {
        if (selectedPath == path) return
        val oldPath = selectedPath
        selectedPath = path
        val list = currentList
        if (oldPath != null) {
            val oldIdx = list.indexOfFirst { it.file.path == oldPath }
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
        }
        if (path != null) {
            val newIdx = list.indexOfFirst { it.file.path == path }
            if (newIdx >= 0) notifyItemChanged(newIdx)
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).file.path.hashCode().toLong()

    inner class ViewHolder(val binding: ItemResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val sourceDotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }

        init {
            binding.vSourceDot.background = sourceDotDrawable
        }

        fun bindSourceDot(color: Int) {
            sourceDotDrawable.setColor(color)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = getItem(position)
        val file = result.file
        val ctx = holder.itemView.context

        with(holder.binding) {
            tvFileName.text = highlightName(file.name, query, ctx)
            tvExtension.text = file.extension.uppercase().take(4)

            val folder = file.path.substringBeforeLast("/", "")
                .substringAfterLast("/", "")
                .ifBlank { "Files" }
            tvFileSub.text = "${folder} · ${file.metadata.typeLabel} · ${file.displaySize} · ${file.metadata.ageBucket}"

            val dotColor = categoryColor(file.categories.firstOrNull())
            holder.bindSourceDot(dotColor)

            root.setBackgroundResource(
                when {
                    shareMode && file.path == selectedPath -> R.drawable.bg_file_card_selected
                    !shareMode && position == 0 -> R.drawable.bg_result_row_top
                    else -> R.drawable.bg_result_row
                }
            )
            root.setOnClickListener { onItemClick(result) }
        }
    }

    private fun highlightName(name: String, rawQuery: String, ctx: android.content.Context): CharSequence {
        if (rawQuery.isBlank()) return name
        val tokens = rawQuery.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return name
        val spannable = SpannableString(name)
        val crimson = ContextCompat.getColor(ctx, R.color.ru_crimson)
        tokens.forEach { token ->
            var start = name.indexOf(token, ignoreCase = true)
            while (start >= 0) {
                val end = start + token.length
                spannable.setSpan(ForegroundColorSpan(crimson), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start = name.indexOf(token, start + 1, ignoreCase = true)
            }
        }
        return spannable
    }

    private fun categoryColor(category: Category?): Int = CATEGORY_COLORS[category ?: Category.GENERAL]
        ?: CATEGORY_COLORS.getValue(Category.GENERAL)

    companion object {
        private val CATEGORY_COLORS = mapOf(
            Category.IDENTITY to Color.parseColor("#C41E3A"),
            Category.WORK to Color.parseColor("#3B65DC"),
            Category.EDUCATION to Color.parseColor("#8B6200"),
            Category.PERSONAL to Color.parseColor("#0D9E76"),
            Category.MEDIA to Color.parseColor("#7C3AED"),
            Category.GENERAL to Color.parseColor("#888888"),
        )

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(a: SearchResult, b: SearchResult) =
                a.file.path == b.file.path
            override fun areContentsTheSame(a: SearchResult, b: SearchResult) =
                a == b
        }
    }
}
