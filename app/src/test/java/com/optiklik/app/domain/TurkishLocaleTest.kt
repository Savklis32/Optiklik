package com.optiklik.app.domain

import com.optiklik.app.data.repository.OptiklikRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TurkishLocaleTest {

    private val trLocale = Locale("tr", "TR")

    @Test
    fun turkishCaseFoldingHandlesDottedAndDotlessI() {
        assertEquals("ibrahim", "İBRAHİM".lowercase(trLocale))
        assertEquals("İBRAHİM", "ibrahim".uppercase(trLocale))
        assertEquals("şenol", "ŞENOL".lowercase(trLocale))
        assertEquals("ŞENOL", "şenol".uppercase(trLocale))
        assertEquals("I", "ı".uppercase(trLocale))
        assertEquals("ı", "I".lowercase(trLocale))
    }

    @Test
    fun normalizeClassBranchPreservesTurkishBranchLetters() {
        assertEquals("9-Ş", OptiklikRepository.normalizeClassBranch("9 ş"))
        assertEquals("10-İ", OptiklikRepository.normalizeClassBranch("10/i"))
        assertEquals("11-Ğ", OptiklikRepository.normalizeClassBranch("11ğ"))
        assertEquals("9-I", OptiklikRepository.normalizeClassBranch("9ı"))
        assertTrue(OptiklikRepository.normalizeClassBranch("9a") == "9-A")
    }
}
