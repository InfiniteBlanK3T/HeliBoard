// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.event

import kotlin.test.Test
import kotlin.test.assertEquals

class VniConverterTest {

    private fun assertConverts(raw: String, expected: String) =
        assertEquals(expected, VniConverter.convert(raw), "convert(\"$raw\")")

    @Test fun toneMarks() {
        assertConverts("a1", "á"); assertConverts("a2", "à"); assertConverts("a3", "ả")
        assertConverts("a4", "ã"); assertConverts("a5", "ạ")
        assertConverts("e1", "é"); assertConverts("i1", "í"); assertConverts("o1", "ó")
        assertConverts("u1", "ú"); assertConverts("y1", "ý")
    }

    @Test fun shapeMarks() {
        assertConverts("a6", "â"); assertConverts("e6", "ê"); assertConverts("o6", "ô")
        assertConverts("o7", "ơ"); assertConverts("u7", "ư")
        assertConverts("a8", "ă"); assertConverts("d9", "đ")
    }

    @Test fun combinedShapeAndTone() {
        assertConverts("a61", "ấ"); assertConverts("a62", "ầ"); assertConverts("a65", "ậ")
        assertConverts("a81", "ắ")
        assertConverts("e61", "ế")
        assertConverts("o61", "ố")
        assertConverts("o71", "ớ")
        assertConverts("u71", "ứ")
    }

    @Test fun uppercaseIsPreserved() {
        assertConverts("A1", "Á")
        assertConverts("A6", "Â")
        assertConverts("O7", "Ơ")
        assertConverts("D9", "Đ")
        assertConverts("Tie6ng1", "Tiếng")
    }

    @Test fun wholeWords() {
        assertConverts("tie6ng1", "tiếng")
        assertConverts("d9u7o7ng2", "đường")
        assertConverts("ngu7o7i2", "người")
        assertConverts("chao2", "chào")
        assertConverts("hoa1", "hóa")
    }

    @Test fun tonePlacementInOpenSyllables() {
        // open syllable: tone goes on the second-to-last vowel of the cluster
        assertConverts("hoa1", "hóa")
        assertConverts("thuy3", "thủy")
        assertConverts("ai1", "ái")
        assertConverts("trai2", "trài")
    }

    @Test fun tonePlacementWithCoda() {
        // closed syllable: tone goes on the last vowel
        assertConverts("hoang1", "hoáng")
        assertConverts("toan1", "toán")
        assertConverts("hoang2", "hoàng")
    }

    @Test fun shapeVowelAlwaysCarriesTone() {
        assertConverts("tie6ng1", "tiếng")
        assertConverts("d9u7o7ng2", "đường")
        assertConverts("muo6i1", "muối")
    }

    @Test fun digitsThatCannotApplyStayLiteral() {
        assertConverts("1", "1")
        assertConverts("a9", "a9")
        assertConverts("12", "12")
        assertConverts("hello", "hello")
    }

    @Test fun repeatedModifierToggles() {
        assertConverts("a11", "a")
        assertConverts("a66", "a")
        assertConverts("o77", "o")
    }

    @Test fun zeroRemovesDiacritics() {
        assertConverts("a10", "a")
        assertConverts("a60", "a")
        assertConverts("o70", "o")
        assertConverts("d90", "d")
    }
}
