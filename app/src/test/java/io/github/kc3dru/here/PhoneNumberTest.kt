package io.github.kc3dru.here

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local unit tests for [PhoneNumber.normalize] — US-oriented E.164 rules.
 */
class PhoneNumberTest {

    @Test
    fun bareTenDigitNumberGetsUsCountryCode() {
        assertEquals("+12125550100", PhoneNumber.normalize("2125550100"))
    }

    @Test
    fun usFormattedWithParenthesesAndDashes() {
        assertEquals("+12125550100", PhoneNumber.normalize("(212) 555-0100"))
    }

    @Test
    fun usFormattedWithDashes() {
        assertEquals("+12125550100", PhoneNumber.normalize("212-555-0100"))
    }

    @Test
    fun alreadyInternationalIsUnchanged() {
        assertEquals("+12125550100", PhoneNumber.normalize("+12125550100"))
    }

    @Test
    fun elevenDigitsStartingWithOneGetPlusPrefix() {
        assertEquals("+12125550100", PhoneNumber.normalize("12125550100"))
    }

    @Test
    fun sevenDigitLocalNumberIsRejected() {
        assertNull(PhoneNumber.normalize("5550100"))
    }

    @Test
    fun emptyStringIsRejected() {
        assertNull(PhoneNumber.normalize(""))
    }

    @Test
    fun lettersOnlyIsRejected() {
        assertNull(PhoneNumber.normalize("abc"))
    }

    @Test
    fun internationalNumberKeepsItsCountryCode() {
        assertEquals("+442079460958", PhoneNumber.normalize("+44 20 7946 0958"))
    }

    // Extra guards for the same rules

    @Test
    fun spacedAndPaddedInputIsTrimmed() {
        assertEquals("+12125550100", PhoneNumber.normalize("  +1 (212) 555-0100  "))
    }

    @Test
    fun whitespaceOnlyIsRejected() {
        assertNull(PhoneNumber.normalize("   "))
    }

    @Test
    fun tooLongNumberIsRejected() {
        assertNull(PhoneNumber.normalize("+12125550100123456"))
    }
}
