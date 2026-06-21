package com.strider.quanto

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.strider.quanto.databinding.FragmentHomeBinding

/** Home results chrome: idle hero vs results panel below search. UI-only — search pipeline unchanged. */
class HomeResultsUi(
    private val binding: FragmentHomeBinding,
    private val skeletonAdapter: SkeletonResultsAdapter,
    private val onNavVisibility: (hide: Boolean, animated: Boolean) -> Unit,
    private val uiAnimDuration: (Long) -> Long = { ms -> ms },
    private val onResultsLayoutUpdated: () -> Unit = {},
    private val onClearSearch: () -> Unit = {},
) {
    private var resultsMode = false
    private var searching = false
    private var skeletonShown = false
    private var showSkeletonRunnable: Runnable? = null
    private var sheetExpandedTop = 0
    private var sheetCollapsedTop = 0
    private var sheetDragStartY = 0f
    private var sheetDragStartTop = 0
    private var sheetSnapAnimator: ValueAnimator? = null
    private var lastImeVisible: Boolean? = null
    private var lastRootHeight = 0
    private val relayoutRunnable = Runnable { relayoutResultsPanel() }

    fun setup() {
        binding.resultsSheet.visibility = View.GONE
        binding.btnClearSearch.setOnClickListener { onClearSearch() }
        setupSheetDrag()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            scheduleRelayoutFromInsets(insets)
            insets
        }
    }

    fun destroy() {
        cancelPendingSkeleton()
        sheetSnapAnimator?.cancel()
        sheetSnapAnimator = null
        binding.rvResults.animate().cancel()
        binding.llHeroBranding.animate().cancel()
        binding.llResultsTop.animate().cancel()
        binding.root.removeCallbacks(relayoutRunnable)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
    }

    fun focusSearchField() {
        binding.etSearch.apply {
            isEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            val end = text?.length ?: 0
            setSelection(end)
        }
        val imm = binding.root.context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    fun onSearchStarted() {
        searching = true
        skeletonShown = false
        cancelPendingSkeleton()
        if (!resultsMode) enterResultsMode(animated = true)
        ensureFullHeightSheet()
        binding.tvResultsEmpty.visibility = View.GONE
        showResultsPanel(expanded = true)
        onNavVisibility(true, true)
        val delay = animDuration(SKELETON_DELAY_MS)
        if (delay > 0L) {
            showSkeletonRunnable = Runnable {
                if (!searching) return@Runnable
                skeletonShown = true
                binding.rvResults.scrollToPosition(0)
                binding.rvResults.adapter = skeletonAdapter
                binding.tvResultsCount.text = binding.root.context.getString(R.string.results_searching)
            }
            binding.root.postDelayed(showSkeletonRunnable!!, delay)
        } else {
            skeletonShown = true
            binding.rvResults.scrollToPosition(0)
            binding.rvResults.adapter = skeletonAdapter
            binding.tvResultsCount.text = binding.root.context.getString(R.string.results_searching)
        }
    }

    fun onSearchRefining() {
        searching = true
        binding.rvResults.animate().cancel()
        binding.rvResults.animate().alpha(REFINE_DIM_ALPHA).setDuration(animDuration(120L)).start()
    }

    fun onSearchFinished(
        results: List<SearchResult>,
        query: String,
        resultsAdapter: ResultsAdapter,
        isRefine: Boolean = false,
        onListApplied: (() -> Unit)? = null,
    ) {
        searching = false
        cancelPendingSkeleton()
        binding.rvResults.animate().cancel()
        binding.rvResults.animate().alpha(1f).setDuration(animDuration(150L)).start()
        resultsAdapter.setQuery(query)
        if (skeletonShown || !isRefine) {
            binding.rvResults.adapter = resultsAdapter
        }
        if (!resultsMode) enterResultsMode(animated = true)
        if (results.isEmpty()) {
            binding.tvResultsEmpty.text =
                binding.root.context.getString(R.string.results_empty, query)
            binding.tvResultsEmpty.visibility = View.VISIBLE
            binding.tvResultsCount.text =
                binding.root.context.getString(R.string.results_empty, query)
            resultsAdapter.submitList(emptyList()) {
                binding.rvResults.adapter = resultsAdapter
                if (!isRefine) {
                    binding.rvResults.scrollToPosition(0)
                    showResultsPanel(expanded = true)
                }
                onListApplied?.invoke()
            }
        } else {
            binding.tvResultsEmpty.visibility = View.GONE
            binding.tvResultsCount.text =
                binding.root.context.resources.getQuantityString(
                    R.plurals.results_files_found, results.size, results.size
                )
            resultsAdapter.submitList(results) {
                binding.rvResults.adapter = resultsAdapter
                if (!isRefine) {
                    binding.rvResults.scrollToPosition(0)
                    showResultsPanel(expanded = true)
                }
                onListApplied?.invoke()
            }
        }
        skeletonShown = false
        if (!isRefine) onNavVisibility(true, true)
    }

    fun closeResults(animated: Boolean) {
        if (!resultsMode && !searching) return
        searching = false
        cancelPendingSkeleton()
        sheetSnapAnimator?.cancel()
        binding.rvResults.animate().cancel()
        binding.rvResults.alpha = 1f
        resultsMode = false
        lastImeVisible = null
        lastRootHeight = 0
        val duration = animDuration(280L)
        binding.resultsSheet.visibility = View.GONE

        binding.llHeroBranding.animate().cancel()
        binding.llResultsTop.animate().cancel()

        moveSearchBarToIdle()
        binding.svHome.visibility = View.VISIBLE
        ensureFullHeightSheet()
        restoreSpacers()
        binding.tvResultsEmpty.visibility = View.GONE
        binding.llSharePicker.visibility = View.GONE
        binding.btnClearSearch.visibility = View.GONE
        binding.llSearchHelper.visibility = View.VISIBLE
        onNavVisibility(false, animated)

        binding.root.post {
            binding.root.post { onResultsLayoutUpdated() }
        }

        if (animated && duration > 0L) {
            binding.llHeroBranding.apply {
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(duration).start()
            }
            binding.llResultsTop.animate().alpha(0f).setDuration(duration).withEndAction {
                binding.llResultsTop.visibility = View.GONE
            }.start()
        } else {
            binding.llHeroBranding.visibility = View.VISIBLE
            binding.llHeroBranding.alpha = 1f
            binding.llResultsTop.visibility = View.GONE
            binding.llResultsTop.alpha = 0f
        }
    }

    fun isResultsMode(): Boolean = resultsMode || searching

    fun isPanelVisible(): Boolean = binding.resultsSheet.visibility == View.VISIBLE

    fun relayoutResultsPanel() {
        if (!isPanelVisible()) return
        binding.llResultsTop.post {
            if (!isPanelVisible()) return@post
            computeSheetAnchors()
            val lp = binding.resultsSheet.layoutParams as CoordinatorLayout.LayoutParams
            val mid = (sheetExpandedTop + sheetCollapsedTop) / 2
            val expanded = lp.topMargin <= mid
            applySheetTop(if (expanded) sheetExpandedTop else sheetCollapsedTop)
            ensureFullHeightSheet()
        }
    }

    private fun scheduleRelayoutFromInsets(insets: WindowInsetsCompat) {
        if (binding.resultsSheet.visibility != View.VISIBLE) return
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val rootH = binding.root.height
        if (imeVisible == lastImeVisible && rootH == lastRootHeight) return
        lastImeVisible = imeVisible
        lastRootHeight = rootH
        binding.root.removeCallbacks(relayoutRunnable)
        binding.root.post(relayoutRunnable)
    }

    private fun enterResultsMode(animated: Boolean) {
        if (resultsMode) return
        resultsMode = true
        val duration = animDuration(280L)

        binding.llHeroBranding.animate().cancel()
        binding.llResultsTop.animate().cancel()
        binding.svHome.visibility = View.GONE
        binding.llResultsTop.visibility = View.VISIBLE
        binding.tvResultsWordmark.visibility = View.GONE
        binding.root.bringChildToFront(binding.llResultsTop)
        binding.root.bringChildToFront(binding.resultsSheet)
        moveSearchBarToResultsTop()

        if (animated && duration > 0L) {
            binding.llHeroBranding.animate()
                .alpha(0f)
                .setDuration(duration)
                .withEndAction { binding.llHeroBranding.visibility = View.GONE }
                .start()
            binding.llResultsTop.alpha = 0f
            binding.llResultsTop.animate().alpha(1f).setDuration(animDuration(320L)).start()
        } else {
            binding.llHeroBranding.visibility = View.GONE
            binding.llHeroBranding.alpha = 0f
            binding.llResultsTop.alpha = 1f
        }
        collapseSpacers()
        binding.llSearchHelper.visibility = View.GONE
        binding.btnClearSearch.visibility = View.VISIBLE
    }

    private fun showResultsPanel(expanded: Boolean) {
        binding.llResultsTop.post {
            if (binding.resultsSheet.visibility == View.GONE && !resultsMode && !searching) return@post
            computeSheetAnchors()
            applySheetTop(if (expanded) sheetExpandedTop else sheetCollapsedTop)
            binding.resultsSheet.visibility = View.VISIBLE
            ensureFullHeightSheet()
        }
    }

    private fun computeSheetAnchors() {
        val gap = binding.root.resources.getDimensionPixelSize(R.dimen.results_sheet_top_gap)
        sheetExpandedTop = binding.llResultsTop.bottom + gap
        val peek = binding.root.resources.getDimensionPixelSize(R.dimen.results_sheet_peek)
        sheetCollapsedTop = (binding.root.height - peek).coerceAtLeast(sheetExpandedTop)
    }

    private fun applySheetTop(top: Int) {
        if (sheetCollapsedTop < sheetExpandedTop) {
            computeSheetAnchors()
        }
        val clamped = top.coerceIn(sheetExpandedTop, sheetCollapsedTop)
        val lp = binding.resultsSheet.layoutParams as CoordinatorLayout.LayoutParams
        val newHeight = (binding.root.height - clamped).coerceAtLeast(0)
        if (lp.topMargin == clamped && lp.height == newHeight) return
        lp.topMargin = clamped
        lp.height = newHeight
        lp.bottomMargin = 0
        binding.resultsSheet.layoutParams = lp
    }

    private fun setupSheetDrag() {
        binding.llSheetDragZone.setOnTouchListener { _, event ->
            if (!isPanelVisible()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sheetSnapAnimator?.cancel()
                    computeSheetAnchors()
                    sheetDragStartY = event.rawY
                    sheetDragStartTop =
                        (binding.resultsSheet.layoutParams as CoordinatorLayout.LayoutParams).topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - sheetDragStartY
                    applySheetTop(sheetDragStartTop + dy.toInt())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    snapSheet()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapSheet() {
        computeSheetAnchors()
        val lp = binding.resultsSheet.layoutParams as CoordinatorLayout.LayoutParams
        val mid = (sheetExpandedTop + sheetCollapsedTop) / 2
        val target = if (lp.topMargin < mid) sheetExpandedTop else sheetCollapsedTop
        if (lp.topMargin == target) return
        sheetSnapAnimator?.cancel()
        sheetSnapAnimator = ValueAnimator.ofInt(lp.topMargin, target).apply {
            duration = animDuration(180L)
            addUpdateListener { anim ->
                applySheetTop(anim.animatedValue as Int)
            }
            start()
        }
    }

    private fun ensureFullHeightSheet() {
        val rvLp = binding.rvResults.layoutParams as LinearLayout.LayoutParams
        rvLp.height = 0
        rvLp.weight = 1f
        binding.rvResults.layoutParams = rvLp
    }

    private fun moveSearchBarToResultsTop() {
        if (binding.llSearchBar.parent === binding.flResultsSearchHost) {
            binding.btnSearch.visibility = View.GONE
            return
        }
        (binding.llSearchBar.parent as? ViewGroup)?.removeView(binding.llSearchBar)
        val h = binding.root.resources.getDimensionPixelSize(R.dimen.home_results_search_height)
        binding.flResultsSearchHost.addView(
            binding.llSearchBar,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        )
        binding.llSearchBar.layoutParams.height = h
        val action = binding.root.resources.getDimensionPixelSize(R.dimen.home_mic_compact_size)
        binding.flSearchAction.layoutParams.width = action
        binding.flSearchAction.layoutParams.height = action
        binding.btnSearch.visibility = View.GONE
        binding.flMicWrap.visibility = View.VISIBLE
        binding.etSearch.textSize = 16f
    }

    private fun moveSearchBarToIdle() {
        if (binding.llSearchBar.parent === binding.flIdleSearchHost) {
            return
        }
        (binding.llSearchBar.parent as? ViewGroup)?.removeView(binding.llSearchBar)
        val h = binding.root.resources.getDimensionPixelSize(R.dimen.home_search_height)
        binding.flIdleSearchHost.addView(
            binding.llSearchBar,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        )
        binding.llSearchBar.layoutParams.height = h
        val action = binding.root.resources.getDimensionPixelSize(R.dimen.home_mic_size)
        binding.flSearchAction.layoutParams.width = action
        binding.flSearchAction.layoutParams.height = action
        binding.etSearch.textSize = 17f
    }

    private fun cancelPendingSkeleton() {
        showSkeletonRunnable?.let { binding.root.removeCallbacks(it) }
        showSkeletonRunnable = null
    }

    private fun collapseSpacers() = setSpacerWeights(0f, 0f)

    private fun restoreSpacers() = setSpacerWeights(1f, 1f)

    private fun setSpacerWeights(top: Float, bottom: Float) {
        val topLp = binding.topSpacer.layoutParams as LinearLayout.LayoutParams
        val bottomLp = binding.bottomSpacer.layoutParams as LinearLayout.LayoutParams
        topLp.weight = top
        bottomLp.weight = bottom
        binding.topSpacer.layoutParams = topLp
        binding.bottomSpacer.layoutParams = bottomLp
    }

    private fun animDuration(ms: Long): Long = uiAnimDuration(ms)

    companion object {
        private const val SKELETON_DELAY_MS = 280L
        private const val REFINE_DIM_ALPHA = 0.55f
    }
}
