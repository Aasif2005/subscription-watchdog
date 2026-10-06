package com.aasif.watchdog.core.merchant

data class ResolvedMerchant(
    /** Catalog id for known services, otherwise "u:" plus the squashed name. */
    val id: String,
    val name: String,
    val category: String,
    val known: KnownMerchant?,
)

/**
 * Maps the many spellings banks use ("NETFLIX.COM", "Netflix Entertainment Services",
 * "razorpay.netflix@icici") to one canonical merchant, so debits can be grouped per service.
 */
class MerchantNormalizer(catalog: List<KnownMerchant> = MerchantCatalog.all) {

    // Longest alias first, so "applemusic" wins over a shorter overlapping alias.
    private val aliases: List<Pair<String, KnownMerchant>> =
        catalog.flatMap { m -> m.aliases.map { it to m } }.sortedByDescending { it.first.length }

    /** Finds a catalog merchant mentioned anywhere in [text], e.g. a whole SMS body. */
    fun findKnownIn(text: String): KnownMerchant? {
        val squashed = squash(text)
        return aliases.firstOrNull { squashed.contains(it.first) }?.second
    }

    fun resolve(raw: String): ResolvedMerchant {
        findKnownIn(raw)?.let { return it.toResolved() }

        val cleaned = clean(raw)
        val squashed = squash(cleaned)
        // Catch small typos and truncations such as "NETFLX ENT" or "SPOTIFYIN", word by word.
        val candidates = (cleaned.split(' ').map(::squash) + squashed).filter { it.length >= 5 }.distinct()
        aliases.firstOrNull { (alias, _) ->
            alias.length >= 5 && candidates.any {
                // The Jaro-Winkler prefix bonus scores an exact-prefix match almost as high as a
                // real typo, which used to fuzzy-match a short, genuinely different, uncatalogued
                // word onto a longer sibling alias it happens to prefix — "SWIGGY" (a plain food
                // order) onto "swiggyone" (the paid membership), the same way for "flipkart" onto
                // "flipkartplus" or "amazon" onto "amazonprime". A real typo or truncation stays
                // close to the alias's own length; capping the gap keeps that case while rejecting
                // a shorter word that is simply a different, legitimate brand on its own.
                kotlin.math.abs(it.length - alias.length) <= MAX_LENGTH_GAP && jaroWinkler(it, alias) >= FUZZY_THRESHOLD
            }
        }?.let { return it.second.toResolved() }

        if (squashed.isEmpty()) return UNKNOWN
        return ResolvedMerchant("u:$squashed", titleCase(cleaned), "Other", known = null)
    }

    private fun KnownMerchant.toResolved() = ResolvedMerchant(id, name, category, this)

    companion object {
        private const val FUZZY_THRESHOLD = 0.92

        /** How many characters a fuzzy candidate may differ in length from the alias it matches. */
        private const val MAX_LENGTH_GAP = 2

        val UNKNOWN = ResolvedMerchant("u:unknown", "Unknown merchant", "Other", known = null)

        // Payment gateways and legal suffixes that wrap the real merchant name.
        private val noiseTokens = setOf(
            "razorpay", "rzp", "payu", "cashfree", "cf", "billdesk", "ccavenue", "paytm", "phonepe", "juspay",
            "pvt", "private", "ltd", "limited", "llp", "inc", "india", "technologies", "technology",
            "services", "service", "solutions", "digital", "online", "com", "www", "in", "co", "the",
            "upi", "autopay", "mandate", "payment", "payments", "subscription", "bill",
        )

        fun squash(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

        /** Strips the VPA domain, gateway prefixes and legal suffixes. */
        fun clean(raw: String): String {
            val beforeAt = raw.substringBefore('@')
            return beforeAt
                .split(Regex("[^A-Za-z0-9&]+"))
                .filter { it.isNotBlank() && it.lowercase() !in noiseTokens }
                .joinToString(" ")
        }

        private fun titleCase(s: String): String =
            s.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

        fun jaroWinkler(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
            val aMatched = BooleanArray(a.length)
            val bMatched = BooleanArray(b.length)
            var matches = 0
            for (i in a.indices) {
                val lo = maxOf(0, i - window)
                val hi = minOf(b.length - 1, i + window)
                for (j in lo..hi) {
                    if (!bMatched[j] && a[i] == b[j]) {
                        aMatched[i] = true; bMatched[j] = true; matches++; break
                    }
                }
            }
            if (matches == 0) return 0.0
            var transpositions = 0
            var k = 0
            for (i in a.indices) {
                if (!aMatched[i]) continue
                while (!bMatched[k]) k++
                if (a[i] != b[k]) transpositions++
                k++
            }
            val m = matches.toDouble()
            val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
            val prefix = a.zip(b).take(4).takeWhile { it.first == it.second }.size
            return jaro + prefix * 0.1 * (1 - jaro)
        }
    }
}
