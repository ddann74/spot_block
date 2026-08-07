package com.spotblock.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.spotblock.app.databinding.ActivityMainBinding
import com.spotblock.app.diagnostics.DiagnosticLog
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var diagnosticLog: DiagnosticLog

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            refreshHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        diagnosticLog = DiagnosticLog(this, settingsRepository)

        setupListeners()
        binding.adSkipEnabledSwitch.isChecked = settingsRepository.isAdSkipEnabled
        binding.overlayEnabledSwitch.isChecked = settingsRepository.isOverlayEnabled
        binding.diagnosticLoggingSwitch.isChecked = settingsRepository.isDiagnosticLoggingEnabled
        renderAllLists()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun setupListeners() {
        binding.openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.adSkipEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isAdSkipEnabled = isChecked
        }
        binding.overlayEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isOverlayEnabled = isChecked
        }
        binding.diagnosticLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isDiagnosticLoggingEnabled = isChecked
        }

        binding.addAdKeywordButton.setOnClickListener {
            val keyword = binding.adKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addAdKeyword(keyword)
            binding.adKeywordInput.setText("")
            renderAdKeywords()
        }
        binding.addSkipControlKeywordButton.setOnClickListener {
            val keyword = binding.skipControlKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addSkipControlKeyword(keyword)
            binding.skipControlKeywordInput.setText("")
            renderSkipControlKeywords()
        }
        binding.addDownloadControlKeywordButton.setOnClickListener {
            val keyword = binding.downloadControlKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addDownloadControlKeyword(keyword)
            binding.downloadControlKeywordInput.setText("")
            renderDownloadControlKeywords()
        }
        binding.addTargetPackageButton.setOnClickListener {
            val pkg = binding.targetPackageInput.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            settingsRepository.addTargetPackage(pkg)
            binding.targetPackageInput.setText("")
            renderTargetPackages()
        }

        binding.clearStatsButton.setOnClickListener {
            statsRepository.clear()
            refreshStats()
        }
        binding.shareDiagnosticLogButton.setOnClickListener { shareDiagnosticLog() }
        binding.clearDiagnosticLogButton.setOnClickListener {
            diagnosticLog.clear()
            refreshStats()
            Toast.makeText(this, "Diagnostic log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStats() {
        binding.adsDetectedText.text = "Ads detected: ${statsRepository.adsDetected}"
        binding.skipTappedText.text = "Skip tapped: ${statsRepository.skipTapped}"
        binding.skipBlockedText.text = "Skip blocked (disabled by Spotify): ${statsRepository.skipBlocked}"
        binding.skipControlNotFoundText.text = "Skip control not found: ${statsRepository.skipControlNotFound}"
        binding.downloadTappedText.text = "Download tapped: ${statsRepository.downloadTapped}"
        binding.downloadControlNotFoundText.text = "Download control not found: ${statsRepository.downloadControlNotFound}"
        val log = statsRepository.recentLog()
        binding.activityLogText.text = if (log.isEmpty()) "No activity yet" else log.joinToString("\n")

        val kilobytes = diagnosticLog.sizeBytes / 1024.0
        binding.diagnosticLogSizeText.text = "Diagnostic log: %.1f KB".format(kilobytes)
    }

    private fun refreshAccessibilityStatus() {
        binding.accessibilityStatusText.text = if (isAccessibilityServiceGranted()) {
            "Accessibility Service: Granted"
        } else {
            "Accessibility Service: NOT granted - tap the button above"
        }
    }

    private fun isAccessibilityServiceGranted(): Boolean {
        val expected = "$packageName/${SpotifyAdSkipService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun renderAllLists() {
        renderAdKeywords()
        renderSkipControlKeywords()
        renderDownloadControlKeywords()
        renderTargetPackages()
    }

    private fun renderAdKeywords() {
        renderList(binding.adKeywordsContainer, settingsRepository.adKeywords) { keyword ->
            settingsRepository.removeAdKeyword(keyword)
            renderAdKeywords()
        }
    }

    private fun renderSkipControlKeywords() {
        renderList(binding.skipControlKeywordsContainer, settingsRepository.skipControlKeywords) { keyword ->
            settingsRepository.removeSkipControlKeyword(keyword)
            renderSkipControlKeywords()
        }
    }

    private fun renderDownloadControlKeywords() {
        renderList(binding.downloadControlKeywordsContainer, settingsRepository.downloadControlKeywords) { keyword ->
            settingsRepository.removeDownloadControlKeyword(keyword)
            renderDownloadControlKeywords()
        }
    }

    private fun renderTargetPackages() {
        renderList(binding.targetPackagesContainer, settingsRepository.targetPackages) { pkg ->
            settingsRepository.removeTargetPackage(pkg)
            renderTargetPackages()
        }
    }

    /** Rebuilds [container] from scratch with one list_item_row per entry in [items] -
      * simple over efficient, but these lists are always small (a handful of
      * keywords/packages), so a RecyclerView would be more machinery than the job
      * calls for. */
    private fun renderList(container: LinearLayout, items: List<String>, onRemove: (String) -> Unit) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.list_item_row, container, false)
            row.findViewById<TextView>(R.id.rowText).text = item
            row.findViewById<Button>(R.id.rowRemoveButton).setOnClickListener { onRemove(item) }
            container.addView(row)
        }
    }

    private fun shareDiagnosticLog() {
        val file = File(diagnosticLog.filePath)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Diagnostic log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Diagnostic Log"))
    }

    companion object {
        private const val STATS_REFRESH_INTERVAL_MILLIS = 2000L
    }
}
