package io.github.vladislavekimtcov.anamorphic

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.vladislavekimtcov.anamorphic.core.AnamorphicProcessor
import io.github.vladislavekimtcov.anamorphic.core.JpegFile
import io.github.vladislavekimtcov.anamorphic.core.Mode
import io.github.vladislavekimtcov.anamorphic.core.OutputNames
import io.github.vladislavekimtcov.anamorphic.core.ProcessOptions
import io.github.vladislavekimtcov.anamorphic.core.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Idle : State
        data class Working(val current: Int, val total: Int) : State
        data class Finished(val report: Report) : State
    }

    data class Report(
        val mode: Mode,
        val saved: Int,
        val alreadyStretched: Int,
        val notJpeg: Int,
        val failures: List<String>,
        /** Originals to move to the trash once the user confirms. */
        val originals: List<Uri>,
        /** Originals we had to keep (not on this device, or location could not be read). */
        val keptOriginals: Int,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Shared photos, kept here so they survive rotation while a permission dialog is up. */
    var uris: List<Uri> = emptyList()

    /** Set once the trash confirmation has been shown, so it isn't shown twice. */
    var trashRequested = false

    /** The answer to the trash confirmation, once it came back. */
    var trashed: Boolean? = null

    fun start(mode: Mode, settings: Settings) {
        if (_state.value != State.Idle) return
        val options = ProcessOptions(mode, settings.factor, settings.rotated)
        val replace = settings.replaceOriginal
        val uris = uris
        _state.value = State.Working(1, uris.size)
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) { processAll(uris, options, replace) }
            _state.value = State.Finished(report)
        }
    }

    private fun processAll(uris: List<Uri>, options: ProcessOptions, replace: Boolean): Report {
        val app = getApplication<Application>()
        val repository = MediaStoreRepository(app)
        val processor = AnamorphicProcessor(BitmapJpegCodec())
        var saved = 0
        var alreadyStretched = 0
        var notJpeg = 0
        var kept = 0
        val failures = ArrayList<String>()
        val originals = ArrayList<Uri>()

        uris.forEachIndexed { index, uri ->
            _state.value = State.Working(index + 1, uris.size)
            var label = uri.lastPathSegment ?: uri.toString()
            try {
                val source = repository.describe(uri)
                source.displayName?.let { label = it }
                val content = repository.read(source)
                if (!JpegFile.isJpeg(content.bytes)) {
                    notJpeg++
                    return@forEachIndexed
                }
                when (val result = processor.process(content.bytes, options)) {
                    ProcessResult.AlreadyStretched -> alreadyStretched++
                    is ProcessResult.Success -> {
                        result.warnings.forEach { Log.w(TAG, "$label: $it") }
                        repository.insert(result.jpeg, OutputNames.forMode(source.displayName, options.mode), source)
                        saved++
                        if (replace) {
                            val original = source.mediaUri
                            if (original != null && content.complete) originals += original else kept++
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory on $label", e)
                failures += app.getString(R.string.error_out_of_memory, label)
            } catch (e: Exception) {
                Log.e(TAG, "Failed on $label", e)
                failures += app.getString(R.string.error_failed, label, e.message ?: e.javaClass.simpleName)
            }
        }
        return Report(options.mode, saved, alreadyStretched, notJpeg, failures, originals, kept)
    }

    private companion object {
        const val TAG = "Anamorphic"
    }
}
