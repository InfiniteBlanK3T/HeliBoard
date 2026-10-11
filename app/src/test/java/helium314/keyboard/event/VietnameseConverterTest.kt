// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.event

import kotlin.test.Test
import kotlin.test.assertEquals

class VietnameseConverterTest {

    private fun vni(raw: String, style: VietnameseToneStyle = VietnameseToneStyle.TRADITIONAL) =
        VietnameseConverter.convert(raw, VietnameseMethod.VNI, style)

    private fun telex(raw: String, style: VietnameseToneStyle = VietnameseToneStyle.TRADITIONAL) =
        VietnameseConverter.convert(raw, VietnameseMethod.TELEX, style)

    private fun assertVni(raw: String, expected: String) =
        assertEquals(expected, vni(raw), "VNI convert(\"$raw\")")

    private fun assertTelex(raw: String, expected: String) =
        assertEquals(expected, telex(raw), "Telex convert(\"$raw\")")

    // ---------- VNI ----------

    @Test fun vniToneMarks() {
        assertVni("a1", "á"); assertVni("a2", "à"); assertVni("a3", "ả")
        assertVni("a4", "ã"); assertVni("a5", "ạ")
        assertVni("e1", "é"); assertVni("i1", "í"); assertVni("o1", "ó")
        assertVni("u1", "ú"); assertVni("y1", "ý")
    }

    @Test fun vniShapeMarks() {
        assertVni("a6", "â"); assertVni("e6", "ê"); assertVni("o6", "ô")
        assertVni("o7", "ơ"); assertVni("u7", "ư")
        assertVni("a8", "ă"); assertVni("d9", "đ")
    }

    @Test fun vniCombinedShapeAndTone() {
        assertVni("a61", "ấ"); assertVni("a62", "ầ"); assertVni("a65", "ậ")
        assertVni("a81", "ắ"); assertVni("e61", "ế"); assertVni("o61", "ố")
        assertVni("o71", "ớ"); assertVni("u71", "ứ")
    }

    @Test fun vniUppercaseIsPreserved() {
        assertVni("A1", "Á"); assertVni("A6", "Â"); assertVni("O7", "Ơ"); assertVni("D9", "Đ")
        assertVni("Tie6ng1", "Tiếng")
    }

    @Test fun vniWholeWords() {
        assertVni("tie6ng1", "tiếng")
        assertVni("d9u7o7ng2", "đường")
        assertVni("ngu7o7i2", "người")
        assertVni("chao2", "chào")
    }

    @Test fun vniTonePlacementOpenSyllables() {
        assertVni("hoa1", "hóa")
        assertVni("thuy3", "thủy")
        assertVni("ai1", "ái")
    }

    @Test fun vniTonePlacementWithCoda() {
        assertVni("hoang1", "hoáng")
        assertVni("toan1", "toán")
    }

    @Test fun vniQuAndGiOnsetGlides() {
        assertVni("qua1", "quá")
        assertVni("que6", "quê")
        assertVni("quy1", "quý")
        assertVni("gia1", "giá")
        assertVni("giao1", "giáo")
        assertVni("gi2", "gì")
        assertVni("gio2", "giò")
    }

    @Test fun vniToneIsReplacedAsSyllableGrows() {
        // tone typed before the coda must move to the correct vowel
        assertVni("hoa2ng", "hoàng")
        assertVni("hoa1ng", "hoáng")
    }

    @Test fun vniRepeatedModifierInsertsLiteral() {
        assertVni("a11", "a1")
        assertVni("a66", "a6")
        assertVni("o77", "o7")
        assertVni("d99", "d9")
    }

    @Test fun vniZeroRemovesDiacritics() {
        assertVni("a10", "a")
        assertVni("a60", "a")
        assertVni("o70", "o")
        assertVni("d90", "d")
    }

    @Test fun vniDigitsThatCannotApplyStayLiteral() {
        assertVni("1", "1")
        assertVni("a9", "a9")
        assertVni("12", "12")
        assertVni("hello", "hello")
    }

    @Test fun vniModernToneStyle() {
        assertEquals("hoá", vni("hoa1", VietnameseToneStyle.MODERN))
        assertEquals("thuỷ", vni("thuy3", VietnameseToneStyle.MODERN))
        assertEquals("khoẻ", vni("khoe3", VietnameseToneStyle.MODERN))
        // closed syllables are unaffected
        assertEquals("hoáng", vni("hoang1", VietnameseToneStyle.MODERN))
    }

    // ---------- Telex ----------

    @Test fun telexToneMarks() {
        assertTelex("as", "á"); assertTelex("af", "à"); assertTelex("ar", "ả")
        assertTelex("ax", "ã"); assertTelex("aj", "ạ")
    }

    @Test fun telexShapeMarks() {
        assertTelex("aa", "â"); assertTelex("ee", "ê"); assertTelex("oo", "ô")
        assertTelex("ow", "ơ"); assertTelex("uw", "ư"); assertTelex("aw", "ă")
        assertTelex("dd", "đ")
    }

    @Test fun telexWholeWords() {
        assertTelex("tieengs", "tiếng")
        assertTelex("dduwowngf", "đường")
        assertTelex("nguwowif", "người")
    }

    @Test fun telexOnsetConsonantsStayLiteral() {
        assertTelex("so", "so")
        assertTelex("sa", "sa")
        assertTelex("xa", "xa")
        assertTelex("test", "tét") // s after a vowel is a tone in Telex
    }

    @Test fun telexQuAndGiOnsetGlides() {
        assertTelex("quas", "quá")
        assertTelex("gias", "giá")
    }

    @Test fun telexRepeatedModifierInsertsLiteral() {
        assertTelex("ass", "as")
        assertTelex("ddd", "dd")
    }

    @Test fun telexModernToneStyle() {
        assertEquals("hoá", telex("hoas", VietnameseToneStyle.MODERN))
        assertEquals("hóa", telex("hoas", VietnameseToneStyle.TRADITIONAL))
    }
}
