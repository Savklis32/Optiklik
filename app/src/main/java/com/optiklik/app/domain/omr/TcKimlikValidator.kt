package com.optiklik.app.domain.omr

/**
 * Validates 11-digit Turkish Republic Identification Number (T.C. Kimlik Numarası)
 * using the official 10th and 11th digit modulo checksum rules.
 */
object TcKimlikValidator {

    fun isValid(tc: String): Boolean {
        if (tc.length != 11) return false
        if (!tc.all { it.isDigit() }) return false
        if (tc[0] == '0') return false

        val digits = tc.map { it - '0' }

        val oddSum = digits[0] + digits[2] + digits[4] + digits[6] + digits[8]
        val evenSum = digits[1] + digits[3] + digits[5] + digits[7]

        val digit10 = ((oddSum * 7) - evenSum).mod(10)
        if (digits[9] != digit10) return false

        val sumFirst10 = digits.take(10).sum()
        val digit11 = sumFirst10.mod(10)
        return digits[10] == digit11
    }
}
