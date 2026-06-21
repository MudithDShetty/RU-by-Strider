package com.strider.quanto.eval

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.strider.quanto.databinding.ItemAnalyticsEventBinding

class AnalyticsEventAdapter :
    ListAdapter<EvalLiveFeed.LiveEvent, AnalyticsEventAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(val binding: ItemAnalyticsEventBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAnalyticsEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        with(holder.binding) {
            tvEventBadge.text = event.category.uppercase()
            tvEventTime.text = event.ts.takeLast(12).removePrefix("T").removeSuffix("Z")
            tvEventSummary.text = event.summary
            tvEventDetail.text = event.detail
            tvEventDetail.visibility = if (event.detail.isBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
            val badgeColor = when (event.category) {
                "search" -> Color.parseColor("#E8F5E9")
                "index" -> Color.parseColor("#E3F2FD")
                "init" -> Color.parseColor("#FFF3E0")
                else -> Color.parseColor("#F5F5F5")
            }
            tvEventBadge.setBackgroundColor(badgeColor)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EvalLiveFeed.LiveEvent>() {
            override fun areItemsTheSame(a: EvalLiveFeed.LiveEvent, b: EvalLiveFeed.LiveEvent) =
                a.id == b.id
            override fun areContentsTheSame(a: EvalLiveFeed.LiveEvent, b: EvalLiveFeed.LiveEvent) =
                a == b
        }
    }
}
