// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import java.text.Normalizer
import java.util.ArrayList

/** Which Vietnamese typing convention a [VietnameseCombiner] implements. */
enum class VietnameseMethod { VNI, TELEX }

/** Where the tone mark goes in the ambiguous open syllables `oa`, `oe`, `uy`. */
enum class VietnameseToneStyle { TRADITIONAL, MODERN }

/**
 * Vietnamese input combiner, supporting both VNI and Telex.
 *
 * VNI uses digits after the vowel/syllable:
 *   1 sắc   2 huyền   3 hỏi   4 ngã   5 nặng
 *   6 â/ê/ô   7 ơ/ư   8 ă   9 đ   0 remove diacritics
 *
 * Telex uses letters:
 *   s sắc   f huyền   r hỏi   x ngã   j nặng
 *   w → ơ/ư/ă   z remove diacritics   doubled vowel → â/ê/ô   dd → đ
 *
 * Raw keystrokes are buffered and the display form is recomputed on every keystroke
 * (like [BnKhiproCombiner]), so the tone mark is re-placed as the syllable grows and
 * backspace is predictable. Repeating a modifier reverts it and inserts it literally,
 * which is also the escape hatch for typing raw digits/letters.
 */
class VietnameseCombiner(
    private val method: VietnameseMethod,
    private val toneStyle: VietnameseToneStyle
) : Combiner {

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
        get() = VietnameseConverter.convert(rawInput.toString(), method, toneStyle)

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
 * Pure conversion from a raw Vietnamese keystroke sequence to the display string.
 * Stateless and free of Android dependencies so it can be unit tested on the JVM.
 */
object VietnameseConverter {

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

    // 'qu' and 'gi' onsets: the u/i is a glide, not part of the vowel nucleus
    private const val GLIDE_PREFIXES = "qu,gi"

    private enum class Shape { NONE, CIRCUMFLEX, BREVE, HORN, STROKE }
    private enum class Tone { NONE, ACUTE, GRAVE, HOOK, TILDE, DOT }
    private enum class Apply { CONSUMED, ESCAPED, NOT_APPLICABLE }

    private class Unit(
        var base: Char = ' ',
        var shape: Shape = Shape.NONE,
        var tone: Tone = Tone.NONE,
        var upper: Boolean = false,
        var isLetter: Boolean = false,
        var literal: String = ""
    ) {
        fun render(): String {
            if (!isLetter) return literal
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

    fun convert(raw: String, method: VietnameseMethod, toneStyle: VietnameseToneStyle): String {
        val units = ArrayList<Unit>(raw.length)
        // requested tone per letter-run (keyed by the index where the run starts)
        val runTones = HashMap<Int, Tone>()

        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charCount = Character.charCount(cp)
            val c: Char? = if (cp < 0x10000) cp.toChar() else null

            val result = if (c == null) Apply.NOT_APPLICABLE
            else when (method) {
                VietnameseMethod.VNI -> applyVni(units, runTones, c)
                VietnameseMethod.TELEX -> applyTelex(units, runTones, c)
            }

            when (result) {
                Apply.CONSUMED -> {}
                Apply.ESCAPED -> units.add(Unit(isLetter = false, literal = String(Character.toChars(cp))))
                Apply.NOT_APPLICABLE -> {
                    if (c != null && (c in 'a'..'z' || c in 'A'..'Z')) {
                        units.add(Unit(base = c.lowercaseChar(), upper = c.isUpperCase(), isLetter = true))
                    } else {
                        units.add(Unit(isLetter = false, literal = String(Character.toChars(cp))))
                    }
                }
            }
            i += charCount
        }

        // Place deferred tones now that the syllable structure is known
        for ((start, tone) in runTones) {
            val end = runEnd(units, start)
            if (end <= start) continue
            val run = units.subList(start, end)
            val index = toneTargetIndex(run, toneStyle)
            if (index >= 0) run[index].tone = tone
        }

        val sb = StringBuilder(units.size)
        for (unit in units) sb.append(unit.render())
        return sb.toString()
    }

    // ---- VNI ----

    private fun applyVni(units: ArrayList<Unit>, runTones: HashMap<Int, Tone>, c: Char): Apply {
        if (c !in '0'..'9') return Apply.NOT_APPLICABLE
        val start = currentRunStart(units)
        if (start == units.size) return Apply.NOT_APPLICABLE // no letters to modify

        return when (c) {
            in '1'..'5' -> {
                val tone = vniTone(c)
                if (runTones[start] == tone) {
                    runTones.remove(start) // repeat -> revert and type the digit literally
                    Apply.NOT_APPLICABLE
                } else {
                    runTones[start] = tone
                    Apply.CONSUMED
                }
            }
            '6', '7', '8', '9' -> {
                val shape = when (c) {
                    '6' -> Shape.CIRCUMFLEX
                    '7' -> Shape.HORN
                    '8' -> Shape.BREVE
                    else -> Shape.STROKE
                }
                val validBases = when (c) {
                    '6' -> "aeo"
                    '7' -> "ou"
                    '8' -> "a"
                    else -> "d"
                }
                val last = units.last()
                if (last.base !in validBases) return Apply.NOT_APPLICABLE
                if (last.shape == shape) {
                    last.shape = Shape.NONE // repeat -> revert and type the digit literally
                    Apply.NOT_APPLICABLE
                } else {
                    last.shape = shape
                    Apply.CONSUMED
                }
            }
            '0' -> removeDiacritics(units, runTones, start)
            else -> Apply.NOT_APPLICABLE
        }
    }

    private fun vniTone(c: Char): Tone = when (c) {
        '1' -> Tone.ACUTE
        '2' -> Tone.GRAVE
        '3' -> Tone.HOOK
        '4' -> Tone.TILDE
        else -> Tone.DOT
    }

    // ---- Telex ----

    private fun applyTelex(units: ArrayList<Unit>, runTones: HashMap<Int, Tone>, c: Char): Apply {
        val lc = c.lowercaseChar()
        val last = units.lastOrNull()

        // doubled vowel -> circumflex (aa ee oo)
        if (last != null && last.isLetter && lc in "aeo" && last.base == lc) {
            when (last.shape) {
                Shape.NONE -> {
                    last.shape = Shape.CIRCUMFLEX
                    return Apply.CONSUMED
                }
                Shape.CIRCUMFLEX -> {
                    last.shape = Shape.NONE // third letter -> revert to the doubled form
                    return Apply.NOT_APPLICABLE
                }
                else -> {} // fall through, append as a normal letter
            }
        }

        // dd -> đ
        if (last != null && last.isLetter && lc == 'd' && last.base == 'd') {
            return when (last.shape) {
                Shape.NONE -> {
                    last.shape = Shape.STROKE
                    Apply.CONSUMED
                }
                Shape.STROKE -> {
                    last.shape = Shape.NONE
                    Apply.NOT_APPLICABLE
                }
                else -> Apply.NOT_APPLICABLE
            }
        }

        // w -> horn (o/u) or breve (a)
        if (lc == 'w' && last != null && last.isLetter) {
            val target = when (last.base) {
                'a' -> Shape.BREVE
                'o', 'u' -> Shape.HORN
                else -> null
            }
            if (target != null) {
                return if (last.shape == target) {
                    last.shape = Shape.NONE // repeat -> revert and type 'w' literally
                    Apply.NOT_APPLICABLE
                } else {
                    last.shape = target
                    Apply.CONSUMED
                }
            }
        }

        // z -> remove diacritics
        if (lc == 'z') {
            val start = currentRunStart(units)
            if (start == units.size) return Apply.NOT_APPLICABLE
            return removeDiacritics(units, runTones, start)
        }

        // s f r x j -> tones (only when the current syllable already has a vowel)
        if (lc in "sfrxj") {
            val start = currentRunStart(units)
            if (start == units.size) return Apply.NOT_APPLICABLE // onset consonant, not a tone
            val run = units.subList(start, units.size)
            if (run.none { it.base in VOWELS }) return Apply.NOT_APPLICABLE
            val tone = when (lc) {
                's' -> Tone.ACUTE
                'f' -> Tone.GRAVE
                'r' -> Tone.HOOK
                'x' -> Tone.TILDE
                else -> Tone.DOT
            }
            return if (runTones[start] == tone) {
                runTones.remove(start) // repeat -> revert and type the letter literally
                Apply.NOT_APPLICABLE
            } else {
                runTones[start] = tone
                Apply.CONSUMED
            }
        }

        return Apply.NOT_APPLICABLE
    }

    private fun removeDiacritics(units: ArrayList<Unit>, runTones: HashMap<Int, Tone>, start: Int): Apply {
        if (runTones.remove(start) != null) return Apply.CONSUMED
        val run = units.subList(start, units.size)
        val shaped = run.lastOrNull { it.shape != Shape.NONE }
        if (shaped != null) {
            shaped.shape = Shape.NONE
            return Apply.CONSUMED
        }
        return Apply.NOT_APPLICABLE
    }

    // ---- tone placement ----

    private fun toneTargetIndex(run: List<Unit>, style: VietnameseToneStyle): Int {
        val vowels = run.indices.filter { run[it].isLetter && run[it].base in VOWELS }
        if (vowels.isEmpty()) return -1

        // exclude the glide vowel of a leading 'qu' or 'gi'
        var nucleus = vowels
        if (run.size >= 3 && vowels.first() == 1) {
            val prefix = "${run[0].base}${run[1].base}"
            if (prefix in GLIDE_PREFIXES.split(",")) nucleus = vowels.drop(1)
        }
        if (nucleus.isEmpty()) nucleus = vowels

        val shaped = nucleus.filter { run[it].shape != Shape.NONE }
        if (shaped.isNotEmpty()) return shaped.last()

        val lastVowel = nucleus.last()
        val hasCoda = (lastVowel + 1 until run.size).any { run[it].isLetter && run[it].base !in VOWELS }
        if (hasCoda) return lastVowel

        if (nucleus.size >= 2) {
            if (style == VietnameseToneStyle.MODERN) {
                val pair = "${run[nucleus[nucleus.size - 2]].base}${run[nucleus.last()].base}"
                if (pair == "oa" || pair == "oe" || pair == "uy") return nucleus.last()
            }
            return nucleus[nucleus.size - 2]
        }
        return nucleus[0]
    }

    private fun currentRunStart(units: List<Unit>): Int {
        var start = units.size
        while (start > 0 && units[start - 1].isLetter) start--
        return start
    }

    private fun runEnd(units: List<Unit>, start: Int): Int {
        var end = start
        while (end < units.size && units[end].isLetter) end++
        return end
    }
}
