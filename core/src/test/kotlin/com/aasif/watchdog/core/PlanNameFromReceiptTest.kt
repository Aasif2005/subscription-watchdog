package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.merchant.MerchantCatalog
import com.aasif.watchdog.core.merchant.MerchantNormalizer
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.Extractors
import com.aasif.watchdog.core.parser.SmsParser
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A subscription is named after the platform its receipt names plus a real tier word ("ChatGPT Go",
 * "Claude Pro", "Spotify Premium"), never the developer's free-text plan title ("Individual Offer").
 * Google Play prints items as "<plan title> (<app name>)". The ChatGPT and Claude item lines are real
 * (order numbers and addresses replaced); the Spotify, LinkedIn and Apple Music ones keep the plan titles
 * seen on the phone but their bracket text is modelled on Play's usual app listing, so it is synthetic.
 */
class PlanNameFromReceiptTest {
    private val parser = SmsParser(fromEmail = true)
    private val engine = SubscriptionEngine()

    private fun receipt(item: String, price: String, order: String = "GPA.1111-2222-3333-55555") =
        "Thank you You've made a subscription purchase on Google Play. Order number: $order Order date: 18 Sept 2026 11:30:49 am IST " +
            "Your account: someone@example.com Item Price $item $price Auto-renewing subscription Total: $price " +
            "Payment method: UPI: GPay View the Google Play Refund Policy and the Terms of Service."

    private fun parse(item: String, price: String = "₹199.00/month"): MandateCreated {
        val subject = "Your Google Play Order Receipt from 18 Sept 2026"
        val sms = Sms("googleplay-noreply@google.com", "$subject ${receipt(item, price)}", LocalDateTime.of(2026, 9, 18, 11, 30, 51), "google.com", subject)
        return assertIs<MandateCreated>(parser.parse(sms))
    }

    @Test
    fun `the platform and a real tier word name the plan`() {
        assertEquals("Claude Pro", parse("Claude Pro Subscription (Claude by Anthropic)", "₹1,999.00/month").planName)
        assertEquals("ChatGPT Go", parse("ChatGPT Go (ChatGPT)", "₹399.00/month").planName)
        assertEquals("Spotify Premium", parse("Premium Standard (Spotify: Music and Podcasts)").planName)
        assertEquals("LinkedIn Premium", parse("Premium Career Monthly (LinkedIn: Jobs & Business News)").planName)
    }

    @Test
    fun `a plan title with no tier word gives just the platform, never the offer label`() {
        val e = parse("Individual Offer (Apple Music)")
        assertEquals("applemusic", e.merchantId)
        assertEquals("Apple Music", e.planName)
    }

    @Test
    fun `a bracket naming another service is ignored and the catalog name stands in`() {
        val body = "Item Price Premium Standard (Some Other App) ₹119.00/month Total: ₹119.00/month"
        assertEquals("Spotify Premium", Extractors.itemName(body, "Spotify") { false })
        assertEquals("LinkedIn Premium", Extractors.itemName("Item Price Premium (Some App) ₹1.00", "LinkedIn Premium") { false })
    }

    @Test
    fun `no item line gives no plan name and the engine falls back to the catalog name`() {
        val subject = "Your Google Play Order Receipt"
        val body = "$subject Thank you. Your subscription will automatically renew on 18 Oct 2026 unless cancelled. " +
            "Order number: GPA.1111-2222-3333-55555 Total: ₹1,999.00/month View the Google Play Refund Policy for Anthropic."
        val e = assertIs<MandateCreated>(parser.parse(Sms("googleplay-noreply@google.com", body, LocalDateTime.of(2026, 9, 18, 11, 30, 51), "google.com", subject)))
        assertNull(e.planName)
        assertEquals("Claude", engine.build(listOf(e), LocalDate.of(2026, 10, 1)).single().name)
    }

    @Test
    fun `the engine shows the receipt's name over the catalog's`() {
        val e = parse("Claude Pro Subscription (Claude by Anthropic)", "₹1,999.00/month")
        assertEquals("Claude Pro", engine.build(listOf(e), LocalDate.of(2026, 10, 1)).single().name)
    }

    /**
     * Brackets are the real Google Play listing titles of each app (read from the public Play pages); the plan
     * titles in front are made up, since no public source shows other services' receipts.
     */
    @Test
    fun `real Play app titles resolve to one clean platform name per service`() {
        val normalizer = MerchantNormalizer()
        val cases = listOf(
            Triple("hotstar", "Super (JioHotstar)", "JioHotstar Super"),
            Triple("prime", "Prime (Prime Video)", "Prime Video"),
            Triple("spotify", "Premium Standard (Spotify: Music and Podcasts)", "Spotify Premium"),
            Triple("spotify", "Premium Duo (Spotify: Music and Podcasts)", "Spotify Premium Duo"),
            Triple("youtube", "Premium Individual (YouTube)", "YouTube Premium"),
            Triple("youtube", "Individual (YouTube)", "YouTube Premium"),
            Triple("sonyliv", "Premium (Sony LIV: Sports & Entmt)", "SonyLIV Premium"),
            Triple("sonyliv", "Monthly (Sony LIV: Sports & Entmt)", "SonyLIV"),
            Triple("zee5", "Monthly (Zee 5)", "ZEE5"),
            Triple("applemusic", "Individual Offer (Apple Music)", "Apple Music"),
            Triple("linkedin", "Premium Career Monthly (LinkedIn: Community & Network)", "LinkedIn Premium"),
            Triple("linkedin", "Business Monthly (LinkedIn: Community & Network)", "LinkedIn Premium"),
            Triple("googleone", "100 GB (Google One)", "Google One"),
            Triple("chatgpt", "ChatGPT Go (ChatGPT)", "ChatGPT Go"),
            Triple("claude", "Claude Pro Subscription (Claude by Anthropic)", "Claude Pro"),
            Triple("duolingo", "Super (Duolingo: Learn Languages)", "Duolingo Super"),
        )
        for ((id, item, expected) in cases) {
            val catalogName = MerchantCatalog.all.first { it.id == id }.name
            val body = "Order number: GPA.1111-2222-3333-55555 Item Price $item ₹199.00/month Total: ₹199.00/month"
            assertEquals(expected, Extractors.itemName(body, catalogName) { normalizer.findKnownIn(it)?.id == id }, item)
        }
    }

    @Test
    fun `a direct service mail with no Play item table keeps the catalog name`() {
        // Netflix has taken no new Google Play billing since 2018; its own mail has no item table.
        assertNull(Extractors.itemName("Your Netflix membership. Amount: ₹199.00 per month", "Netflix") { true })
    }
}
