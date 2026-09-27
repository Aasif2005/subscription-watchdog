package com.aasif.watchdog.core

import com.aasif.watchdog.core.parser.SmsClassifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromoFilterTest {

    @Test
    fun `marketing pushes from UPI apps are promotional`() {
        listOf(
            "Set up UPI AutoPay for Netflix and get ₹50 cashback on your first payment",
            "Never miss a bill! Enable AutoPay today. Limited time offer",
            "Get Rs 100 off when you set up a mandate for your Spotify subscription",
            "Scratch card unlocked! Pay your recurring bills with AutoPay",
            "Flat 20% off on JioHotstar yearly plan. Activate AutoPay now",
            "Refer a friend to set up AutoPay and win rewards",
        ).forEach { assertTrue(SmsClassifier.isPromotional(it), it) }
    }

    @Test
    fun `real payment alerts from UPI and bank apps are not promotional`() {
        listOf(
            "AutoPay reminder: ₹649 will be debited from your HDFC Bank account on 05 Oct for NETFLIX",
            "UPI AutoPay mandate created. ₹199 to Spotify India, Monthly, starting 12 Oct 2026",
            "Paid ₹149 to YouTube Premium via UPI AutoPay",
            "Your AutoPay for Amazon Prime has been revoked successfully",
            "Rs.199.00 will be debited from A/c XX1234 on 29-09-26 towards Spotify AutoPay mandate. UMN for reference: a1b2@icici",
            "Mandate executed: INR 299 debited for Windows 365 on 01-10-26",
        ).forEach { assertFalse(SmsClassifier.isPromotional(it), it) }
    }
}
