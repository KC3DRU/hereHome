package io.github.kc3dru.here

/**
 * Normalises user-entered phone numbers to E.164 so the number handed to
 * SmsManager is unambiguous. US-oriented: a bare 10-digit number is assumed
 * to be +1.
 */
object PhoneNumber {
    /** @return the E.164 form (e.g. +12125550100), or null if it cannot be a phone number. */
    fun normalize(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        val alreadyInternational = raw.startsWith("+")
        val digits = raw.filter { it.isDigit() }

        return when {
            digits.isEmpty() -> null
            alreadyInternational ->
                if (digits.length in 8..15) "+$digits" else null
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> null          // 7-digit numbers are rejected: no area code
        }
    }
}
