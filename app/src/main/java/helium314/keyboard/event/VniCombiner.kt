// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import java.text.Normalizer
import java.util.ArrayList

/**
 * VNI input method combiner for Vietnamese.
 *
 * The user types plain Latin letters, and digits act as modifiers placed after the
 * letter/syllable they affect:
 *   1 sắc (acute)      2 huyền (grave)     3 hỏi (hook above)
 *   4 ngã (tilde)      5 nặng (dot below)  6 â/ê/ô (circumflex)
 *   7 ơ/ư (horn)       8 ă (breve)         9 đ (d-stroke)
 *   0 removes diacritics
 *
 * The raw keystrokes are buffered and the display form is recomputed on every keystroke
 * (like [BnKhiproCombiner]) so backspace and out-of-order modifiers behave predictably.
 * A digit that cannot be interpreted as a modifier is emitted literally, so numbers can
 * still be typed.
 */
class VniCombiner : Combiner {

    private val rawInput = StringBuilder()

    override fun processEvent(previousEvents: ArrayList<Event>?, event: Event): Event {
        // Shift does not affect the composition
        if (event.keyCode == KeyCode.SHIFT) return event

        // Backspace inside the composition removes the last raw keystroke
        if (event.keyCode == KeyCode.DELETE) {
            if (rawInput.isNotEmpty()) {
                val cp = rawInput.codePointBefore(rawInput.length)
                rawInput.delete(rawInput.length - Character.charCount(cp), rawInput.length)
                if (rawInput.isEmpty()) {
                    reset()
                    // exit composing cleanly, mirroring HangulCombiner / BnKhiproCombiner
                    return Event.createHardwareKeypressEvent(0x20, Constants.CODE_SPACE, 0, event, event.isKeyRepeat)
                }
                return Event.createConsumedEvent(event)
            }
            // no composing state -> let backspace propagate to the editor
            return event
        }

        val codePoint = event.codePoint
        val isValidCodePoint = codePoint != Integer.MAX_VALUE && Character.isValidCodePoint(codePoint)
        val isWhitespace = isValidCodePoint && Character.isWhitespace(codePoint)

        if (event.isFunctionalKeyEvent || isWhitespace) {
            return commitAndReset(event)
        }
        if (!isValidCodePoint) return Event.createConsumedEvent(event)

        rawInput.appendCodePoint(codePoint)
        return Event.createConsumedEvent(event)
    }

    override val combiningStateFeedback: CharSequence
        get() = VniConverter.convert(rawInput.toString())

    override fun reset() {
        rawInput.setLength(0)
    }

    private fun commitAndReset(event: Event): Event {
        val converted = combiningStateFeedback
        reset()
        return Event.createSoftwareTextEvent(converted, KeyCode.MULTIPLE_CODE_POINTS, event)
    }
}

/**
 * Pure conversion from a raw VNI keystroke sequence to the Vietnamese display string.
 * Kept stateless and free of Android dependencies so it can be unit tested on the JVM.
 */
object VniConverter {

    private const val COMBINING_CIRCUMFLEX = '\u0302'
    private const val COMBINING_BREVE = '\u0306'
    private const val COMBINING_HORN = '\u031B'
    private const val COMBINING_ACUTE = '\u0301'
    private const val COMBINING_GRAVE = '\u0300'
    private const val COMBINING_HOOK_ABOVE = '\u0309'
    private const val COMBINING_TILDE = '\u0303'
    private const val COMBINING_DOT_BELOW = '\u0323'

    private const val LETTER_D_STROKE = '\u0111' // đ

    private const val VOWELS = "aeiouy"

    fun convert(raw: String): String {
        val units = ArrayList<Unit>(raw.length)
        for (c in raw) {
            when {
                c in '0'..'9' -> if (!applyModifier(units, c)) {
                    units.add(Unit(c, Shape.NONE, Tone.NONE, upper = false, isLetter = false))
                }
                c in 'a'..'z' || c in 'A'..'Z' ->
                    units.add(Unit(c.lowercaseChar(), Shape.NONE, Tone.NONE, upper = c.isUpperCase(), isLetter = true))
                else -> units.add(Unit(c, Shape.NONE, Tone.NONE, upper = false, isLetter = false))
            }
        }
        val sb = StringBuilder(units.size)
        for (u in units) sb.append(u.render())
        return sb.toString()
    }

    /** @return true if [digit] was consumed as a modifier, false if it should be literal. */
    private fun applyModifier(units: ArrayList<Unit>, digit: Char): Boolean {
        // modifiers only act on the trailing run of letters (the current syllable)
        var start = units.size
        while (start > 0 && units[start - 1].isLetter) start--
        if (start == units.size) return false
        val syllable = units.subList(start, units.size)

        return when (digit) {
            '1' -> applyTone(syllable, Tone.ACUTE)
            '2' -> applyTone(syllable, Tone.GRAVE)
            '3' -> applyTone(syllable, Tone.HOOK)
            '4' -> applyTone(syllable, Tone.TILDE)
            '5' -> applyTone(syllable, Tone.DOT)
            '6' -> applyShape(syllable, Shape.CIRCUMFLEX, "aeo")
            '7' -> applyShape(syllable, Shape.HORN, "ou")
            '8' -> applyShape(syllable, Shape.BREVE, "a")
            '9' -> applyShape(syllable, Shape.STROKE, "d")
            '0' -> removeDiacritics(syllable)
            else -> false
        }
    }

    private fun applyShape(syllable: List<Unit>, shape: Shape, validBases: String): Boolean {
        val target = syllable.last()
        if (target.base !in validBases) return false
        target.shape = if (target.shape == shape) Shape.NONE else shape
        return true
    }

    private fun applyTone(syllable: List<Unit>, tone: Tone): Boolean {
        val vowelIndices = syllable.indices.filter { syllable[it].base in VOWELS }
        if (vowelIndices.isEmpty()) return false

        val shapedVowels = vowelIndices.filter { syllable[it].shape != Shape.NONE }
        val targetIndex = when {
            // a vowel with a shape diacritic (â ê ô ơ ư ă) always carries the tone
            shapedVowels.isNotEmpty() -> shapedVowels.last()
            // closed syllable: tone on the last vowel
            hasCoda(syllable, vowelIndices.last()) -> vowelIndices.last()
            // open syllable: tone on the second-to-last vowel of the cluster
            vowelIndices.size >= 2 -> vowelIndices[vowelIndices.size - 2]
            else -> vowelIndices[0]
        }
        val target = syllable[targetIndex]
        target.tone = if (target.tone == tone) Tone.NONE else tone
        return true
    }

    private fun hasCoda(syllable: List<Unit>, lastVowelIndex: Int): Boolean {
        for (i in lastVowelIndex + 1 until syllable.size) {
            if (syllable[i].isLetter && syllable[i].base !in VOWELS) return true
        }
        return false
    }

    private fun removeDiacritics(syllable: List<Unit>): Boolean {
        syllable.lastOrNull { it.tone != Tone.NONE }?.let {
            it.tone = Tone.NONE
            return true
        }
        syllable.lastOrNull { it.shape != Shape.NONE }?.let {
            it.shape = Shape.NONE
            return true
        }
        return false
    }

    private enum class Shape { NONE, CIRCUMFLEX, BREVE, HORN, STROKE }

    private enum class Tone { NONE, ACUTE, GRAVE, HOOK, TILDE, DOT }

    private class Unit(
        val base: Char,
        var shape: Shape,
        var tone: Tone,
        val upper: Boolean,
        val isLetter: Boolean
    ) {
        fun render(): String {
            if (!isLetter) return base.toString()
            if (base == 'd' && shape == Shape.STROKE) {
                return if (upper) LETTER_D_STROKE.uppercaseChar().toString() else LETTER_D_STROKE.toString()
            }
            val sb = StringBuilder(3)
            sb.append(base)
            when (shape) {
                Shape.CIRCUMFLEX -> sb.append(COMBINING_CIRCUMFLEX)
                Shape.BREVE -> sb.append(COMBINING_BREVE)
                Shape.HORN -> sb.append(COMBINING_HORN)
                Shape.NONE, Shape.STROKE -> {}
            }
            when (tone) {
                Tone.ACUTE -> sb.append(COMBINING_ACUTE)
                Tone.GRAVE -> sb.append(COMBINING_GRAVE)
                Tone.HOOK -> sb.append(COMBINING_HOOK_ABOVE)
                Tone.TILDE -> sb.append(COMBINING_TILDE)
                Tone.DOT -> sb.append(COMBINING_DOT_BELOW)
                Tone.NONE -> {}
            }
            val composed = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
            return if (upper) composed.map { it.uppercaseChar() }.joinToString("") else composed
        }
    }
}
