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
}
