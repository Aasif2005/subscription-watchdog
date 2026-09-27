package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.SmsParser
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** A small realistic inbox, run through parser and engine together. */
class EndToEndTest {
    private fun sms(date: String, sender: String, body: String) =
        Sms(sender, body, LocalDate.parse(date).atTime(10, 0))

    private val inbox = listOf(
        // Netflix: card debits only, no mandate SMS kept.
        sms("2026-07-05", "BP-AXISBK", "Spent INR 649.00 Axis Bank Card no. XX4321 05-07-26 10:12:44 IST NETFLIX.COM Avl Limit: INR 45,000.00"),
        sms("2026-08-05", "BP-AXISBK", "Spent INR 649.00 Axis Bank Card no. XX4321 05-08-26 10:12:44 IST NETFLIX.COM Avl Limit: INR 44,351.00"),
        sms("2026-09-05", "BP-AXISBK", "Spent INR 649.00 Axis Bank Card no. XX4321 05-09-26 10:12:44 IST NETFLIX.COM Avl Limit: INR 43,702.00"),
        // Spotify: mandate + debit + tomorrow's pre-debit notice.
        sms("2026-08-12", "AX-SBIINB", "AutoPay mandate of Rs 199 has been registered for SPOTIFY INDIA with frequency MONTHLY. First debit on 12-Aug-2026. -SBI"),
        sms("2026-08-12", "VM-HDFCBK", "Sent Rs.199.00\nFrom HDFC Bank A/C *1234\nTo Spotify\nOn 12/08/26\nRef 623512345678"),
        sms("2026-09-26", "JD-ICICIT", "Rs.199.00 will be debited from your A/c XX1234 on 27-09-2026 towards Spotify AutoPay mandate. -ICICI Bank"),
        // YouTube: free trial converting next month.
        sms("2026-09-20", "BP-AXISBK", "UPI mandate created successfully. Rs 1.00 debited for verification towards YouTube. Recurring payment of Rs 149 will start from 20-10-2026. Frequency: Monthly"),
        // Hotstar: cancelled.
        sms("2026-06-01", "VM-HDFCBK", "Your UPI-Mandate for Rs.299.00 is successfully created towards JioHotstar. Frequency: Monthly, Start date: 01/06/26"),
        sms("2026-09-01", "VM-HDFCBK", "Your UPI AutoPay mandate for JioHotstar has been revoked successfully."),
        // Noise.
        sms("2026-09-10", "VM-HDFCBK", "123456 is your OTP for txn of Rs 2,340 at AMAZON. Do not share."),
        sms("2026-09-11", "VM-HDFCBK", "Rs 5000 credited to your A/c XX1234 by NEFT. -HDFC"),
        sms("2026-09-12", "AX-SBIINB", "Rs.85 debited from A/c XX1234 on 12-09-26 to VPA chaiwala@okaxis. -SBI"),
    )

    @Test
    fun `inbox becomes a subscription list`() {
        val parser = SmsParser()
        val subs = SubscriptionEngine().build(inbox.mapNotNull(parser::parse), today = LocalDate.parse("2026-09-26"))
        val byId = subs.associateBy { it.merchantId }

        assertEquals(setOf("netflix", "spotify", "youtube", "hotstar"), byId.keys)

        assertEquals(LocalDate.parse("2026-09-27"), byId.getValue("spotify").nextRenewal)
        assertEquals(RenewalSource.PRE_DEBIT_NOTICE, byId.getValue("spotify").renewalSource)

        assertEquals(LocalDate.parse("2026-10-05"), byId.getValue("netflix").nextRenewal)

        assertEquals(SubscriptionStatus.TRIAL, byId.getValue("youtube").status)
        assertEquals(LocalDate.parse("2026-10-20"), byId.getValue("youtube").nextRenewal)

        assertEquals(SubscriptionStatus.CANCELLED, byId.getValue("hotstar").status)
    }
}
