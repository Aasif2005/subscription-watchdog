package com.aasif.watchdog.core.merchant

/**
 * Merchants whose charges must never become a subscription from pattern-matching alone, however
 * regular the amounts and gaps happen to look.
 *
 * [com.aasif.watchdog.core.engine.RecurrenceDetector] can only see amount and timing, and three
 * movie outings that happen to land ~90 days apart at a similar ticket price fit a "quarterly
 * subscription" exactly as well as a real one would — that is how a PVR INOX booking habit became
 * an "active" subscription. These merchants sell one-off units (a ticket, a meal, a ride, a tank
 * of fuel), not a plan, so no amount of coincidental regularity should count; only an explicit
 * mandate/AutoPay signal on the debit itself ([com.aasif.watchdog.core.model.PaymentEvent.Debit.viaMandate])
 * is honoured, since that is real evidence rather than a coincidence.
 *
 * Matched the same way [MerchantCatalog] is: lowercased, non-alphanumeric characters stripped,
 * substring search. Only applies to merchants the catalog does not already know by name — a real
 * catalog entry (e.g. "Swiggy One", alias `swiggyone`) is judged on its own merits and never
 * reaches this list, even though its alias contains the plain-order word "swiggy".
 *
 * The telecom entries here are the common-case sibling of [KnownMerchant.autopayOnly]: a bank SMS
 * for a prepaid recharge almost always just says "AIRTEL" or "JIO" plainly, which is too short and
 * generic to fuzzy-match a catalog alias like `airtelxstream`, so it resolves as an *unknown*
 * merchant rather than the catalog's `airtel`/`jio` entry — `autopayOnly` alone would miss it. Both
 * are kept: `autopayOnly` catches the compound official descriptor when one is used, this catches
 * the bare carrier name, which is what most real recharge SMS actually say.
 */
object NonSubscriptionCatalog {
    private val aliases: List<String> = listOf(
        // Movie & event ticketing — every booking is a one-off, never a plan.
        "bookmyshow", "pvrinox", "pvrcinemas", "pvr", "inox", "cinepolis", "carnivalcinemas",
        "moviemax", "districtcinemas", "mirajcinemas", "asiancinemas",

        // Food delivery — the per-order charge, not a paid membership (Swiggy One / Zomato Gold
        // are separate catalog entries with their own distinct alias and are never reached here).
        "swiggy", "zomato", "zeptonow", "zepto", "blinkit", "dunzo", "eatsure", "dominos",
        "magicpin", "freshmenu",

        // Cabs & ride-hailing — a ride booked when needed, not a subscription.
        "uber", "olacabs", "olamoney", "ola", "rapido", "meru", "blusmart",

        // Generic shopping — an order, not a paid membership (Amazon Prime / Flipkart Plus /
        // Myntra Insider are separate catalog entries and never reach this list).
        "amazon", "flipkart", "myntra", "ajio", "nykaa", "tatacliq", "meesho", "snapdeal",

        // Fuel — a tank filled when needed. Petrol pump names vary endlessly by agent/location,
        // so a few generic words are kept alongside the national retailer brands.
        "indianoil", "indoil", "hindustanpetroleum", "bharatpetroleum", "iocl", "hpcl", "bpcl",
        "essarfuel", "nayarafuel", "shellfuel", "petrolpump", "petrol", "diesel", "fuelstation",

        // Telecom prepaid — the bare carrier name a recharge SMS actually uses; see class doc.
        "airtel", "jio", "vodafone",
    ).map(::squash).distinct().sortedByDescending { it.length }

    private const val UNKNOWN_PREFIX = "u:"

    private fun squash(s: String): String = MerchantNormalizer.squash(s)

    /**
     * Whether [merchantId] (as produced by [MerchantNormalizer.resolve]) names one of these
     * merchants. Catalog merchants (an id that isn't the "u:" unknown prefix) are never checked —
     * they were already judged on their own merits when they were added to [MerchantCatalog].
     */
    fun matches(merchantId: String): Boolean {
        if (!merchantId.startsWith(UNKNOWN_PREFIX)) return false
        val squashed = merchantId.removePrefix(UNKNOWN_PREFIX)
        return aliases.any { squashed.contains(it) }
    }
}
