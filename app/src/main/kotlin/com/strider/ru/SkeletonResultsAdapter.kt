package com.strider.ru

import android.animation.ValueAnimator
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.strider.ru.databinding.ItemResultSkeletonBinding

class SkeletonResultsAdapter : RecyclerView.Adapter<SkeletonResultsAdapter.Holder>() {

    private val count = 6
    private var shimmerAnimator: ValueAnimator? = null
    private var attachedRecyclerView: RecyclerView? = null

    inner class Holder(val binding: ItemResultSkeletonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemResultSkeletonBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    override fun getItemCount(): Int = count

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        startShimmerIfNeeded(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        stopShimmer()
        attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun startShimmerIfNeeded(recyclerView: RecyclerView) {
        val scale = Settings.Global.getFloat(
            recyclerView.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        if (scale <= 0f) {
            recyclerView.alpha = 1f
            return
        }
        stopShimmer()
        shimmerAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                attachedRecyclerView?.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        attachedRecyclerView?.alpha = 1f
    }
}
