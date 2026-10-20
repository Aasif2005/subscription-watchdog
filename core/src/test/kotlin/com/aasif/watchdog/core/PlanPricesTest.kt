package com.aasif.watchdog.core

import com.aasif.watchdog.core.merchant.PlanPrices
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanPricesTest {
    private fun inr(v: String) = Money(BigDecimal(v))

    @Test
    fun `a unique exact price suggests its plan`() {
        val p = PlanPrices.suggest("spotify", inr("119.00"))
        assertEquals(Frequency.MONTHLY, p?.frequency)
        assertEquals("Premium Individual", p?.label)
        assertEquals(Frequency.YEARLY, PlanPrices.suggest("spotify", inr("1189"))?.frequency)
    }

    @Test
    fun `a promotional price matches nothing, so the user is asked plainly`() {
        assertNull(PlanPrices.suggest("spotify", inr("69")))
    }

    @Test
    fun `two plans at one price are not guessed between`() {
        assertNull(PlanPrices.suggest("hotstar", inr("149")))
    }

    @Test
    fun `unknown services, odd paise and other currencies get no suggestion`() {
        assertNull(PlanPrices.suggest("claude", inr("1999")))
        assertNull(PlanPrices.suggest("spotify", inr("119.50")))
        assertNull(PlanPrices.suggest("spotify", Money(BigDecimal("119"), "USD")))
    }
}
