package com.strider.quanto

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.strider.quanto.databinding.ActivityMainBinding
import com.strider.quanto.databinding.FragmentAnalyticsBinding
import com.strider.quanto.databinding.FragmentHomeBinding
import com.strider.quanto.databinding.FragmentIndexBinding
import com.strider.quanto.databinding.FragmentSettingsBinding
import com.strider.quanto.databinding.ItemBucketChipBinding
import com.strider.quanto.eval.AnalyticsEventAdapter
import com.strider.quanto.eval.EvalLiveFeed
import com.strider.quanto.eval.EvalLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.File

enum class Screen { HOME, INDEX, ANALYTICS, SETTINGS }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: StriderApp
    private lateinit var engine: EmbeddingEngine
    private lateinit var indexer: FileIndexer
    private lateinit var db: DatabaseHelper
    private lateinit var resultsAdapter: ResultsAdapter

    private var isEngineReady = false
    private var isIndexReady = false
    private var isIndexing = false
    private var currentScreen = Screen.HOME

    // View bindings for each screen (inflated once, swapped in/out)
    private lateinit var homeBinding: FragmentHomeBinding
    private lateinit var indexBinding: FragmentIndexBinding
    private lateinit var analyticsBinding: FragmentAnalyticsBinding
    private lateinit var settingsBinding: FragmentSettingsBinding
    private lateinit var analyticsAdapter: AnalyticsEventAdapter
    private var analyticsFeedListener: ((EvalLiveFeed.DashboardState) -> Unit)? = null

    private val taglineHandler = Handler(Looper.getMainLooper())
    private var taglineToggle = false
    private var taglineCycleActive = false
    private var taglineCycleRunnable: Runnable? = null
    private var eqAnimators: List<ObjectAnimator>? = null
    private var micBreatheAnimator: ObjectAnimator? = null
    private var micRingAnimators: List<ObjectAnimator>? = null
    private var hintDotAnimator: ObjectAnimator? = null
    private lateinit var skeletonAdapter: SkeletonResultsAdapter
    private lateinit var homeResultsUi: HomeResultsUi

    private lateinit var prefs: SharedPreferences
    private lateinit var voiceSearchManager: VoiceSearchManager
    private var statusBeforeListening: String? = null
    private var voiceCancelRequested = false
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var searchGeneration = 0
    private var lastTargetReport: TargetFileReport? = null
    private var lastSearchResults: List<SearchResult> = emptyList()

    private var pendingShareResults: List<SearchResult>? = null
    private var pendingShareIndex = 0
    private var pendingShareTarget: String? = null
    private var onboardingIsFirstRun = false
    private var navHiddenForResults = false
    /** User explicitly left the search field (opened a file, etc.). Layout blur must not clear this. */
    private var userDismissedSearchFocus = false
    private val searchFocusHandler = Handler(Looper.getMainLooper())
    private var dialSweepDegrees = 0f
    private val dialAnimHandler = Handler(Looper.getMainLooper())
    private val toastHandler = Handler(Looper.getMainLooper())
    private var toastHideRunnable: Runnable? = null
    private val maxFilesOptions = intArrayOf(1_000, 2_000, 5_000, 10_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as StriderApp

        PDFBoxResourceLoader.init(applicationContext)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        voiceSearchManager = VoiceSearchManager(this, voiceSearchCallbacks)

        inflateScreens()
        applyShellWordmarks()
        setupNavBar()
        val restored = restoreLastScreen()
        showScreen(restored)
        setupHomeScreen()
        setupIndexScreen()
        setupAnalyticsScreen()
        setupSettingsScreen()
        setupOnboardingOverlay()
        attachToApp()
        observeIndexingWork()
        syncHomeDecorAnimations()

        if (!UserProfile.isNameSet(this)) {
            showOnboardingOverlay(isFirstRun = true)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                if (homeBinding.etSearch.hasFocus()) {
                    imm.hideSoftInputFromWindow(homeBinding.etSearch.windowToken, 0)
                    homeBinding.etSearch.clearFocus()
                }
            } else if (binding.onboardingOverlay.visibility == View.VISIBLE) {
                hideOnboardingOverlay()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Screen inflation
    // ─────────────────────────────────────────────

    private fun inflateScreens() {
        homeBinding     = FragmentHomeBinding.inflate(layoutInflater)
        indexBinding    = FragmentIndexBinding.inflate(layoutInflater)
        analyticsBinding = FragmentAnalyticsBinding.inflate(layoutInflater)
        settingsBinding = FragmentSettingsBinding.inflate(layoutInflater)
    }

    private fun applyShellWordmarks() {
        RuUi.applyWordmark(homeBinding.tvHomeWordmark)
        RuUi.applyWordmark(homeBinding.tvResultsWordmark, R.dimen.results_wordmark_size)
        RuUi.applyWordmark(indexBinding.tvIndexWordmark)
        RuUi.applyWordmark(binding.tvOnboardingWordmark, R.dimen.onboarding_wordmark_size)
        homeBinding.tvTaglineHi.alpha = 1f
        homeBinding.tvTaglineEn.alpha = 0f
        homeBinding.tvSearchHintIdle.alpha = 1f
        homeBinding.tvSearchHintLive.alpha = 0f
    }

    private fun restoreLastScreen(): Screen {
        if (!UserProfile.isNameSet(this)) return Screen.HOME
        val name = prefs.getString(PREF_LAST_TAB, Screen.HOME.name) ?: Screen.HOME.name
        val screen = runCatching { Screen.valueOf(name) }.getOrDefault(Screen.HOME)
        if (screen == Screen.ANALYTICS && !EvalLogger.enabled) return Screen.HOME
        return screen
    }

    private fun persistLastScreen(screen: Screen) {
        prefs.edit().putString(PREF_LAST_TAB, screen.name).apply()
    }

    private fun showScreen(screen: Screen) {
        if (screen == Screen.ANALYTICS && !EvalLogger.enabled) {
            showScreen(Screen.HOME)
            return
        }
        detachAnalyticsListener()
        currentScreen = screen
        binding.flContent.removeAllViews()
        val view = when (screen) {
            Screen.HOME      -> homeBinding.root
            Screen.INDEX     -> indexBinding.root
            Screen.ANALYTICS -> {
                attachAnalyticsListener()
                analyticsBinding.root
            }
            Screen.SETTINGS  -> settingsBinding.root
        }
        binding.flContent.addView(view)
        updateNavBar(screen)
        persistLastScreen(screen)
        if (screen == Screen.HOME) syncNavForHomeResults()
        if (screen == Screen.SETTINGS) refreshSettingsSystemStatus()
        syncHomeDecorAnimations()
    }

    private fun syncNavForHomeResults() {
        if (::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()) {
            setNavHiddenForResults(hide = true, animated = false)
        } else {
            setNavHiddenForResults(hide = false, animated = false)
        }
    }

    // ─────────────────────────────────────────────
    // Nav bar
    // ─────────────────────────────────────────────

    private fun setupNavBar() {
        if (EvalLogger.enabled) {
            binding.navAnalytics.visibility = View.VISIBLE
            binding.navAnalytics.setOnClickListener { showScreen(Screen.ANALYTICS) }
        }
        binding.navHome.setOnClickListener     { showScreen(Screen.HOME) }
        binding.navIndex.setOnClickListener    { showScreen(Screen.INDEX) }
        binding.navSettings.setOnClickListener { showScreen(Screen.SETTINGS) }
    }

    private fun updateNavBar(screen: Screen) {
        val tabs = buildList {
            add(Triple(binding.navHomeIcon, binding.navHomeLabel, screen == Screen.HOME))
            add(Triple(binding.navIndexIcon, binding.navIndexLabel, screen == Screen.INDEX))
            if (EvalLogger.enabled) {
                add(Triple(binding.navAnalyticsIcon, binding.navAnalyticsLabel, screen == Screen.ANALYTICS))
            }
            add(Triple(binding.navSettingsIcon, binding.navSettingsLabel, screen == Screen.SETTINGS))
        }
        tabs.forEach { (icon, label, active) ->
            icon.isSelected = active
            label.isSelected = active
        }
    }

    private fun updateContentBottomInset(showNav: Boolean) {
        val bottom = if (showNav) {
            resources.getDimensionPixelSize(R.dimen.ru_nav_clearance)
        } else {
            0
        }
        binding.flContent.setPadding(0, 0, 0, bottom)
    }

    private fun setNavHiddenForResults(hide: Boolean, animated: Boolean) {
        updateContentBottomInset(showNav = currentScreen != Screen.HOME || !hide)
        if (currentScreen != Screen.HOME) {
            if (navHiddenForResults) {
                navHiddenForResults = false
                binding.llNavBar.visibility = View.VISIBLE
                binding.llNavBar.alpha = 1f
            }
            return
        }
        if (hide == navHiddenForResults) return
        navHiddenForResults = hide
        val duration = uiAnimDuration(300L)
        binding.llNavBar.animate().cancel()
        if (hide) {
            if (animated && duration > 0L) {
                binding.llNavBar.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .withEndAction { binding.llNavBar.visibility = View.GONE }
                    .start()
            } else {
                binding.llNavBar.visibility = View.GONE
                binding.llNavBar.alpha = 0f
            }
        } else {
            binding.llNavBar.visibility = View.VISIBLE
            if (animated && duration > 0L) {
                binding.llNavBar.alpha = 0f
                binding.llNavBar.animate().alpha(1f).setDuration(duration).start()
            } else {
                binding.llNavBar.alpha = 1f
            }
        }
    }

    // ─────────────────────────────────────────────
    // Home screen setup
    // ─────────────────────────────────────────────

    private fun setupHomeScreen() {
        skeletonAdapter = SkeletonResultsAdapter()
        homeResultsUi = HomeResultsUi(
            binding = homeBinding,
            skeletonAdapter = skeletonAdapter,
            onNavVisibility = { hide, animated -> setNavHiddenForResults(hide, animated) },
            uiAnimDuration = { ms -> uiAnimDuration(ms) },
            onResultsLayoutUpdated = {
                restoreSearchFocus()
                syncSearchActionButton()
            },
            onClearSearch = {
                homeBinding.etSearch.setText("")
                resetSearchUi(animated = true)
            },
        ).also { it.setup() }

        resultsAdapter = ResultsAdapter { result ->
            if (pendingShareResults != null) {
                val idx = pendingShareResults!!.indexOfFirst { it.file.path == result.file.path }
                if (idx >= 0) {
                    pendingShareIndex = idx
                    updateSharePickerUi()
                }
            } else {
                val rank = lastSearchResults.indexOfFirst { it.file.path == result.file.path } + 1
                if (EvalLogger.enabled && rank > 0) {
                    EvalLogger.logResultClick(
                        query = homeBinding.etSearch.text.toString().trim(),
                        clickedRank = rank,
                        clickedPath = result.file.path,
                        top1Path = lastSearchResults.firstOrNull()?.file?.path
                    )
                }
                openFile(result.file)
            }
        }
        homeBinding.rvResults.apply {
            adapter = resultsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = true
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
        }

        homeBinding.btnMic.isEnabled = false
        homeBinding.btnSearch.isEnabled = false

        homeBinding.etSearch.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submitSearchFromUser("ime_search")
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    userDismissedSearchFocus = false
                    val text = s?.toString()?.trim() ?: ""
                    syncSearchActionButton()
                    cancelSearchDebounce()
                    if (text.length < 2) {
                        if (::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()) {
                            searchJob?.cancel()
                            dismissSharePicker()
                            resultsAdapter.submitList(emptyList())
                            homeBinding.tvResultsEmpty.visibility = View.GONE
                            homeBinding.tvStatus.visibility = View.GONE
                            homeBinding.tvResultsCount.text = getString(R.string.results_keep_typing)
                            return
                        }
                        val wasResults = ::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()
                        resetSearchUi(animated = true)
                        if (!wasResults) restoreSearchFocus()
                        return
                    }
                    if (::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()) {
                        scheduleLiveSearch()
                    }
                }
            })
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    userDismissedSearchFocus = false
                    homeBinding.llSearchBar.setBackgroundResource(R.drawable.bg_search_bar_active)
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    homeBinding.llSearchBar.setBackgroundResource(R.drawable.bg_search_bar)
                }
                syncSearchActionButton()
            }
        }

        homeBinding.llSearchBar.setOnClickListener {
            focusSearchField()
        }

        homeBinding.btnMic.setOnClickListener {
            handleMicClick()
        }

        homeBinding.btnSearch.setOnClickListener {
            submitSearchFromUser("button_search")
        }

        homeBinding.btnShareNext.setOnClickListener { advanceShareSelection() }
        homeBinding.btnShareConfirm.setOnClickListener { shareCurrentSelection() }
        homeBinding.btnShareCancel.setOnClickListener { dismissSharePicker() }
        syncSearchActionButton()
    }

    /** Idle home: mic when empty; enter when user has typed. Results mode: mic only (live refine). */
    private fun syncSearchActionButton() {
        if (!::homeBinding.isInitialized) return
        val inResults = ::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()
        val listening = ::voiceSearchManager.isInitialized && voiceSearchManager.isListening
        val hasText = !homeBinding.etSearch.text.isNullOrEmpty()
        val showEnter = !inResults && !listening && hasText

        homeBinding.btnSearch.visibility = if (showEnter) View.VISIBLE else View.GONE
        homeBinding.flMicWrap.visibility = if (showEnter) View.GONE else View.VISIBLE

        if (showEnter) {
            stopMicIdleAnimation()
        } else if (!inResults && !listening) {
            syncHomeDecorAnimations()
        }
    }

    private fun focusSearchField() {
        userDismissedSearchFocus = false
        if (::homeResultsUi.isInitialized) {
            homeResultsUi.focusSearchField()
        } else {
            homeBinding.etSearch.requestFocus()
        }
    }

    /** Keep the keyboard up while the user is editing — layout must not kick them out. */
    private fun restoreSearchFocus() {
        if (userDismissedSearchFocus || currentScreen != Screen.HOME) return
        searchFocusHandler.post {
            searchFocusHandler.post {
                if (userDismissedSearchFocus || currentScreen != Screen.HOME) return@post
                val len = homeBinding.etSearch.text?.length ?: 0
                homeBinding.etSearch.requestFocus()
                homeBinding.etSearch.setSelection(len)
                homeBinding.llSearchBar.setBackgroundResource(R.drawable.bg_search_bar_active)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(homeBinding.etSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun startMicIdleAnimation() {
        if (animatorDurationScale() <= 0f) return
        if (micBreatheAnimator?.isRunning == true) return
        homeBinding.vMicBreathe.visibility = View.VISIBLE
        micBreatheAnimator?.cancel()
        micBreatheAnimator = ObjectAnimator.ofFloat(homeBinding.vMicBreathe, View.ALPHA, 0.32f, 0f).apply {
            duration = 3600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopMicIdleAnimation() {
        micBreatheAnimator?.cancel()
        micBreatheAnimator = null
        homeBinding.vMicBreathe.alpha = 0.32f
    }

    private fun uiAnimDuration(ms: Long): Long =
        if (animatorDurationScale() > 0f) ms else 0L

    private fun animatorDurationScale(): Float =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    // ─────────────────────────────────────────────
    // Index screen setup
    // ─────────────────────────────────────────────

    private fun setupIndexScreen() {
        indexBinding.btnIndex.setOnClickListener {
            if (!isEngineReady) { showToast(getString(R.string.status_loading)); return@setOnClickListener }
            if (isIndexing) return@setOnClickListener
            checkPermissionsAndIndex()
        }
        refreshIndexIdleState()
    }

    // ─────────────────────────────────────────────
    // Analytics dashboard (eval builds only)
    // ─────────────────────────────────────────────

    private fun setupAnalyticsScreen() {
        if (!EvalLogger.enabled) return
        analyticsAdapter = AnalyticsEventAdapter()
        analyticsBinding.rvAnalyticsEvents.apply {
            adapter = analyticsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
        }
    }

    private fun attachAnalyticsListener() {
        if (!EvalLogger.enabled) return
        EvalLiveFeed.loadHistoryIfNeeded(this)
        val listener: (EvalLiveFeed.DashboardState) -> Unit = listener@{ state ->
            if (currentScreen != Screen.ANALYTICS) return@listener
            renderAnalyticsDashboard(state)
        }
        analyticsFeedListener = listener
        EvalLiveFeed.addListener(listener)
    }

    private fun detachAnalyticsListener() {
        analyticsFeedListener?.let { EvalLiveFeed.removeListener(it) }
        analyticsFeedListener = null
    }

    private fun renderAnalyticsDashboard(state: EvalLiveFeed.DashboardState) {
        analyticsBinding.tvSystemStatus.text = buildSystemStatusText(state)
        analyticsBinding.tvLastSearch.text = state.lastSearchSummary
            ?: getString(R.string.analytics_no_search_yet)
        analyticsAdapter.submitList(state.events)
    }

    private fun buildSystemStatusText(state: EvalLiveFeed.DashboardState): String = buildString {
        appendLine("engine_ready: $isEngineReady")
        appendLine("index_ready: $isIndexReady")
        appendLine("indexing: $isIndexing")
        if (::indexer.isInitialized) appendLine("indexed_files: ${indexer.size}")
        appendLine("init_phase: ${app.currentInitStateOrReady().phase.name}")
        state.lastInitPhase?.let { appendLine("last_init: $it") }
        state.lastIndexSummary?.let { appendLine("last_index: $it") }
        state.lastGoldenStatus?.let { appendLine("golden_eval: $it") }
        appendLine("events_logged: ${state.totalEventCount}")
    }

    // ─────────────────────────────────────────────
    // Settings screen setup
    // ─────────────────────────────────────────────

    private fun setupSettingsScreen() {
        refreshYourNameRow()
        refreshMaxFilesRow()

        settingsBinding.rowYourName.apply {
            tvRowTitle.text = getString(R.string.settings_your_name)
            ivRowIcon.setImageResource(R.drawable.ic_search)
            ivChevron.visibility = View.VISIBLE
            switchRow.visibility = View.GONE
            root.setOnClickListener { showOnboardingOverlay(isFirstRun = false) }
        }

        settingsBinding.rowSemantic.apply {
            tvRowTitle.text = getString(R.string.settings_semantic)
            tvRowSub.text = getString(R.string.settings_semantic_sub)
            ivRowIcon.setImageResource(R.drawable.ic_search)
            switchRow.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            switchRow.isChecked = prefs.getBoolean(PREF_SEMANTIC_RERANK, true)
            switchRow.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_SEMANTIC_RERANK, isChecked).apply()
                if (!isChecked) showToast(getString(R.string.toast_semantic_off))
            }
        }
        settingsBinding.rowVoice.apply {
            tvRowTitle.text = getString(R.string.settings_voice)
            tvRowSub.text = getString(R.string.settings_voice_sub)
            ivRowIcon.setImageResource(R.drawable.ic_mic)
            switchRow.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            switchRow.isChecked = isVoiceSearchEnabled()
            switchRow.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_VOICE_SEARCH_ENABLED, isChecked).apply()
                if (!isChecked && voiceSearchManager.isListening) {
                    voiceCancelRequested = true
                    voiceSearchManager.stopListening()
                    resetListeningUi()
                }
                if (!isChecked) showToast(getString(R.string.toast_voice_off))
            }
        }
        settingsBinding.rowMultilingual.apply {
            tvRowTitle.text = getString(R.string.settings_multilingual)
            tvRowSub.text = getString(R.string.settings_multilingual_sub)
            ivRowIcon.setImageResource(R.drawable.ic_globe)
            switchRow.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            switchRow.isChecked = true
        }
        settingsBinding.rowContent.apply {
            tvRowTitle.text = getString(R.string.settings_content)
            tvRowSub.text = getString(R.string.settings_content_sub)
            ivRowIcon.setImageResource(R.drawable.ic_file)
            switchRow.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            switchRow.isChecked = true
        }
        settingsBinding.rowMaxFiles.apply {
            tvRowSub.text = getString(R.string.settings_max_files_sub)
            ivRowIcon.setImageResource(R.drawable.ic_shield)
            switchRow.visibility = View.GONE
            ivChevron.visibility = View.VISIBLE
            root.setOnClickListener { cycleMaxFiles() }
        }
        settingsBinding.rowAutoReindex.apply {
            tvRowTitle.text = getString(R.string.settings_auto_reindex)
            tvRowSub.text = getString(R.string.settings_auto_reindex_sub)
            ivRowIcon.setImageResource(R.drawable.ic_refresh)
            switchRow.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            switchRow.isChecked = prefs.getBoolean(PREF_AUTO_REINDEX, true)
            switchRow.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_AUTO_REINDEX, isChecked).apply()
                app.schedulePeriodicIndexing(isChecked)
            }
        }
        settingsBinding.rowSetupStatus.apply {
            tvRowTitle.text = getString(R.string.settings_setup_status)
            ivRowIcon.setImageResource(R.drawable.ic_refresh)
            switchRow.visibility = View.GONE
            ivChevron.visibility = View.GONE
        }
        refreshSettingsSystemStatus()
        settingsBinding.rowVersion.apply {
            tvRowTitle.text = getString(R.string.settings_version)
            tvRowSub.visibility = View.GONE
            llIconWrap.visibility = View.GONE
            switchRow.visibility = View.GONE
            ivChevron.visibility = View.GONE
        }
        settingsBinding.rowModel.apply {
            tvRowTitle.text = getString(R.string.settings_model)
            tvRowSub.text = getString(R.string.settings_model_val)
            llIconWrap.visibility = View.GONE
            switchRow.visibility = View.GONE
            ivChevron.visibility = View.GONE
        }
        settingsBinding.rowClear.apply {
            tvRowTitle.text = getString(R.string.settings_clear)
            tvRowTitle.setTextColor(getColor(R.color.ru_crimson))
            tvRowSub.text = getString(R.string.settings_clear_sub)
            ivRowIcon.setImageResource(R.drawable.ic_trash)
            ivRowIcon.setColorFilter(getColor(R.color.ru_crimson))
            switchRow.visibility = View.GONE
            ivChevron.visibility = View.VISIBLE
            root.setOnClickListener { clearIndex() }
        }
    }

    private fun cycleMaxFiles() {
        val current = prefs.getInt(PREF_MAX_FILES, 5_000)
        val idx = maxFilesOptions.indexOf(current).let { if (it < 0) 2 else it }
        val next = maxFilesOptions[(idx + 1) % maxFilesOptions.size]
        prefs.edit().putInt(PREF_MAX_FILES, next).apply()
        refreshMaxFilesRow()
        showToast(getString(R.string.toast_max_files, formatMaxFiles(next)))
    }

    private fun refreshMaxFilesRow() {
        val value = prefs.getInt(PREF_MAX_FILES, 5_000)
        settingsBinding.rowMaxFiles.tvRowTitle.text =
            getString(R.string.settings_max_files_fmt, formatMaxFiles(value))
    }

    private fun formatMaxFiles(value: Int): String = when (value) {
        1_000 -> "1,000"
        2_000 -> "2,000"
        5_000 -> "5,000"
        10_000 -> "10,000"
        else -> "%,d".format(value)
    }

    // ─────────────────────────────────────────────
    // Tagline animation
    // ─────────────────────────────────────────────

    private fun stopTaglineCycle() {
        taglineCycleActive = false
        taglineCycleRunnable?.let { taglineHandler.removeCallbacks(it) }
        taglineCycleRunnable = null
        if (::homeBinding.isInitialized) {
            homeBinding.tvTaglineHi.animate().cancel()
            homeBinding.tvTaglineEn.animate().cancel()
        }
    }

    private fun syncHomeDecorAnimations() {
        if (!::homeBinding.isInitialized || !::homeResultsUi.isInitialized) return
        val voiceActive = ::voiceSearchManager.isInitialized && voiceSearchManager.isListening
        val shouldRun = currentScreen == Screen.HOME &&
            !homeResultsUi.isResultsMode() &&
            !voiceActive
        if (shouldRun) {
            startTaglineCycle()
            startMicIdleAnimation()
        } else {
            stopTaglineCycle()
            if (!voiceActive) stopMicIdleAnimation()
        }
    }

    private fun startTaglineCycle() {
        if (taglineCycleActive) return
        taglineCycleActive = true
        taglineCycleRunnable?.let { taglineHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!taglineCycleActive || currentScreen != Screen.HOME || homeResultsUi.isResultsMode()) {
                    return
                }
                val fadeMs = uiAnimDuration(500L)
                val gapMs = uiAnimDuration(600L)
                val cur = if (taglineToggle) homeBinding.tvTaglineHi else homeBinding.tvTaglineEn
                val next = if (taglineToggle) homeBinding.tvTaglineEn else homeBinding.tvTaglineHi
                cur.animate().alpha(0f).setDuration(fadeMs).withEndAction {
                    if (!taglineCycleActive) return@withEndAction
                    taglineHandler.postDelayed({
                        if (taglineCycleActive) {
                            next.animate().alpha(1f).setDuration(fadeMs).start()
                        }
                    }, gapMs)
                }.start()
                taglineToggle = !taglineToggle
                if (taglineCycleActive) taglineHandler.postDelayed(this, 5200)
            }
        }
        taglineCycleRunnable = runnable
        taglineHandler.postDelayed(runnable, 5200)
    }

    // ─────────────────────────────────────────────
    // Engine init (Application-scoped — survives activity restarts)
    // ─────────────────────────────────────────────

    private fun attachToApp() {
        app = application as StriderApp

        app.observeInitProgress { state -> updateInitUi(state) }

        app.whenEngineReady { readyApp ->
            onEngineReady(readyApp)
        }
        app.whenIndexReady { readyApp ->
            onIndexReady(readyApp)
        }
    }

    private fun updateInitUi(state: AppInitState) {
        refreshSettingsSystemStatus(state)
        syncOnboardingSetupUi(state)
    }

    private fun syncOnboardingSetupUi(state: AppInitState) {
        if (!::binding.isInitialized) return
        val showSetup = app.isFirstModelLoad &&
            !state.isComplete &&
            binding.onboardingOverlay.visibility == View.VISIBLE
        binding.llOnboardingSetup.visibility = if (showSetup) View.VISIBLE else View.GONE
        if (!showSetup) return
        binding.tvOnboardingSetupMessage.text =
            state.message.ifBlank { getString(R.string.status_first_load) }
        if (state.progress >= 0) {
            binding.onboardingSetupProgressBar.progress = state.progress
        }
    }

    private fun onEngineReady(readyApp: StriderApp) {
        engine = readyApp.engine
        indexer = readyApp.indexer
        db = readyApp.db
        isEngineReady = true
        homeBinding.btnMic.isEnabled = true
        homeBinding.btnSearch.isEnabled = true
        syncSearchActionButton()
        refreshSettingsSystemStatus()

        if (prefs.getBoolean(PREF_AUTO_REINDEX, true)) {
            app.schedulePeriodicIndexing(true)
        }
    }

    private fun onIndexReady(readyApp: StriderApp) {
        indexer = readyApp.indexer
        db = readyApp.db
        isIndexReady = true

        val existingCount = indexer.size
        if (existingCount > 0) {
            updateIndexDialCount(existingCount)
            indexBinding.btnIndex.text = getString(R.string.btn_reindex)
            setIndexPhaseDone(existingCount)
            indexBinding.tvIndexHint.text = getString(R.string.index_hint_done)
            showBucketPills()
        } else if (isEngineReady) {
            refreshIndexIdleState()
        }
        refreshSettingsSystemStatus()
    }

    private fun refreshSettingsSystemStatus(state: AppInitState? = null) {
        if (!::settingsBinding.isInitialized) return
        val initState = state ?: app.currentInitStateOrReady()
        val subtitle = when {
            initState.isComplete -> {
                val count = if (::indexer.isInitialized) indexer.size else 0
                if (count > 0) getString(R.string.settings_status_ready_indexed, count)
                else getString(R.string.settings_status_ready)
            }
            initState.progress >= 0 ->
                getString(R.string.settings_status_loading, initState.message, initState.progress)
            else -> initState.message.ifBlank { getString(R.string.status_loading) }
        }
        settingsBinding.rowSetupStatus.tvRowSub.text = subtitle
        settingsBinding.rowSetupStatus.tvRowSub.visibility = View.VISIBLE
    }

    private fun setupOnboardingOverlay() {
        binding.btnOnboardingSave.setOnClickListener {
            val name = binding.etOnboardingName.text.toString().trim()
            if (name.isBlank()) {
                showToast(getString(R.string.onboarding_name_empty))
                return@setOnClickListener
            }
            saveUserName(name)
            hideOnboardingOverlay()
        }

        binding.btnOnboardingSkip.setOnClickListener {
            hideOnboardingOverlay()
        }
    }

    private fun showOnboardingOverlay(isFirstRun: Boolean) {
        onboardingIsFirstRun = isFirstRun
        binding.etOnboardingName.setText(UserProfile.getDisplayName(this) ?: "")
        binding.btnOnboardingSkip.visibility = if (isFirstRun) View.VISIBLE else View.GONE
        binding.tvOnboardingTitle.text = getString(R.string.onboarding_name_title)
        binding.tvOnboardingSub.text = getString(R.string.onboarding_name_sub)
        binding.onboardingOverlay.visibility = View.VISIBLE
        syncOnboardingSetupUi(app.currentInitStateOrReady())
        binding.onboardingOverlay.translationY = 12f * resources.displayMetrics.density
        binding.onboardingOverlay.animate().translationY(0f).setDuration(220).start()
        binding.etOnboardingName.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etOnboardingName, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideOnboardingOverlay() {
        binding.onboardingOverlay.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etOnboardingName.windowToken, 0)
        onboardingIsFirstRun = false
    }

    private fun refreshYourNameRow() {
        if (!::settingsBinding.isInitialized) return
        val name = UserProfile.getDisplayName(this)
        settingsBinding.rowYourName.tvRowTitle.text = getString(R.string.settings_your_name)
        settingsBinding.rowYourName.tvRowSub.text = name
            ?: getString(R.string.settings_your_name_not_set)
    }

    private fun saveUserName(name: String) {
        val hadName = UserProfile.isNameSet(this)
        UserProfile.saveName(this, name)
        refreshYourNameRow()

        if (isEngineReady && (hadName || db.getTotalCount() > 0)) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.invalidateAllForReindex()
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.name_saved_reindex))
                    app.enqueueIndexing(forceFull = false)
                }
            }
        }
    }

    private fun observeIndexingWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(IndexingWorker.UNIQUE_WORK_NAME)
            .observe(this) { workInfos ->
                val info = workInfos.firstOrNull() ?: return@observe
                updateIndexingUi(info)
            }
    }

    private fun updateIndexingUi(info: WorkInfo) {
        val status = info.progress.getString(IndexingWorker.KEY_STATUS)
        val count = info.progress.getInt(IndexingWorker.KEY_COUNT, 0)
        val total = info.progress.getInt(IndexingWorker.KEY_TOTAL, 0)
        val phase = info.progress.getString(IndexingWorker.KEY_PHASE)

        when (info.state) {
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                isIndexing = true
                setIndexRunningUi(count, total, status, phase, info.state)
            }
            WorkInfo.State.SUCCEEDED -> {
                isIndexing = false
                stopDialSweepAnimation()
                indexBinding.btnIndex.isEnabled = true
                indexBinding.btnIndex.alpha = 1f
                indexBinding.btnIndex.text = getString(R.string.btn_reindex)
                indexBinding.progressScanBar.visibility = View.GONE
                indexBinding.tvCurrentFile.visibility = View.GONE
                if (isEngineReady) {
                    indexer.loadFromDatabase()
                    val size = indexer.size
                    updateIndexDialCount(size)
                    indexBinding.indexDial.setProgress(1f)
                    setIndexPhaseDone(size)
                    indexBinding.tvIndexHint.text = getString(R.string.index_hint_done)
                    showBucketPills()
                    refreshSettingsSystemStatus()
                    showToast(getString(R.string.toast_index_complete, size))
                }
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                isIndexing = false
                stopDialSweepAnimation()
                indexBinding.btnIndex.isEnabled = true
                indexBinding.btnIndex.alpha = 1f
                indexBinding.progressScanBar.visibility = View.GONE
                indexBinding.tvCurrentFile.visibility = View.GONE
                refreshIndexIdleState()
                status?.let {
                    indexBinding.tvIndexPhase.text = it
                    showStatus(it)
                }
            }
            else -> { /* unused */ }
        }
    }

    private fun setIndexRunningUi(
        count: Int,
        total: Int,
        status: String?,
        phase: String?,
        state: WorkInfo.State
    ) {
        indexBinding.btnIndex.isEnabled = false
        indexBinding.btnIndex.alpha = 0.55f
        indexBinding.btnIndex.text = getString(R.string.btn_indexing)
        indexBinding.progressScanBar.visibility = View.VISIBLE
        indexBinding.tvCurrentFile.visibility = View.VISIBLE
        indexBinding.flexBuckets.visibility = View.GONE
        indexBinding.tvIndexHint.text = getString(R.string.index_hint_running)

        val msg = status ?: when (state) {
            WorkInfo.State.BLOCKED -> "Waiting — turn off battery saver for indexing"
            WorkInfo.State.ENQUEUED -> "Queued…"
            else -> "Scanning storage…"
        }
        indexBinding.tvCurrentFile.text = msg
        updateIndexDialCount(count)
        showStatus(msg)

        val bucket = extractBucketName(msg, phase)
        if (bucket != null) setIndexPhaseScanning(bucket)
        else indexBinding.tvIndexPhase.text = msg

        if (total > 0) {
            stopDialSweepAnimation()
            indexBinding.indexDial.setIndeterminate(false)
            indexBinding.indexDial.setProgress(count.toFloat() / total.toFloat())
        } else {
            indexBinding.indexDial.setIndeterminate(true)
            startDialSweepAnimation()
        }
    }

    private fun extractBucketName(status: String, phase: String?): String? {
        val known = listOf("Documents", "Downloads", "DCIM", "Camera", "WhatsApp", "Media", "Music")
        known.firstOrNull { status.contains(it, ignoreCase = true) }?.let { return it }
        return when (phase) {
            IndexingWorker.PHASE_SCANNING -> "Documents"
            IndexingWorker.PHASE_INDEXING -> "Documents"
            else -> null
        }
    }

    private fun setIndexPhaseScanning(bucket: String) {
        val full = getString(R.string.index_phase_scanning, bucket)
        val spannable = SpannableString(full)
        val start = full.indexOf(bucket)
        if (start >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(getColor(R.color.ru_crimson)),
                start, start + bucket.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start, start + bucket.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        indexBinding.tvIndexPhase.text = spannable
    }

    private fun setIndexPhaseDone(count: Int) {
        val countStr = count.toString()
        val full = getString(R.string.index_phase_done, countStr)
        val spannable = SpannableString(full)
        val start = full.indexOf(countStr)
        if (start >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(getColor(R.color.ru_crimson)),
                start, start + countStr.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start, start + countStr.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        indexBinding.tvIndexPhase.text = spannable
    }

    private fun updateIndexDialCount(count: Int) {
        indexBinding.tvDialCount.text = count.toString()
    }

    private fun refreshIndexIdleState() {
        val count = if (::indexer.isInitialized) indexer.size else 0
        updateIndexDialCount(count)
        indexBinding.indexDial.setIndeterminate(false)
        indexBinding.indexDial.setProgress(if (count > 0) 1f else 0f)
        indexBinding.btnIndex.isEnabled = true
        indexBinding.btnIndex.alpha = 1f
        indexBinding.btnIndex.text = if (count > 0) getString(R.string.btn_reindex) else getString(R.string.btn_index)
        indexBinding.progressScanBar.visibility = View.GONE
        indexBinding.tvCurrentFile.visibility = View.GONE
        if (count > 0) {
            setIndexPhaseDone(count)
            indexBinding.tvIndexHint.text = getString(R.string.index_hint_done)
            showBucketPills()
        } else {
            indexBinding.tvIndexPhase.text = getString(R.string.index_phase_idle)
            indexBinding.tvIndexHint.text = getString(R.string.index_hint_idle)
            indexBinding.flexBuckets.visibility = View.GONE
        }
    }

    private val dialSweepRunnable = object : Runnable {
        override fun run() {
            if (!isIndexing) return
            dialSweepDegrees = (dialSweepDegrees + 8f) % 360f
            indexBinding.indexDial.setIndeterminateSweep(dialSweepDegrees)
            dialAnimHandler.postDelayed(this, 32)
        }
    }

    private fun startDialSweepAnimation() {
        dialAnimHandler.removeCallbacks(dialSweepRunnable)
        dialAnimHandler.post(dialSweepRunnable)
    }

    private fun stopDialSweepAnimation() {
        dialAnimHandler.removeCallbacks(dialSweepRunnable)
        indexBinding.indexDial.setIndeterminate(false)
    }

    // ─────────────────────────────────────────────
    // Permissions + indexing
    // ─────────────────────────────────────────────

    private fun checkPermissionsAndIndex() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) startIndexing()
            else startActivityForResult(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }, REQUEST_MANAGE_STORAGE
            )
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) startIndexing()
            else ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ_STORAGE
            )
        }
    }

    private fun startIndexing() {
        if (!isEngineReady) {
            showToast("Model still loading…")
            return
        }
        resultsAdapter.submitList(emptyList())
        showStatus("Starting background index…")
        app.enqueueIndexing(forceFull = false)
    }

    private fun showBucketPills() {
        val buckets = indexer.getBucketSizes().filter { it.value > 0 }
        if (buckets.isEmpty()) {
            indexBinding.flexBuckets.visibility = View.GONE
            return
        }

        indexBinding.flexBuckets.removeAllViews()
        val colors = mapOf(
            Category.WORK to R.color.cat_work,
            Category.IDENTITY to R.color.cat_identity,
            Category.EDUCATION to R.color.cat_education,
            Category.PERSONAL to R.color.cat_personal,
            Category.MEDIA to R.color.cat_media,
            Category.GENERAL to R.color.cat_general,
        )

        buckets.forEach { (cat, count) ->
            val chip = ItemBucketChipBinding.inflate(LayoutInflater.from(this), indexBinding.flexBuckets, false)
            val color = getColor(colors[cat] ?: R.color.cat_general)
            chip.vBucketDot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            chip.tvBucketCount.text = count.toString()
            chip.tvBucketCount.setTextColor(color)
            chip.tvBucketName.text = RuUi.capitalizeCategory(cat.label)
            indexBinding.flexBuckets.addView(chip.root)
        }
        indexBinding.flexBuckets.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────

    private fun resetSearchUi(animated: Boolean = true) {
        searchGeneration++
        searchJob?.cancel()
        cancelSearchDebounce()
        dismissSharePicker()
        if (::resultsAdapter.isInitialized) {
            resultsAdapter.submitList(emptyList())
        }
        if (::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()) {
            homeResultsUi.closeResults(animated)
        }
        syncHomeDecorAnimations()
        syncSearchActionButton()
    }

    private fun isSemanticEnabled(): Boolean =
        prefs.getBoolean(PREF_SEMANTIC_RERANK, true)

    private fun cancelSearchDebounce() {
        searchDebounceJob?.cancel()
        searchDebounceJob = null
    }

    /** Debounce for in-results query refinement only — not used on idle home. */
    private fun searchDebounceMs(): Long {
        val largeLibrary = ::indexer.isInitialized && indexer.size >= 5_000
        return if (largeLibrary) 800L else 650L
    }

    private fun scheduleLiveSearch() {
        if (!::homeResultsUi.isInitialized || !homeResultsUi.isResultsMode()) return
        if (::voiceSearchManager.isInitialized && voiceSearchManager.isListening) return
        if (searchJob?.isActive == true) return
        cancelSearchDebounce()
        searchDebounceJob = lifecycleScope.launch {
            delay(searchDebounceMs())
            if (searchJob?.isActive == true) return@launch
            performSearchWithFilter(null, trigger = "live_refine")
        }
    }

    private fun submitSearchFromUser(trigger: String) {
        cancelSearchDebounce()
        val rawQuery = homeBinding.etSearch.text.toString().trim()
        if (rawQuery.length < 2) {
            return
        }
        performSearch(trigger)
    }

    private fun performSearch(trigger: String = "ime_search") {
        performSearchWithFilter(null, semanticEnabled = isSemanticEnabled(), trigger = trigger)
    }

    /** Search always runs once the index subsystem is ready; this gates status messaging only. */
    private fun isSearchWarmingUp(indexedCount: Int): Boolean =
        isIndexing && indexedCount < MIN_FILES_SEARCH_UNLOCK

    private fun statusForSearchResults(
        results: List<SearchResult>,
        rawQuery: String,
        indexedCount: Int,
        categoryHints: List<Category>
    ): String {
        if (results.isEmpty() && indexedCount == 0) {
            return if (isIndexing) getString(R.string.search_indexing_scanning)
            else getString(R.string.search_no_files_hint)
        }
        if (isSearchWarmingUp(indexedCount)) {
            return getString(R.string.search_indexing_warming_up, indexedCount, MIN_FILES_SEARCH_UNLOCK)
        }
        val catInfo = if (categoryHints.isNotEmpty())
            " [${categoryHints.joinToString { it.label }}]" else ""
        return if (results.isEmpty()) getString(R.string.no_results, rawQuery)
        else getString(R.string.results_count, results.size, rawQuery) + catInfo
    }

    private fun performSearchWithFilter(
        categoryFilter: List<Category>?,
        semanticEnabled: Boolean = isSemanticEnabled(),
        trigger: String = if (categoryFilter != null) "category_filter" else "ime_search"
    ) {
        val rawQuery = homeBinding.etSearch.text.toString().trim()
        if (rawQuery.isEmpty()) return
        if (!isIndexReady || !::indexer.isInitialized) {
            showToast(getString(R.string.status_loading))
            return
        }
        if (semanticEnabled && !isEngineReady) {
            showToast(getString(R.string.status_loading))
            return
        }

        val isLiveRefine = trigger == "live_refine" &&
            ::homeResultsUi.isInitialized &&
            homeResultsUi.isPanelVisible()

        EvalLogger.setSearchContext(trigger)
        val queryGeneration = ++searchGeneration
        if (!isLiveRefine) {
            dismissSharePicker()
            homeResultsUi.onSearchStarted()
            syncHomeDecorAnimations()
            syncSearchActionButton()
        } else {
            homeResultsUi.onSearchRefining()
        }
        searchJob?.cancel()
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enriched = enrichQuery(rawQuery, UserProfile.getNameTokens(this@MainActivity))
                val useSemantic = semanticEnabled || enriched.actionIntent != null
                val outcome = indexer.searchWithDiagnostics(
                    enriched,
                    topK = 20,
                    semanticEnabled = useSemantic,
                    trackHint = null
                )
                var results = outcome.results
                lastTargetReport = outcome.diagnostics?.targetReport

                if (!categoryFilter.isNullOrEmpty()) {
                    results = results.filter { r ->
                        r.file.categories.any { it in categoryFilter }
                    }
                }

                val indexedCount = indexer.size

                withContext(Dispatchers.Main) {
                    if (queryGeneration != searchGeneration) return@withContext
                    val currentQuery = homeBinding.etSearch.text.toString().trim()
                    if (currentQuery.length < 2 || currentQuery != rawQuery) return@withContext
                    lastSearchResults = results
                    val shareRefinePending = isLiveRefine &&
                        enriched.actionIntent != null &&
                        results.isNotEmpty() &&
                        pendingShareResults != null
                    homeResultsUi.onSearchFinished(
                        results,
                        rawQuery,
                        resultsAdapter,
                        isRefine = isLiveRefine,
                        onListApplied = if (shareRefinePending) {
                            { refreshSharePickerResults(results, enriched.shareTarget) }
                        } else {
                            null
                        },
                    )
                    if (!isLiveRefine) syncHomeDecorAnimations()

                    if (enriched.actionIntent != null) {
                        if (results.isNotEmpty()) {
                            if (!shareRefinePending) {
                                showSharePicker(results, enriched.shareTarget)
                            }
                            homeBinding.tvStatus.visibility = View.GONE
                        } else {
                            dismissSharePicker()
                            showStatus(statusForSearchResults(results, rawQuery, indexedCount, enriched.categoryHints))
                            if (!isSearchWarmingUp(indexedCount) && indexedCount > 0) {
                                showToast(getString(R.string.share_no_file))
                            }
                        }
                    } else {
                        dismissSharePicker()
                        maybeShowSearchStatus(results, rawQuery, indexedCount, enriched.categoryHints)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (queryGeneration != searchGeneration) return@withContext
                    val currentQuery = homeBinding.etSearch.text.toString().trim()
                    if (currentQuery.length < 2 || currentQuery != rawQuery) return@withContext
                    showStatus("Search error: ${e.message}")
                    homeResultsUi.onSearchFinished(emptyList(), rawQuery, resultsAdapter, isRefine = isLiveRefine)
                    if (!isLiveRefine) syncHomeDecorAnimations()
                }
            } finally {
                // Search UI state is finalized in onSearchFinished; no extra UI work here.
            }
        }
    }

    // ─────────────────────────────────────────────
    // Share picker
    // ─────────────────────────────────────────────

    private fun maybeShowSearchStatus(
        results: List<SearchResult>,
        rawQuery: String,
        indexedCount: Int,
        categoryHints: List<Category>,
    ) {
        if (results.isNotEmpty() && !isSearchWarmingUp(indexedCount)) {
            homeBinding.tvStatus.visibility = View.GONE
            return
        }
        showStatus(statusForSearchResults(results, rawQuery, indexedCount, categoryHints))
    }

    private fun showSharePicker(results: List<SearchResult>, shareTarget: String?) {
        pendingShareResults = results
        pendingShareIndex = 0
        pendingShareTarget = shareTarget
        val animateIn = homeBinding.llSharePicker.visibility != View.VISIBLE
        homeBinding.tvStatus.visibility = View.GONE
        if (!resultsAdapter.shareModeEnabled()) {
            resultsAdapter.setShareMode(true)
        }
        if (animateIn) {
            homeBinding.llSharePicker.alpha = 0f
            homeBinding.llSharePicker.visibility = View.VISIBLE
            homeBinding.llSharePicker.animate()
                .alpha(1f)
                .setDuration(uiAnimDuration(220L))
                .start()
        }
        updateSharePickerUi()
    }

    private fun refreshSharePickerResults(results: List<SearchResult>, shareTarget: String?) {
        val previousPath = pendingShareResults?.getOrNull(pendingShareIndex)?.file?.path
        pendingShareResults = results
        pendingShareTarget = shareTarget
        pendingShareIndex = if (previousPath != null) {
            results.indexOfFirst { it.file.path == previousPath }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        updateSharePickerUi()
    }

    private fun updateSharePickerUi() {
        val results = pendingShareResults ?: return
        if (results.isEmpty()) {
            dismissSharePicker()
            return
        }
        pendingShareIndex = pendingShareIndex.coerceIn(0, results.lastIndex)
        val file = results[pendingShareIndex].file
        homeBinding.tvSharePickerFile.text = file.name
        homeBinding.tvSharePickerPosition.text =
            getString(R.string.share_picker_position, pendingShareIndex + 1, results.size)
        homeBinding.btnShareNext.alpha = if (pendingShareIndex < results.size - 1) 1f else 0.4f
        homeBinding.btnShareNext.isEnabled = pendingShareIndex < results.size - 1
        resultsAdapter.setShareSelection(file.path)
        homeBinding.rvResults.post {
            (homeBinding.rvResults.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pendingShareIndex, 0)
        }
    }

    private fun advanceShareSelection() {
        val results = pendingShareResults ?: return
        if (pendingShareIndex < results.size - 1) {
            pendingShareIndex++
            updateSharePickerUi()
        }
    }

    private fun shareCurrentSelection() {
        val results = pendingShareResults ?: return
        val file = results.getOrNull(pendingShareIndex)?.file ?: return
        ShareManager.share(this, file, pendingShareTarget)
        showStatus(getString(R.string.sharing_file, file.name))
        dismissSharePicker()
    }

    private fun dismissSharePicker() {
        pendingShareResults = null
        pendingShareTarget = null
        pendingShareIndex = 0
        if (::homeBinding.isInitialized) {
            homeBinding.llSharePicker.visibility = View.GONE
        }
        if (::resultsAdapter.isInitialized) {
            resultsAdapter.setShareMode(false)
        }
    }

    // ─────────────────────────────────────────────
    // File opening
    // ─────────────────────────────────────────────

    private fun openFile(file: IndexedFile) {
        userDismissedSearchFocus = true
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", File(file.path))
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            showToast("Cannot open: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // Voice search
    // ─────────────────────────────────────────────

    private fun isVoiceSearchEnabled(): Boolean =
        prefs.getBoolean(PREF_VOICE_SEARCH_ENABLED, true)

    private fun handleMicClick() {
        if (!isVoiceSearchEnabled()) {
            showToast(getString(R.string.voice_disabled))
            return
        }
        if (!isIndexReady || !::indexer.isInitialized) {
            showToast(getString(R.string.status_loading))
            return
        }
        if (!isEngineReady) {
            showToast(getString(R.string.status_loading))
            return
        }
        if (voiceSearchManager.isListening) {
            voiceCancelRequested = true
            voiceSearchManager.stopListening()
            resetListeningUi()
            return
        }
        if (!voiceSearchManager.isAvailable()) {
            showToast(getString(R.string.voice_not_available))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
            return
        }
        startVoiceListening()
    }

    private fun startVoiceListening() {
        userDismissedSearchFocus = true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(homeBinding.etSearch.windowToken, 0)
        statusBeforeListening = homeBinding.tvStatus.text?.toString()
        voiceSearchManager.startListening()
    }

    private fun resetListeningUi() {
        setListeningUi(false)
        statusBeforeListening?.let { showStatus(it) }
        statusBeforeListening = null
    }

    private fun setListeningUi(listening: Boolean) {
        if (!::homeBinding.isInitialized) return
        val fadeMs = uiAnimDuration(400L)
        if (listening) {
            stopTaglineCycle()
            stopMicIdleAnimation()
            homeBinding.vMicBreathe.visibility = View.GONE
            homeBinding.etSearch.visibility = View.GONE
            homeBinding.llVoiceEq.visibility = View.VISIBLE
            homeBinding.tvSearchHintIdle.animate().alpha(0f).setDuration(fadeMs).start()
            homeBinding.tvSearchHintLive.animate().alpha(1f).setDuration(fadeMs).start()
            homeBinding.btnMic.setBackgroundResource(R.drawable.bg_mic_btn_listening)
            startVoiceEqAnimation()
            startMicListeningRings()
            startHintDotBlink()
        } else {
            homeBinding.vMicBreathe.visibility = View.VISIBLE
            homeBinding.etSearch.visibility = View.VISIBLE
            homeBinding.llVoiceEq.visibility = View.GONE
            homeBinding.tvSearchHintIdle.animate().alpha(1f).setDuration(fadeMs).start()
            homeBinding.tvSearchHintLive.animate().alpha(0f).setDuration(fadeMs).start()
            homeBinding.btnMic.setBackgroundResource(R.drawable.bg_mic_btn)
            stopVoiceEqAnimation()
            stopMicListeningRings()
            stopHintDotBlink()
        }
        syncSearchActionButton()
        syncHomeDecorAnimations()
    }

    private fun startMicListeningRings() {
        stopMicListeningRings()
        if (animatorDurationScale() <= 0f) return
        val rings = listOf(homeBinding.vMicRing1, homeBinding.vMicRing2)
        rings.forEach { it.visibility = View.VISIBLE }
        micRingAnimators = rings.mapIndexed { index, ring ->
            ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 2.4f).apply {
                duration = 1800L
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 600L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    ring.scaleY = ring.scaleX
                    ring.alpha = (1.1f - ring.scaleX).coerceIn(0f, 0.55f)
                }
            }.also { it.start() }
        }
    }

    private fun stopMicListeningRings() {
        micRingAnimators?.forEach { it.cancel() }
        micRingAnimators = null
        listOf(homeBinding.vMicRing1, homeBinding.vMicRing2).forEach {
            it.visibility = View.GONE
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 0f
        }
    }

    private fun startHintDotBlink() {
        stopHintDotBlink()
        if (animatorDurationScale() <= 0f) return
        hintDotAnimator = ObjectAnimator.ofFloat(homeBinding.vHintLiveDot, View.ALPHA, 1f, 0.25f).apply {
            duration = 1100L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopHintDotBlink() {
        hintDotAnimator?.cancel()
        hintDotAnimator = null
        homeBinding.vHintLiveDot.alpha = 1f
    }

    private fun startVoiceEqAnimation() {
        stopVoiceEqAnimation()
        if (animatorDurationScale() <= 0f) return
        homeBinding.llVoiceEq.post {
            val bars = (0 until homeBinding.llVoiceEq.childCount).map { homeBinding.llVoiceEq.getChildAt(it) }
            eqAnimators = bars.mapIndexed { index, bar ->
                bar.pivotY = bar.height / 2f
                ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.4f, 1f).apply {
                    duration = 1000L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    startDelay = index * 120L
                    interpolator = AccelerateDecelerateInterpolator()
                }
            }
            eqAnimators?.forEach { it.start() }
        }
    }

    private fun stopVoiceEqAnimation() {
        eqAnimators?.forEach { it.cancel() }
        eqAnimators = null
        for (i in 0 until homeBinding.llVoiceEq.childCount) {
            homeBinding.llVoiceEq.getChildAt(i).scaleY = 1f
        }
    }

    private val voiceSearchCallbacks = object : VoiceSearchManager.Callbacks {
        override fun onListeningStarted() {
            setListeningUi(true)
        }

        override fun onListeningEnded() {
            if (!voiceCancelRequested) setListeningUi(false)
        }

        override fun onPartialResult(text: String) {
            homeBinding.etSearch.setText(text)
            homeBinding.etSearch.setSelection(text.length)
        }

        override fun onResult(text: String) {
            voiceCancelRequested = false
            resetListeningUi()
            homeBinding.etSearch.setText(text)
            homeBinding.etSearch.setSelection(text.length)
            cancelSearchDebounce()
            performSearch("voice")
        }

        override fun onError(messageResId: Int) {
            if (voiceCancelRequested) {
                voiceCancelRequested = false
                resetListeningUi()
                return
            }
            resetListeningUi()
            showToast(getString(messageResId))
        }
    }

    // ─────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────

    private fun showStatus(msg: String) {
        if (!::homeBinding.isInitialized) return
        if (pendingShareResults != null) {
            homeBinding.tvStatus.visibility = View.GONE
            return
        }
        homeBinding.tvStatus.text = msg
        if (::homeResultsUi.isInitialized && homeResultsUi.isResultsMode()) {
            homeBinding.tvStatus.visibility = View.VISIBLE
        }
    }

    private fun showProgress(show: Boolean) {
        if (show && ::homeResultsUi.isInitialized) homeResultsUi.onSearchStarted()
    }

    private fun showToast(msg: String) {
        toastHideRunnable?.let { toastHandler.removeCallbacks(it) }
        binding.llToast.animate().cancel()
        binding.tvToast.text = msg
        binding.llToast.alpha = 1f
        binding.llToast.visibility = View.VISIBLE
        val hide = Runnable {
            binding.llToast.animate().alpha(0f).setDuration(200).withEndAction {
                binding.llToast.visibility = View.GONE
            }.start()
        }
        toastHideRunnable = hide
        toastHandler.postDelayed(hide, 2400)
    }

    private fun clearIndex() {
        if (!isEngineReady) {
            showToast("Model still loading…")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            db.deleteFiles(db.getStoredFileMeta().keys.toList())
            db.ftsClear()
            db.invalidateEmbeddingCache()
            indexer.clear()
            withContext(Dispatchers.Main) {
                refreshIndexIdleState()
                indexBinding.flexBuckets.visibility = View.GONE
                resultsAdapter.submitList(emptyList())
                homeResultsUi.closeResults(animated = false)
                showToast(getString(R.string.index_cleared))
                refreshSettingsSystemStatus()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Permissions callbacks
    // ─────────────────────────────────────────────

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_READ_STORAGE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startIndexing()
                else showToast(getString(R.string.permission_denied))
            }
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceListening()
                else showToast(getString(R.string.voice_permission_denied))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) startIndexing()
        else showToast("Full storage access not granted")
    }

    override fun onResume() {
        super.onResume()
        syncHomeDecorAnimations()
    }

    override fun onPause() {
        stopTaglineCycle()
        if (!::voiceSearchManager.isInitialized || !voiceSearchManager.isListening) {
            stopMicIdleAnimation()
            stopMicListeningRings()
            stopHintDotBlink()
            stopVoiceEqAnimation()
        }
        super.onPause()
    }

    override fun onDestroy() {
        detachAnalyticsListener()
        stopDialSweepAnimation()
        dialAnimHandler.removeCallbacksAndMessages(null)
        toastHideRunnable?.let { toastHandler.removeCallbacks(it) }
        toastHandler.removeCallbacksAndMessages(null)
        stopVoiceEqAnimation()
        stopMicIdleAnimation()
        stopMicListeningRings()
        stopHintDotBlink()
        stopTaglineCycle()
        taglineHandler.removeCallbacksAndMessages(null)
        searchFocusHandler.removeCallbacksAndMessages(null)
        cancelSearchDebounce()
        searchJob?.cancel()
        if (::homeResultsUi.isInitialized) homeResultsUi.destroy()
        if (::voiceSearchManager.isInitialized) voiceSearchManager.destroy()
        super.onDestroy()
        // Engine, indexer, and DB live in StriderApp — do not close here
    }

    companion object {
        /** First-index UX: show warming-up status until this many files are in SQLite. Search is never blocked. */
        private const val MIN_FILES_SEARCH_UNLOCK = 10

        private const val PREFS_NAME = "strider_quanto_prefs"
        private const val PREF_VOICE_SEARCH_ENABLED = "voice_search_enabled"
        private const val PREF_SEMANTIC_RERANK = "semantic_rerank_enabled"
        private const val PREF_AUTO_REINDEX = "auto_reindex_enabled"
        private const val PREF_LAST_TAB = "last_tab"
        private const val PREF_MAX_FILES = "max_files_index"
        private const val REQUEST_READ_STORAGE   = 100
        private const val REQUEST_MANAGE_STORAGE = 101
        private const val REQUEST_RECORD_AUDIO   = 102
    }
}