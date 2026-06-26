package com.strider.quanto

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.strider.quanto.databinding.ItemResultBinding
import java.io.File

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
            FilePreviewLoader.applyThumbClip(binding.flThumb)
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
        val extUpper = file.extension.uppercase().take(4).ifBlank { "FILE" }

        with(holder.binding) {
            tvFileName.text = highlightName(file.name, query, ctx)

            val source = RuUi.sourceLabelFromPath(file.path)
            val date = RuUi.formatResultDate(file.lastModified)
            tvFileSub.text = buildString {
                append(source)
                append(" · ")
                append(extUpper)
                append(" · ")
                append(RuUi.formatDisplaySize(file.sizeBytes))
                if (date.isNotBlank()) {
                    append(" · ")
                    append(date)
                }
            }

            holder.bindSourceDot(RuUi.sourceDotColor(ctx, source))
            bindThumb(this, extUpper, file)

            root.setBackgroundResource(
                when {
                    shareMode && file.path == selectedPath -> R.drawable.bg_file_card_selected
                    else -> R.drawable.bg_result_row_top
                }
            )
            root.setOnClickListener { onItemClick(result) }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        with(holder.binding) {
            FilePreviewLoader.clearPreview(ivPreview, tvExtensionFallback)
        }
    }

    private fun bindThumb(binding: ItemResultBinding, extUpper: String, file: IndexedFile) {
        val isImage = extUpper in IMAGE_EXTS
        binding.flThumb.setBackgroundResource(
            when {
                isImage -> R.drawable.bg_result_thumb_image
                else -> R.drawable.bg_result_thumb_pdf
            }
        )

        FilePreviewLoader.clearPreview(binding.ivPreview, binding.tvExtensionFallback)

        if (FilePreviewLoader.supportsPreview(extUpper)) {
            FilePreviewLoader.loadPreview(
                imageView = binding.ivPreview,
                fallbackView = binding.tvExtensionFallback,
                file = File(file.path),
                extUpper = extUpper,
                lastModified = file.lastModified,
            )
        } else {
            showCenteredFallback(binding, extUpper, isImage)
        }
    }

    private fun showCenteredFallback(binding: ItemResultBinding, extUpper: String, isImage: Boolean) {
        binding.ivPreview.setImageDrawable(null)
        binding.tvExtensionFallback.apply {
            text = extUpper
            textSize = 10f
            setBackgroundResource(0)
            setPadding(0, 0, 0, 0)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        isImage -> R.color.text_secondary
                        extUpper in OFFICE_EXTS -> R.color.ru_amber
                        else -> R.color.ru_crimson
                    }
                )
            )
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER
                setMargins(0, 0, 0, 0)
            }
            visibility = View.VISIBLE
        }
    }

    private fun highlightName(name: String, rawQuery: String, ctx: android.content.Context): CharSequence {
        if (rawQuery.isBlank()) return name
        val tokens = rawQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
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

    companion object {
        private val IMAGE_EXTS = setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "HEIC")
        private val OFFICE_EXTS = setOf("DOC", "DOCX", "PPT", "PPTX")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(a: SearchResult, b: SearchResult) =
                a.file.path == b.file.path
            override fun areContentsTheSame(a: SearchResult, b: SearchResult) =
                a == b
        }
    }
}
