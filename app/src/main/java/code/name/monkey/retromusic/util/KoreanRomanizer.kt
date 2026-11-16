package code.name.monkey.retromusic.util

/**
 * Hangul to Latin letters (Revised Romanization of Korean, simplified): handles the sound change
 * of a final consonant before a vowel and the common nasal change, not every rule. Characters that
 * are not Hangul stay as they are.
 */
object KoreanRomanizer {
    private val initials = listOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    )
    private val vowels = listOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we",
        "wi", "yu", "eu", "ui", "i"
    )

    // Final consonant (jongseong) sound when nothing follows or a consonant follows.
    private val finals = listOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "p", "l", "l", "m", "p", "p",
        "t", "t", "ng", "t", "t", "k", "t", "p", "t"
    )

    // Sound of the same final when a syllable starting with a vowel follows (it moves over).
    private val finalsBeforeVowel = listOf(
        "", "g", "kk", "ks", "n", "nj", "n", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "r", "m", "b",
        "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "h"
    )

    private fun isHangul(c: Char) = c in '\uAC00'..'\uD7A3'

    fun containsHangul(text: String): Boolean = text.any { isHangul(it) }

    fun romanize(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isHangul(c)) {
                out.append(c)
                i++
                continue
            }
            val code = c.code - 0xAC00
            val initial = code / (21 * 28)
            val vowel = (code % (21 * 28)) / 28
            val final = code % 28
            val next = text.getOrNull(i + 1)?.takeIf { isHangul(it) }
            val nextInitial = next?.let { (it.code - 0xAC00) / (21 * 28) }
            out.append(initials[initial]).append(vowels[vowel])
            when {
                final == 0 -> {}
                nextInitial == 11 && final != 21 -> out.append(finalsBeforeVowel[final])
                nextInitial == 2 || nextInitial == 6 -> out.append(
                    when (finals[final]) { "k" -> "ng"; "t" -> "n"; "p" -> "m"; else -> finals[final] }
                )
                else -> out.append(finals[final])
            }
            i++
        }
        return out.toString()
    }

    // Latin (Revised Romanization) pieces to Arabic letters, longest first. Approximate by nature:
    // Arabic has no p / eo / eu, so the nearest letters are used.
    private val arabicPieces = listOf(
        "yeo" to "يو", "yae" to "يي", "wae" to "وي", "kk" to "ك", "tt" to "ت", "pp" to "ب",
        "ss" to "س", "jj" to "ج", "ch" to "تش", "ng" to "نغ", "sh" to "ش", "eo" to "و", "eu" to "ِ",
        "ae" to "ي", "ya" to "يا", "ye" to "يي", "yo" to "يو", "yu" to "يو", "wa" to "وا",
        "wo" to "وو", "we" to "وي", "wi" to "وي", "oe" to "وي", "ui" to "وي",
        "a" to "ا", "e" to "ي", "i" to "ي", "o" to "و", "u" to "و",
        "g" to "ك", "k" to "ك", "n" to "ن", "d" to "د", "t" to "ت", "r" to "ر", "l" to "ل",
        "m" to "م", "b" to "ب", "p" to "ب", "s" to "س", "j" to "ج", "h" to "ه"
    )

    /** Arabic letters for already romanized Korean text; anything that is not a letter stays. */
    fun romanToArabic(roman: String): String {
        val low = roman.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < low.length) {
            val hit = arabicPieces.firstOrNull { low.startsWith(it.first, i) }
            if (hit != null) {
                out.append(hit.second)
                i += hit.first.length
            } else {
                out.append(low[i])
                i++
            }
        }
        return out.toString()
    }

    /** Pronunciation of one line: mode "en" = Latin letters, "ar" = Arabic letters. Null when the line has no Hangul. */
    fun pronounce(text: String, mode: String): String? {
        if (mode == "off" || !containsHangul(text)) return null
        val roman = romanize(text)
        return if (mode == "ar") romanToArabic(roman) else roman
    }
}
