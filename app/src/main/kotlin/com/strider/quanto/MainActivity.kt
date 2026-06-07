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
import com.strider.quanto.databinding.FragmentHomeBinding
import com.strider.quanto.databinding.FragmentIndexBinding
import com.strider.quanto.databinding.FragmentSettingsBinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.File

enum class Screen { HOME, INDEX, SETTINGS }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: StriderApp
    private lateinit var engine: EmbeddingEngine
    private lateinit var indexer: FileIndexer
    private lateinit var db: DatabaseHelper
    private lateinit var resultsAdapter: ResultsAdapter

    private var isEngineReady = false
    private var isIndexReady = false
    private var setupOverlayDismissed = false
    private var isIndexing = false
    private var currentScreen = Screen.HOME

    // View bindings for each screen (inflated once, swapped in/out)
    private lateinit var homeBinding: FragmentHomeBinding
    private lateinit var indexBinding: FragmentIndexBinding
    private lateinit var settingsBinding: FragmentSettingsBinding

    private val taglineHandler = Handler(Looper.getMainLooper())
    private var taglineToggle = false

    private lateinit var prefs: SharedPreferences
    private lateinit var voiceSearchManager: VoiceSearchManager
    private var statusBeforeListening: String? = null
    private var voiceCancelRequested = false
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var searchGeneration = 0

    private var pendingShareResults: List<SearchResult>? = null
    private var pendingShareIndex = 0
    private var pendingShareTarget: String? = null
    private var onboardingIsFirstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as StriderApp

        PDFBoxResourceLoader.init(applicationContext)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        voiceSearchManager = VoiceSearchManager(this, voiceSearchCallbacks)

        inflateScreens()
        setupNavBar()
        showScreen(Screen.HOME)
        setupHomeScreen()
        setupIndexScreen()
        setupSettingsScreen()
        setupOnboardingOverlay()
        attachToApp()
        observeIndexingWork()
        startTaglineCycle()

        if (!UserProfile.isNameSet(this)) {
            showOnboardingOverlay(isFirstRun = true)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (binding.onboardingOverlay.visibility == View.VISIBLE) {
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
        settingsBinding = FragmentSettingsBinding.inflate(layoutInflater)
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        binding.flContent.removeAllViews()
        val view = when (screen) {
            Screen.HOME     -> homeBinding.root
            Screen.INDEX    -> indexBinding.root
            Screen.SETTINGS -> settingsBinding.root
        }
        binding.flContent.addView(view)
        updateNavBar(screen)
    }

    // ─────────────────────────────────────────────
    // Nav bar
    // ─────────────────────────────────────────────

    private fun setupNavBar() {
        binding.navHome.setOnClickListener     { showScreen(Screen.HOME) }
        binding.navIndex.setOnClickListener    { showScreen(Screen.INDEX) }
        binding.navSettings.setOnClickListener { showScreen(Screen.SETTINGS) }
    }

    private fun updateNavBar(screen: Screen) {
        val crimson   = getColor(R.color.ru_crimson)
        val secondary = getColor(R.color.text_secondary)
        val bold      = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val normal    = android.graphics.Typeface.DEFAULT

        listOf(
            Triple(binding.navHomeIcon,     binding.navHomeLabel,     screen == Screen.HOME),
            Triple(binding.navIndexIcon,    binding.navIndexLabel,    screen == Screen.INDEX),
            Triple(binding.navSettingsIcon, binding.navSettingsLabel, screen == Screen.SETTINGS),
        ).forEach { (icon, label, active) ->
            icon.setColorFilter(if (active) crimson else secondary)
            label.setTextColor(if (active) crimson else secondary)
            label.typeface = if (active) bold else normal
        }
    }

    // ─────────────────────────────────────────────
    // Home screen setup
    // ─────────────────────────────────────────────

    private fun setupHomeScreen() {
        resultsAdapter = ResultsAdapter { result ->
            if (pendingShareResults != null) {
                val idx = pendingShareResults!!.indexOfFirst { it.file.path == result.file.path }
                if (idx >= 0) {
                    pendingShareIndex = idx
                    updateSharePickerUi()
                }
            } else {
                openFile(result.file)
            }
        }
        homeBinding.rvResults.apply {
            adapter = resultsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = false
        }

        homeBinding.btnMic.isEnabled = false

        homeBinding.btnIndexShortcut.setOnClickListener { showScreen(Screen.INDEX) }

        homeBinding.etSearch.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchDebounceJob?.cancel()
                    performSearchWithFilter(null, semanticEnabled = isSemanticEnabled())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString()?.trim() ?: ""
                    searchDebounceJob?.cancel()
                    if (text.length < 2) {
                        resultsAdapter.submitList(emptyList())
                        showProgress(false)
                        return
                    }
                    searchDebounceJob = lifecycleScope.launch {
                        delay(450)
                        // Fast lexical preview while typing; full Granite on keyboard Search
                        performSearchWithFilter(null, semanticEnabled = false)
                    }
                }
            })
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        homeBinding.llSearchBar.setOnClickListener {
            homeBinding.etSearch.requestFocus()
        }

        homeBinding.btnMic.setOnClickListener {
            handleMicClick()
        }

        homeBinding.btnShareNext.setOnClickListener { advanceShareSelection() }
        homeBinding.btnShareConfirm.setOnClickListener { shareCurrentSelection() }
        homeBinding.btnShareCancel.setOnClickListener { dismissSharePicker() }

        // Category chip filtering
        val chips = listOf(
            homeBinding.chipAll  to null,
            homeBinding.chipDocs to listOf(Category.WORK, Category.EDUCATION, Category.IDENTITY),
            homeBinding.chipMedia to listOf(Category.MEDIA, Category.PERSONAL),
            homeBinding.chipWork to listOf(Category.WORK),
            homeBinding.chipId   to listOf(Category.IDENTITY),
        )
        chips.forEach { (chip, cats) ->
            chip.setOnClickListener {
                chips.forEach { (c, _) ->
                    c.setBackgroundResource(R.drawable.bg_category_chip)
                    c.setTextColor(getColor(R.color.text_secondary))
                }
                chip.setBackgroundResource(R.drawable.bg_category_chip_selected)
                chip.setTextColor(getColor(android.R.color.white))
                performSearchWithFilter(cats)
            }
        }
    }

    // ─────────────────────────────────────────────
    // Index screen setup
    // ─────────────────────────────────────────────

    private fun setupIndexScreen() {
        indexBinding.btnBack.setOnClickListener { showScreen(Screen.HOME) }
        indexBinding.btnIndex.setOnClickListener {
            if (!isEngineReady) { showToast("Model still loading…"); return@setOnClickListener }
            if (isIndexing) return@setOnClickListener
            checkPermissionsAndIndex()
        }
    }

    // ─────────────────────────────────────────────
    // Settings screen setup
    // ─────────────────────────────────────────────

    private fun setupSettingsScreen() {
        settingsBinding.btnBack.setOnClickListener { showScreen(Screen.HOME) }

        refreshYourNameRow()
        settingsBinding.rowYourName.apply {
            ivRowIcon.setImageResource(R.drawable.ic_search)
            ivChevron.visibility = View.VISIBLE
            switchRow.visibility = View.GONE
            root.setOnClickListener { showOnboardingOverlay(isFirstRun = false) }
        }

        // Configure each row
        settingsBinding.rowSemantic.apply {
            tvRowTitle.text = getString(R.string.settings_semantic)
            tvRowSub.text   = "Granite AI finds files by content, not just filename"
            ivRowIcon.setImageResource(R.drawable.ic_search)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = prefs.getBoolean(PREF_SEMANTIC_RERANK, true)
            switchRow.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_SEMANTIC_RERANK, isChecked).apply()
            }
        }
        settingsBinding.rowVoice.apply {
            tvRowTitle.text = getString(R.string.settings_voice)
            tvRowSub.text   = getString(R.string.settings_voice_sub)
            ivRowIcon.setImageResource(R.drawable.ic_mic)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = isVoiceSearchEnabled()
            switchRow.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_VOICE_SEARCH_ENABLED, isChecked).apply()
                if (!isChecked && voiceSearchManager.isListening) {
                    voiceCancelRequested = true
                    voiceSearchManager.stopListening()
                    resetListeningUi()
                }
            }
        }
        settingsBinding.rowMultilingual.apply {
            tvRowTitle.text = getString(R.string.settings_multilingual)
            tvRowSub.text   = getString(R.string.settings_multilingual_sub)
            ivRowIcon.setImageResource(R.drawable.ic_globe)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = true
        }
        settingsBinding.rowContent.apply {
            tvRowTitle.text = getString(R.string.settings_content)
            tvRowSub.text   = getString(R.string.settings_content_sub)
            ivRowIcon.setImageResource(R.drawable.ic_file)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = true
        }
        settingsBinding.rowMaxFiles.apply {
            tvRowTitle.text = getString(R.string.settings_max_files)
            tvRowSub.text   = getString(R.string.settings_max_files_sub)
            ivRowIcon.setImageResource(R.drawable.ic_shield)
            ivChevron.visibility = View.VISIBLE
        }
        settingsBinding.rowAutoReindex.apply {
            tvRowTitle.text = "Auto re-index"
            tvRowSub.text   = "Daily delta updates"
            ivRowIcon.setImageResource(R.drawable.ic_refresh)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = prefs.getBoolean(PREF_AUTO_REINDEX, false)
            switchRow.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_AUTO_REINDEX, isChecked).apply()
                app.schedulePeriodicIndexing(isChecked)
            }
        }
        settingsBinding.rowVersion.apply {
            tvRowTitle.text = "RU"
            tvRowSub.text   = getString(R.string.settings_version)
            llIconWrap.visibility = View.GONE
        }
        settingsBinding.rowModel.apply {
            tvRowTitle.text = getString(R.string.settings_model)
            tvRowSub.text   = getString(R.string.settings_model_val)
            llIconWrap.visibility = View.GONE
        }
        settingsBinding.rowClear.apply {
            tvRowTitle.text  = getString(R.string.settings_clear)
            tvRowTitle.setTextColor(getColor(R.color.ru_crimson))
            tvRowSub.text    = getString(R.string.settings_clear_sub)
            llIconWrap.visibility = View.GONE
            ivChevron.visibility = View.VISIBLE
            root.setOnClickListener { clearIndex() }
        }
    }

    // ─────────────────────────────────────────────
    // Tagline animation
    // ─────────────────────────────────────────────

    private fun startTaglineCycle() {
        taglineHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!::homeBinding.isInitialized) return
                taglineToggle = !taglineToggle
                homeBinding.tvTagline.animate().alpha(0f).setDuration(280).withEndAction {
                    homeBinding.tvTagline.text = if (taglineToggle)
                        getString(R.string.tagline_hi) else getString(R.string.tagline_en)
                    homeBinding.tvTagline.animate().alpha(1f).setDuration(280).start()
                }.start()
                taglineHandler.postDelayed(this, 3800)
            }
        }, 3800)
    }

    // ─────────────────────────────────────────────
    // Engine init (Application-scoped — survives activity restarts)
    // ─────────────────────────────────────────────

    private fun attachToApp() {
        app = application as StriderApp

        binding.btnSetupContinue.setOnClickListener {
            setupOverlayDismissed = true
            binding.loadingOverlay.visibility = View.GONE
            if (!app.currentInitStateOrReady().isComplete) {
                binding.initBanner.visibility = View.VISIBLE
            }
        }

        if (app.isFirstModelLoad && !setupOverlayDismissed) {
            binding.loadingOverlay.visibility = View.VISIBLE
        } else if (!app.isEngineReady) {
            binding.initBanner.visibility = View.VISIBLE
            binding.tvInitStatus.text = getString(R.string.init_model_loading_bg)
        }

        app.observeInitProgress { state -> updateInitUi(state) }

        app.whenEngineReady { readyApp ->
            onEngineReady(readyApp)
        }
        app.whenIndexReady { readyApp ->
            onIndexReady(readyApp)
        }
    }

    private fun updateInitUi(state: AppInitState) {
        if (state.isComplete) {
            binding.initBanner.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
            return
        }

        binding.tvInitStatus.text = state.message
        binding.tvLoadingMessage.text = state.message

        if (state.progress >= 0) {
            binding.initProgressBar.progress = state.progress
            binding.setupProgressBar.progress = state.progress
        }

        if (setupOverlayDismissed || !app.isFirstModelLoad) {
            binding.initBanner.visibility = View.VISIBLE
        }
    }

    private fun onEngineReady(readyApp: StriderApp) {
        engine = readyApp.engine
        indexer = readyApp.indexer
        db = readyApp.db
        isEngineReady = true
        homeBinding.btnMic.isEnabled = true

        if (prefs.getBoolean(PREF_AUTO_REINDEX, false)) {
            app.schedulePeriodicIndexing(true)
        }
    }

    private fun onIndexReady(readyApp: StriderApp) {
        indexer = readyApp.indexer
        db = readyApp.db
        isIndexReady = true

        val existingCount = indexer.size
        if (existingCount > 0) {
            showStatus("✓ $existingCount files ready")
            indexBinding.tvIndexCount.text = "$existingCount"
            showBucketPills()
        } else if (isEngineReady) {
            showStatus(getString(R.string.status_ready))
        }
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
        val phase = info.progress.getString(IndexingWorker.KEY_PHASE)

        when (info.state) {
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                isIndexing = true
                indexBinding.btnIndex.isEnabled = false
                indexBinding.llIdleState.visibility = View.GONE
                indexBinding.llRunningState.visibility = View.VISIBLE

                val msg = status ?: when (info.state) {
                    WorkInfo.State.BLOCKED ->
                        "Waiting — turn off battery saver for indexing"
                    WorkInfo.State.ENQUEUED -> "Queued…"
                    else -> "Scanning storage…"
                }
                indexBinding.tvCurrentFile.text = msg
                indexBinding.tvLiveCount.text = "$count"
                showStatus(msg)

                indexBinding.tvIndexPhase.text = when (phase) {
                    IndexingWorker.PHASE_WAITING_MODEL -> "Waiting for AI model"
                    IndexingWorker.PHASE_SCANNING -> "Scanning folders on device"
                    IndexingWorker.PHASE_INDEXING -> when {
                        count < 50  -> "Documents → Downloads → Media"
                        count < 200 -> "Indexing documents & code…"
                        else        -> "Indexing remaining files…"
                    }
                    else -> "Indexing in progress"
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                isIndexing = false
                indexBinding.btnIndex.isEnabled = true
                indexBinding.btnIndex.text = getString(R.string.btn_reindex)
                indexBinding.llRunningState.visibility = View.GONE
                indexBinding.llIdleState.visibility = View.VISIBLE
                if (isEngineReady) {
                    indexer.loadFromDatabase()
                    indexBinding.tvIndexCount.text = "${indexer.size}"
                    indexBinding.tvIdleStatus.text =
                        "${indexer.size} files indexed · delta mode active"
                    showBucketPills()
                    showStatus("✓ ${indexer.size} files indexed")
                }
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                isIndexing = false
                indexBinding.btnIndex.isEnabled = true
                indexBinding.llRunningState.visibility = View.GONE
                indexBinding.llIdleState.visibility = View.VISIBLE
                status?.let {
                    indexBinding.tvIdleStatus.text = it
                    showStatus(it)
                }
            }
            else -> { /* unused */ }
        }
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
        if (buckets.isEmpty()) return

        indexBinding.llBuckets.removeAllViews()
        val colors = mapOf(
            Category.WORK      to R.color.cat_work,
            Category.IDENTITY  to R.color.cat_identity,
            Category.EDUCATION to R.color.cat_education,
            Category.PERSONAL  to R.color.cat_personal,
            Category.MEDIA     to R.color.cat_media,
            Category.GENERAL   to R.color.cat_general,
        )

        buckets.forEach { (cat, count) ->
            val pill = android.widget.TextView(this).apply {
                text = "${cat.label}: $count"
                textSize = 10f
                setTextColor(getColor(colors[cat] ?: R.color.cat_general))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(getColor(colors[cat] ?: R.color.cat_general).let {
                        android.graphics.Color.argb(25,
                            android.graphics.Color.red(it),
                            android.graphics.Color.green(it),
                            android.graphics.Color.blue(it))
                    })
                    cornerRadius = 40f
                }
                background = bg
                setPadding(20, 8, 20, 8)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 8
                layoutParams = lp
            }
            indexBinding.llBuckets.addView(pill)
        }
        indexBinding.hsvBuckets.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────

    private fun isSemanticEnabled(): Boolean =
        prefs.getBoolean(PREF_SEMANTIC_RERANK, true)

    private fun performSearch() {
        performSearchWithFilter(null, semanticEnabled = isSemanticEnabled())
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
        semanticEnabled: Boolean = isSemanticEnabled()
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

        val queryGeneration = ++searchGeneration
        dismissSharePicker()
        showProgress(true)
        searchJob?.cancel()
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enriched = enrichQuery(rawQuery, UserProfile.getNameTokens(this@MainActivity))
                val useSemantic = semanticEnabled || enriched.actionIntent != null
                var results  = indexer.search(enriched, topK = 20, semanticEnabled = useSemantic)

                if (!categoryFilter.isNullOrEmpty()) {
                    results = results.filter { r ->
                        r.file.categories.any { it in categoryFilter }
                    }
                }

                val indexedCount = indexer.size

                withContext(Dispatchers.Main) {
                    if (queryGeneration != searchGeneration) return@withContext
                    resultsAdapter.submitList(results)

                    if (enriched.actionIntent != null) {
                        if (results.isNotEmpty()) {
                            showSharePicker(results, enriched.shareTarget)
                            showStatus(getString(R.string.share_picker_tap_hint))
                        } else {
                            showStatus(statusForSearchResults(results, rawQuery, indexedCount, enriched.categoryHints))
                            if (!isSearchWarmingUp(indexedCount) && indexedCount > 0) {
                                showToast(getString(R.string.share_no_file))
                            }
                        }
                    } else {
                        showStatus(statusForSearchResults(results, rawQuery, indexedCount, enriched.categoryHints))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (queryGeneration == searchGeneration) {
                        showStatus("Search error: ${e.message}")
                        resultsAdapter.submitList(emptyList())
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (queryGeneration == searchGeneration) showProgress(false)
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // Share picker
    // ─────────────────────────────────────────────

    private fun showSharePicker(results: List<SearchResult>, shareTarget: String?) {
        pendingShareResults = results
        pendingShareIndex = 0
        pendingShareTarget = shareTarget
        homeBinding.llSharePicker.visibility = View.VISIBLE
        resultsAdapter.setShareMode(true)
        updateSharePickerUi()
    }

    private fun updateSharePickerUi() {
        val results = pendingShareResults ?: return
        if (results.isEmpty()) {
            dismissSharePicker()
            return
        }
        val file = results[pendingShareIndex].file
        homeBinding.tvSharePickerFile.text = file.name
        homeBinding.tvSharePickerPosition.text =
            getString(R.string.share_picker_position, pendingShareIndex + 1, results.size)
        homeBinding.btnShareNext.alpha = if (pendingShareIndex < results.size - 1) 1f else 0.4f
        homeBinding.btnShareNext.isEnabled = pendingShareIndex < results.size - 1
        resultsAdapter.setShareSelection(file.path)
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(homeBinding.etSearch.windowToken, 0)
        statusBeforeListening = homeBinding.tvStatus.text?.toString()
        voiceSearchManager.startListening()
    }

    private fun resetListeningUi() {
        homeBinding.btnMic.alpha = 1f
        statusBeforeListening?.let { showStatus(it) }
        statusBeforeListening = null
    }

    private val voiceSearchCallbacks = object : VoiceSearchManager.Callbacks {
        override fun onListeningStarted() {
            homeBinding.btnMic.alpha = 0.6f
            showStatus(getString(R.string.listening))
        }

        override fun onListeningEnded() {
            homeBinding.btnMic.alpha = 1f
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
            performSearch()
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
        if (::homeBinding.isInitialized) homeBinding.tvStatus.text = msg
    }

    private fun showProgress(show: Boolean) {
        if (::homeBinding.isInitialized)
            homeBinding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showToast(msg: String) {
        binding.tvToast.text = msg
        binding.llToast.visibility = View.VISIBLE
        binding.llToast.animate().alpha(1f).setDuration(200).start()
        Handler(Looper.getMainLooper()).postDelayed({
            binding.llToast.animate().alpha(0f).setDuration(200).withEndAction {
                binding.llToast.visibility = View.GONE
            }.start()
        }, 2200)
    }

    private fun clearIndex() {
        if (!isEngineReady) {
            showToast("Model still loading…")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            db.deleteFiles(db.getStoredFileMeta().keys.toList())
            db.ftsClear()
            indexer.clear()
            withContext(Dispatchers.Main) {
                indexBinding.tvIndexCount.text = "0"
                indexBinding.hsvBuckets.visibility = View.GONE
                indexBinding.tvIdleStatus.text = "Tap to scan and index all files"
                indexBinding.btnIndex.text = getString(R.string.btn_index)
                resultsAdapter.submitList(emptyList())
                showToast("Index cleared")
                showStatus(getString(R.string.status_ready))
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

    override fun onDestroy() {
        super.onDestroy()
        taglineHandler.removeCallbacksAndMessages(null)
        if (::voiceSearchManager.isInitialized) voiceSearchManager.destroy()
        // Engine, indexer, and DB live in StriderApp — do not close here
    }

    companion object {
        /** First-index UX: show warming-up status until this many files are in SQLite. Search is never blocked. */
        private const val MIN_FILES_SEARCH_UNLOCK = 10

        private const val PREFS_NAME = "strider_quanto_prefs"
        private const val PREF_VOICE_SEARCH_ENABLED = "voice_search_enabled"
        private const val PREF_SEMANTIC_RERANK = "semantic_rerank_enabled"
        private const val PREF_AUTO_REINDEX = "auto_reindex_enabled"
        private const val REQUEST_READ_STORAGE   = 100
        private const val REQUEST_MANAGE_STORAGE = 101
        private const val REQUEST_RECORD_AUDIO   = 102
    }
}