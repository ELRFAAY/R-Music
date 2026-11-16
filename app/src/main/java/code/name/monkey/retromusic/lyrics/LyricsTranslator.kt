package code.name.monkey.retromusic.lyrics

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Translates lyrics on the phone with ML Kit. A language's model is downloaded once, then it works offline. */
object LyricsTranslator {

    private suspend fun <T> Task<T>.await(): T = suspendCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    /** The language tag ML Kit finds in [sample], or null when it cannot tell. */
    suspend fun detect(sample: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            client.identifyLanguage(sample.take(600)).await().takeIf { it != "und" }
        } finally {
            client.close()
        }
    }

    /** Translates every line. Returns null when the source and the target are the same language. */
    suspend fun translate(lines: List<String>, targetTag: String): List<String>? {
        val sampleTag = detect(lines.joinToString("\n")) ?: "en"
        val source = TranslateLanguage.fromLanguageTag(sampleTag) ?: TranslateLanguage.ENGLISH
        val target = TranslateLanguage.fromLanguageTag(targetTag) ?: return null
        if (source == target) return null
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            lines.map { line -> if (line.isBlank()) line else translator.translate(line).await() }
        } finally {
            translator.close()
        }
    }
}
