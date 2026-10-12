package io.github.vladislavekimtcov.anamorphic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.vladislavekimtcov.anamorphic.ProcessViewModel.Report
import io.github.vladislavekimtcov.anamorphic.ProcessViewModel.State
import io.github.vladislavekimtcov.anamorphic.core.Mode
import io.github.vladislavekimtcov.anamorphic.databinding.ActivityProcessBinding
import kotlinx.coroutines.launch

/**
 * The share targets "Stretch" and "Unstretch" (two activity-aliases of this activity).
 * Shows progress, saves the processed copies and optionally moves the originals to the trash.
 */
class ProcessActivity : AppCompatActivity() {

    private val viewModel: ProcessViewModel by viewModels()
    private lateinit var binding: ActivityProcessBinding
    private lateinit var settings: Settings
    private lateinit var mode: Mode
    private var completed = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.start(mode, settings)
        }

    private val trashLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.trashed = result.resultCode == RESULT_OK
            val report = (viewModel.state.value as? State.Finished)?.report ?: return@registerForActivityResult
            complete(report, trashed = result.resultCode == RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        settings = Settings(this)
        mode = if (intent.component?.className?.endsWith(UNSTRETCH_ALIAS) == true) Mode.UNSTRETCH else Mode.STRETCH
        binding.title.setText(if (mode == Mode.STRETCH) R.string.process_title_stretch else R.string.process_title_unstretch)
        binding.close.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }

        if (savedInstanceState == null) {
            viewModel.uris = sharedUris(intent)
            when {
                viewModel.uris.isEmpty() -> {
                    Toast.makeText(this, R.string.error_nothing_shared, Toast.LENGTH_LONG).show()
                    finish()
                }
                settings.replaceOriginal && !MediaPermissions.allGranted(this) -> {
                    settings.askedForPermission = true
                    permissionLauncher.launch(MediaPermissions.all)
                }
                else -> viewModel.start(mode, settings)
            }
        }
    }

    private fun render(state: State) {
        when (state) {
            State.Idle -> Unit
            is State.Working -> {
                binding.progress.visibility = View.VISIBLE
                binding.message.text = getString(R.string.process_progress, state.current, state.total)
            }
            is State.Finished -> {
                val report = state.report
                val trashed = viewModel.trashed
                when {
                    report.originals.isEmpty() -> complete(report, trashed = false)
                    trashed != null -> complete(report, trashed)
                    !viewModel.trashRequested -> {
                        viewModel.trashRequested = true
                        val request = MediaStoreRepository(this).trashRequest(report.originals)
                        trashLauncher.launch(IntentSenderRequest.Builder(request).build())
                    }
                }
            }
        }
    }

    private fun complete(report: Report, trashed: Boolean) {
        if (completed) return
        completed = true
        val lines = ArrayList<String>()
        if (report.saved > 0) {
            val done = if (report.mode == Mode.STRETCH) R.plurals.done_stretched else R.plurals.done_unstretched
            lines += resources.getQuantityString(done, report.saved, report.saved)
        }
        if (report.originals.isNotEmpty()) {
            val result = if (trashed) R.plurals.originals_trashed else R.plurals.originals_kept_by_user
            lines += resources.getQuantityString(result, report.originals.size, report.originals.size)
        }
        if (report.keptOriginals > 0) {
            lines += resources.getQuantityString(R.plurals.originals_not_found, report.keptOriginals, report.keptOriginals)
        }
        if (report.alreadyStretched > 0) {
            lines += resources.getQuantityString(R.plurals.already_stretched, report.alreadyStretched, report.alreadyStretched)
        }
        if (report.notJpeg > 0) {
            lines += resources.getQuantityString(R.plurals.not_jpeg, report.notJpeg, report.notJpeg)
        }
        lines += report.failures

        val problems = report.failures.isNotEmpty() || report.alreadyStretched > 0 || report.notJpeg > 0 ||
            report.keptOriginals > 0 || report.saved == 0
        if (!problems) {
            Toast.makeText(this, lines.joinToString("\n"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        binding.progress.visibility = View.GONE
        binding.message.text = lines.joinToString("\n\n")
        binding.close.visibility = View.VISIBLE
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        return uris.toList()
    }

    private companion object {
        const val UNSTRETCH_ALIAS = ".UnstretchShareTarget"
    }
}
