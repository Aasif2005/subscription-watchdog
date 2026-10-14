package com.aasif.watchdog.core.merchant

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money

/** One plan a service sells in India, at its list price. */
data class PlanPrice(val rupees: Int, val frequency: Frequency, val label: String)

/**
 * India list prices for a few common services, used only to *suggest* a plan length the user then
 * confirms — the last source tried, after the plan's own mail, its bank mandate and its payment
 * history have said nothing. Never applied silently: prices change, promotional prices (a ₹69
 * first month) match no plan, and one service can sell two plans at the same price.
 *
 * Checked against published price lists in October 2026 (third-party summaries of each service's
 * India pricing, not the services' own pages). Update with each release; a stale entry only
 * produces a wrong suggestion the user can reject, never a wrong date on its own.
 */
object PlanPrices {
    private val table: Map<String, List<PlanPrice>> = mapOf(
        "netflix" to listOf(
            PlanPrice(149, Frequency.MONTHLY, "Mobile"),
            PlanPrice(199, Frequency.MONTHLY, "Basic"),
            PlanPrice(499, Frequency.MONTHLY, "Standard"),
            PlanPrice(649, Frequency.MONTHLY, "Premium"),
        ),
        "spotify" to listOf(
            PlanPrice(119, Frequency.MONTHLY, "Premium Individual"),
            PlanPrice(59, Frequency.MONTHLY, "Premium Student"),
            PlanPrice(149, Frequency.MONTHLY, "Premium Duo"),
            PlanPrice(179, Frequency.MONTHLY, "Premium Family"),
            PlanPrice(1189, Frequency.YEARLY, "Premium Individual, yearly"),
        ),
        "youtube" to listOf(
            PlanPrice(149, Frequency.MONTHLY, "Individual"),
            PlanPrice(89, Frequency.MONTHLY, "Student"),
            PlanPrice(299, Frequency.MONTHLY, "Family"),
            PlanPrice(159, Frequency.MONTHLY, "Individual, prepaid monthly"),
            PlanPrice(459, Frequency.QUARTERLY, "Individual, prepaid quarterly"),
            PlanPrice(1490, Frequency.YEARLY, "Individual, prepaid yearly"),
        ),
        "prime" to listOf(
            PlanPrice(299, Frequency.MONTHLY, "Prime, monthly"),
            PlanPrice(599, Frequency.QUARTERLY, "Prime, quarterly"),
            PlanPrice(1499, Frequency.YEARLY, "Prime, yearly"),
            PlanPrice(799, Frequency.YEARLY, "Prime Lite"),
            PlanPrice(399, Frequency.YEARLY, "Prime Shopping Edition"),
        ),
        "hotstar" to listOf(
            PlanPrice(79, Frequency.MONTHLY, "Mobile, monthly"),
            PlanPrice(149, Frequency.QUARTERLY, "Mobile, quarterly"),
            PlanPrice(499, Frequency.YEARLY, "Mobile, yearly"),
            PlanPrice(149, Frequency.MONTHLY, "Super, monthly"),
            PlanPrice(349, Frequency.QUARTERLY, "Super, quarterly"),
            PlanPrice(1099, Frequency.YEARLY, "Super, yearly"),
            PlanPrice(299, Frequency.MONTHLY, "Premium, monthly"),
            PlanPrice(699, Frequency.QUARTERLY, "Premium, quarterly"),
            PlanPrice(2199, Frequency.YEARLY, "Premium, yearly"),
        ),
        "applemusic" to listOf(
            PlanPrice(139, Frequency.MONTHLY, "Individual"),
            PlanPrice(69, Frequency.MONTHLY, "Student"),
            PlanPrice(229, Frequency.MONTHLY, "Family"),
        ),
        "googleone" to listOf(
            PlanPrice(130, Frequency.MONTHLY, "100 GB, monthly"),
            PlanPrice(1300, Frequency.YEARLY, "100 GB, yearly"),
            PlanPrice(650, Frequency.MONTHLY, "2 TB, monthly"),
            PlanPrice(6500, Frequency.YEARLY, "2 TB, yearly"),
            PlanPrice(1950, Frequency.MONTHLY, "AI Pro, monthly"),
        ),
    )

    /**
     * The one plan [merchantId] sells at exactly [amount], or null when none does or several do
     * (JioHotstar sells Mobile quarterly and Super monthly both at ₹149) — then the user is asked
     * without a suggestion.
     */
    fun suggest(merchantId: String, amount: Money): PlanPrice? {
        if (amount.currency != Money.INR) return null
        val rupees = amount.amount.stripTrailingZeros().takeIf { it.scale() <= 0 }?.toInt() ?: return null
        return table[merchantId].orEmpty().filter { it.rupees == rupees }.singleOrNull()
    }
}
