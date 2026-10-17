package com.example.myapplication.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.download.ModelDownloadManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Onboarding screen that explains and allows downloading on-device AI models (~234 MB).
 * Users can trigger the download right here and either wait or proceed to finish onboarding.
 */
class OnboardingModelDownloadFragment : Fragment() {

    companion object {
        fun newInstance(): OnboardingModelDownloadFragment {
            return OnboardingModelDownloadFragment()
        }
    }

    private lateinit var cardPreview: View
    private lateinit var onboardTitle: TextView
    private lateinit var onboardBody: TextView

    private lateinit var layoutDownloadAction: View
    private lateinit var btnStartDownload: MaterialButton
    private lateinit var tvDownloadNote: TextView

    private lateinit var layoutDownloadProgress: View
    private lateinit var downloadProgressBar: LinearProgressIndicator
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvProgressSize: TextView
    private lateinit var btnCancelDownload: TextView

    private lateinit var layoutDownloadReady: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_model_download, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardPreview = view.findViewById(R.id.cardPreview)
        onboardTitle = view.findViewById(R.id.onboardTitle)
        onboardBody = view.findViewById(R.id.onboardBody)

        layoutDownloadAction = view.findViewById(R.id.layoutDownloadAction)
        btnStartDownload = view.findViewById(R.id.btnStartDownload)
        tvDownloadNote = view.findViewById(R.id.tvDownloadNote)

        layoutDownloadProgress = view.findViewById(R.id.layoutDownloadProgress)
        downloadProgressBar = view.findViewById(R.id.downloadProgressBar)
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent)
        tvProgressSize = view.findViewById(R.id.tvProgressSize)
        btnCancelDownload = view.findViewById(R.id.btnCancelDownload)

        layoutDownloadReady = view.findViewById(R.id.layoutDownloadReady)

        // Subtle entrance animation matching other onboarding screens
        listOf(
            cardPreview,
            onboardTitle,
            onboardBody,
            layoutDownloadAction
        ).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 60).toLong())
                .setDuration(300)
                .start()
        }

        btnStartDownload.setOnClickListener {
            showDownloadConfirmation()
        }

        btnCancelDownload.setOnClickListener {
            ModelDownloadManager.cancelDownload()
            Toast.makeText(requireContext(), "Download cancelled", Toast.LENGTH_SHORT).show()
        }

        // Collect download progress state reactively
        viewLifecycleOwner.lifecycleScope.launch {
            ModelDownloadManager.downloadStatus.collect { status ->
                updateUiState(status)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState(ModelDownloadManager.downloadStatus.value)
    }

    private fun showDownloadConfirmation() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle("Download On-Device AI Models")
            .setMessage(
                "Download the offline scam detection package (~234 MB) to inspect incoming SMS and chats locally on your phone.\n\n" +
                "• Package Size: ~234 MB\n" +
                "• Completely offline & private\n\n" +
                "⚠️ Wi-Fi connection is strongly recommended to save mobile data."
            )
            .setPositiveButton("Download") { _, _ ->
                startDownload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload() {
        val appContext = requireContext().applicationContext
        // Launch download in Activity scope so it persists across page swipes
        requireActivity().lifecycleScope.launch {
            ModelDownloadManager.downloadMissingModels(appContext).collect { /* StateFlow in ModelDownloadManager handles updates */ }
        }
    }

    private fun updateUiState(status: ModelDownloadManager.DownloadProgress) {
        val context = context ?: return
        val isReady = ModelDownloadManager.areModelsReady(context)

        if (isReady || status is ModelDownloadManager.DownloadProgress.Completed) {
            // Models are ready
            layoutDownloadAction.visibility = View.GONE
            layoutDownloadProgress.visibility = View.GONE
            layoutDownloadReady.visibility = View.VISIBLE
            return
        }

        when (status) {
            is ModelDownloadManager.DownloadProgress.Downloading -> {
                layoutDownloadAction.visibility = View.GONE
                layoutDownloadReady.visibility = View.GONE
                layoutDownloadProgress.visibility = View.VISIBLE

                downloadProgressBar.progress = status.fileProgressPercent
                tvProgressPercent.text = "Downloading (${status.currentFileIndex}/${status.totalFiles}) • ${status.fileProgressPercent}%"
                tvProgressSize.text = String.format(
                    Locale.US,
                    "%.1f / %.1f MB",
                    status.totalDownloadedMB,
                    status.totalSizeMB
                )
            }

            is ModelDownloadManager.DownloadProgress.Failed -> {
                layoutDownloadProgress.visibility = View.GONE
                layoutDownloadReady.visibility = View.GONE
                layoutDownloadAction.visibility = View.VISIBLE

                btnStartDownload.text = "Retry Download (~234 MB)"
            }

            else -> {
                // Idle / Not started
                layoutDownloadProgress.visibility = View.GONE
                layoutDownloadReady.visibility = View.GONE
                layoutDownloadAction.visibility = View.VISIBLE

                btnStartDownload.text = "Download Models (~234 MB)"
            }
        }
    }
}
