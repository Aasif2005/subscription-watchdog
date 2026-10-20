package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.worthKeepingFromSms
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.SmsParser
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmsRelevanceTest {
    private val parser = SmsParser()
    private val at = LocalDateTime.of(2026, 9, 5, 10, 0)

    private fun parse(body: String) = parser.parse(Sms("VM-HDFCBK-S", body, at))

    @Test
    fun `a plain debit to an uncatalogued payee is not kept`() {
        val e = parse("Rs.450.00 debited from A/c XX1234 on 05-09-2026 to PVR INOX. Avl Bal Rs 10,000.00 -HDFC Bank")
        assertNotNull(e)
        assertFalse(e.worthKeepingFromSms())
    }

    @Test
    fun `a plain debit to a known subscription service is kept`() {
        val e = parse("Rs.649.00 debited from A/c XX1234 on 05-09-2026 to NETFLIX. Avl Bal Rs 10,000.00 -HDFC Bank")
        assertNotNull(e)
        assertTrue(e.worthKeepingFromSms())
    }

    @Test
    fun `a debit that states its AutoPay is kept whoever the payee is`() {
        val e = parse("Rs.299.00 debited from A/c XX1234 on 05-09-2026 to SOMESHOP via UPI AutoPay mandate. Avl Bal Rs 10,000.00")
        assertNotNull(e)
        assertTrue(e.worthKeepingFromSms())
    }

    @Test
    fun `mandate revocations and notices are always kept`() {
        val e = parse("UPI Mandate for Rs.199 towards Spotify has been revoked successfully.")
        assertNotNull(e)
        assertEquals(true, e.worthKeepingFromSms())
    }
}
