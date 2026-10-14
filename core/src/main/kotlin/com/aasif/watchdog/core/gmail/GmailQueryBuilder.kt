package com.aasif.watchdog.core.gmail

/**
 * Builds the Gmail `q` search string for a sync run.
 *
 * Deliberately broad: recall matters here because the parser is the strict filter, and an e-mail
 * it never downloads can never be recovered. Merchant names are *not* in the query — a real
 * receipt always carries one of these payment words anyway, while a bare merchant name mostly
 * matches newsletters and inflates the message count against the per-run cap.
 *
 * Gmail's Promotions tab is excluded: the mapper discards every message carrying that label anyway,
 * so downloading it only spends quota and time. Other marketing is filtered later by
 * [com.aasif.watchdog.core.parser.SmsClassifier.isPromotional].
 */
object GmailQueryBuilder {
    private val terms = listOf(
        "subscription", "subscriptions", "autopay", "mandate", "nach", "renewal", "renews",
        "renewed", "receipt", "invoice", "billed", "billing", "debited", "charged", "membership",
        "\"auto pay\"", "\"auto-renew\"", "\"auto renewal\"", "\"e-mandate\"",
        "\"payment successful\"", "\"payment received\"", "\"standing instruction\"",
    )

    /**
     * [beforeEpochSeconds] bounds the newest end of the window. Gmail lists newest first, so the
     * historical backfill walks backwards by moving that bound, one run at a time.
     */
    fun build(afterEpochSeconds: Long, beforeEpochSeconds: Long? = null): String = buildString {
        append('(').append(terms.joinToString(" OR ")).append(')')
        append(" after:").append(afterEpochSeconds)
        beforeEpochSeconds?.let { append(" before:").append(it) }
        append(" -in:spam -in:trash -category:promotions")
    }

    private val startedTerms = listOf(
        "welcome", "subscribed", "\"thanks for subscribing\"", "\"thank you for subscribing\"",
        "\"membership has started\"", "\"subscription is active\"", "\"plan is active\"",
    )

    /**
     * Mail from the services in [senders] saying a plan started ("Welcome to Spotify Premium").
     * Searched separately because such mail is often filed under Promotions, which [build] leaves
     * out, and may never say "subscription". Limited to known senders, so the welcome mail of
     * every newsletter the user ever joined is not downloaded.
     */
    fun buildSubscriptionStarted(senders: List<String>, afterEpochSeconds: Long): String = buildString {
        append('(').append(startedTerms.joinToString(" OR ")).append(')')
        append(" (").append(senders.distinct().joinToString(" OR ") { "from:$it" }).append(')')
        append(" after:").append(afterEpochSeconds)
        append(" -in:spam -in:trash")
    }
}
