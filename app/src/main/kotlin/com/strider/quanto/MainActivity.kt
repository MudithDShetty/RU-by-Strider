package com.strider.quanto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.strider.quanto.databinding.ActivityMainBinding
import com.strider.quanto.databinding.FragmentHomeBinding
import com.strider.quanto.databinding.FragmentIndexBinding
import com.strider.quanto.databinding.FragmentSettingsBinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { HOME, INDEX, SETTINGS }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: EmbeddingEngine
    private lateinit var indexer: FileIndexer
    private lateinit var db: DatabaseHelper
    private lateinit var resultsAdapter: ResultsAdapter

    private var isEngineReady = false
    private var isIndexing = false
    private var currentScreen = Screen.HOME

    // View bindings for each screen (inflated once, swapped in/out)
    private lateinit var homeBinding: FragmentHomeBinding
    private lateinit var indexBinding: FragmentIndexBinding
    private lateinit var settingsBinding: FragmentSettingsBinding

    private val taglineHandler = Handler(Looper.getMainLooper())
    private var taglineToggle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PDFBoxResourceLoader.init(applicationContext)

        inflateScreens()
        setupNavBar()
        showScreen(Screen.HOME)
        setupHomeScreen()
        setupIndexScreen()
        setupSettingsScreen()
        initEngine()
        startTaglineCycle()
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
        resultsAdapter = ResultsAdapter { result -> openFile(result.file) }
        homeBinding.rvResults.apply {
            adapter = resultsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            isNestedScrollingEnabled = false
        }

        homeBinding.btnIndexShortcut.setOnClickListener { showScreen(Screen.INDEX) }

        homeBinding.etSearch.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { performSearch(); true } else false
            }
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
            showToast("Voice search coming soon")
        }

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

        // Configure each row
        settingsBinding.rowSemantic.apply {
            tvRowTitle.text = getString(R.string.settings_semantic)
            tvRowSub.text   = getString(R.string.settings_semantic_sub)
            ivRowIcon.setImageResource(R.drawable.ic_search)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = true
        }
        settingsBinding.rowVoice.apply {
            tvRowTitle.text = getString(R.string.settings_voice)
            tvRowSub.text   = getString(R.string.settings_voice_sub)
            ivRowIcon.setImageResource(R.drawable.ic_mic)
            switchRow.visibility = View.VISIBLE
            switchRow.isChecked  = true
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
            switchRow.isChecked  = false
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
    // Engine init
    // ─────────────────────────────────────────────

    private fun initEngine() {
        showStatus("Loading model…")
        showProgress(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db      = DatabaseHelper(this@MainActivity)
                engine  = EmbeddingEngine(this@MainActivity)
                engine.initialize()
                indexer = FileIndexer(engine, db)

                val existingCount = db.getTotalCount()
                if (existingCount > 0) indexer.loadFromDatabase()

                withContext(Dispatchers.Main) {
                    isEngineReady = true
                    showProgress(false)

                    if (existingCount > 0) {
                        showStatus("✓ $existingCount files ready")
                        indexBinding.tvIndexCount.text = "$existingCount"
                        showBucketPills()
                    } else {
                        showStatus(getString(R.string.status_ready))
                    }
                    homeBinding.btnMic.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    showStatus("Error: ${e.message}")
                }
            }
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
        isIndexing = true
        indexBinding.btnIndex.isEnabled = false
        indexBinding.llIdleState.visibility    = View.GONE
        indexBinding.llRunningState.visibility = View.VISIBLE
        resultsAdapter.submitList(emptyList())

        val rootPath = Environment.getExternalStorageDirectory().absolutePath

        lifecycleScope.launch {
            indexer.indexDirectory(
                rootPath = rootPath,
                onProgress = { msg ->
                    withContext(Dispatchers.Main) {
                        indexBinding.tvCurrentFile.text = msg
                        showStatus(msg)
                    }
                },
                onFileIndexed = { indexed, skipped, deleted ->
                    withContext(Dispatchers.Main) {
                        indexBinding.tvLiveCount.text = "$indexed"
                        val phase = when {
                            indexed < 50  -> "Documents → Code → Media"
                            indexed < 200 -> "Indexing code files…"
                            else          -> "Indexing media…"
                        }
                        indexBinding.tvIndexPhase.text = phase
                    }
                }
            )

            withContext(Dispatchers.Main) {
                isIndexing = false
                indexBinding.btnIndex.isEnabled    = true
                indexBinding.btnIndex.text         = getString(R.string.btn_reindex)
                indexBinding.llRunningState.visibility = View.GONE
                indexBinding.llIdleState.visibility    = View.VISIBLE
                indexBinding.tvIndexCount.text         = "${indexer.size}"
                indexBinding.tvIdleStatus.text         = "${indexer.size} files indexed · delta mode active"
                showBucketPills()
                showStatus("✓ ${indexer.size} files indexed")
            }
        }
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

    private fun performSearch() {
        performSearchWithFilter(null)
    }

    private fun performSearchWithFilter(categoryFilter: List<Category>?) {
        val rawQuery = homeBinding.etSearch.text.toString().trim()
        if (rawQuery.isEmpty()) return
        if (!isEngineReady || indexer.size == 0) {
            showToast(getString(R.string.index_files_first))
            return
        }

        showProgress(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val enriched = enrichQuery(rawQuery)
            var results  = indexer.search(enriched, topK = 20)

            // Apply chip category filter if set
            if (!categoryFilter.isNullOrEmpty()) {
                results = results.filter { r ->
                    r.file.categories.any { it in categoryFilter }
                }
            }

            withContext(Dispatchers.Main) {
                showProgress(false)
                resultsAdapter.submitList(results)
                val catInfo = if (enriched.categoryHints.isNotEmpty())
                    " [${enriched.categoryHints.joinToString { it.label }}]" else ""
                showStatus(
                    if (results.isEmpty()) getString(R.string.no_results, rawQuery)
                    else getString(R.string.results_count, results.size, rawQuery) + catInfo
                )
            }
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
        lifecycleScope.launch(Dispatchers.IO) {
            db.deleteFiles(db.getStoredFileMeta().keys.toList())
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
        if (requestCode == REQUEST_READ_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startIndexing()
        else showToast(getString(R.string.permission_denied))
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
        if (::engine.isInitialized) engine.close()
        if (::db.isInitialized) db.close()
    }

    companion object {
        private const val REQUEST_READ_STORAGE   = 100
        private const val REQUEST_MANAGE_STORAGE = 101
    }
}