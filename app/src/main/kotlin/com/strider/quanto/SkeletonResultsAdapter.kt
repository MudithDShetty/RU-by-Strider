package com.strider.quanto

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.strider.quanto.databinding.ItemResultSkeletonBinding

class SkeletonResultsAdapter : RecyclerView.Adapter<SkeletonResultsAdapter.Holder>() {

    private val count = 6

    inner class Holder(val binding: ItemResultSkeletonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemResultSkeletonBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    override fun getItemCount(): Int = count
}
