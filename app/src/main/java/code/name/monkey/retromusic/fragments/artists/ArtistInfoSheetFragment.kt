package code.name.monkey.retromusic.fragments.artists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentArtistInfoSheetBinding
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.lyrics.LyricsTranslator
import code.name.monkey.retromusic.network.Result
import code.name.monkey.retromusic.repository.Repository
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.util.Locale

/**
 * Short biography of an artist (from Last.fm). Kept on the phone after the first load so it opens
 * offline next time, and it can be translated on the phone (ML Kit, offline after one download).
 */
class ArtistInfoSheetFragment : BottomSheetDialogFragment() {

    private val repository by inject<Repository>()
    private var _binding: FragmentArtistInfoSheetBinding? = null
    private val binding get() = _binding!!
    private var original: String? = null
    private var translated = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArtistInfoSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val name = requireArguments().getString(ARG_NAME).orEmpty()
        binding.artistInfoSheetRoot.minimumHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        binding.artistInfoTitle.text = name
        binding.artistInfoToAr.setOnClickListener { translateTo("ar") }
        binding.artistInfoToEn.setOnClickListener { translateTo("en") }
        binding.artistInfoOriginal.setOnClickListener { original?.let { show(it, false) } }
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { cachedFile(name).takeIf { it.exists() }?.readText() }
                ?: fetch(name)
            if (_binding == null) return@launch
            if (text.isNullOrBlank()) {
                binding.artistInfoText.setText(R.string.artist_info_none)
            } else {
                original = text
                show(text, false)
            }
        }
    }

    private fun cachedFile(name: String): File {
        val dir = File(requireContext().filesDir, "artist_info").also { it.mkdirs() }
        return File(dir, name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80) + ".txt")
    }

    private suspend fun fetch(name: String): String? {
        binding.artistInfoText.setText(R.string.artist_info_loading)
        for (lang in listOf(Locale.getDefault().language, null)) {
            val result = repository.artistInfo(name, lang, null)
            val html = (result as? Result.Success)?.data?.artist?.bio?.content
            val text = html?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
            if (!text.isNullOrBlank()) {
                withContext(Dispatchers.IO) { cachedFile(name).writeText(text) }
                return text
            }
        }
        return null
    }

    private fun show(text: String, isTranslation: Boolean) {
        translated = isTranslation
        binding.artistInfoText.text = text
        binding.artistInfoOriginal.isVisible = isTranslation
    }

    private fun translateTo(tag: String) {
        val text = original ?: return
        // Cut into pieces of whole sentences so every piece is a size the translator handles well.
        val pieces = ArrayList<String>()
        var current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (current.length + sentence.length > 400 && current.isNotEmpty()) {
                pieces += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        context?.showToast(R.string.lyrics_translating)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    kotlin.Result.success(LyricsTranslator.translate(pieces, tag))
                } catch (e: Exception) {
                    kotlin.Result.failure(e)
                }
            }
            if (_binding == null) return@launch
            result.onSuccess { out ->
                if (out == null) context?.showToast(R.string.lyrics_translate_same)
                else show(out.joinToString("\n\n"), true)
            }.onFailure { context?.showToast(R.string.lyrics_translate_failed) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ARTIST_INFO_SHEET"
        private const val ARG_NAME = "artist_name"

        fun newInstance(name: String) = ArtistInfoSheetFragment().apply {
            arguments = bundleOf(ARG_NAME to name)
        }
    }
}
