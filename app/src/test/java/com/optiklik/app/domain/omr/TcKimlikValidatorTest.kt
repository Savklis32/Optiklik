package com.optiklik.app.domain.omr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcKimlikValidatorTest {

    @Test
    fun validTcNumbersPass() {
        assertTrue(TcKimlikValidator.isValid("10000000146"))
        assertTrue(TcKimlikValidator.isValid("10000000214"))
    }

    @Test
    fun invalidTcNumbersFail() {
        assertFalse(TcKimlikValidator.isValid("00000000000"))
        assertFalse(TcKimlikValidator.isValid("10000000140"))
        assertFalse(TcKimlikValidator.isValid("10000000147"))
        // 10000000238: oddSum=3, evenSum=0 -> 10th digit must be 1, not 3
        assertFalse(TcKimlikValidator.isValid("10000000238"))
        assertFalse(TcKimlikValidator.isValid("1234567890"))
        assertFalse(TcKimlikValidator.isValid("1234567890a"))
    }
}
