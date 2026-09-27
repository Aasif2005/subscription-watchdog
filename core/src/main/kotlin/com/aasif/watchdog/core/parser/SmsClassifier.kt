package com.aasif.watchdog.core.parser

enum class SmsKind { MANDATE_CREATED, PRE_DEBIT, DEBIT, MANDATE_REVOKED, IRRELEVANT }

/**
 * Decides what a bank / UPI SMS is about using keyword rules. Order matters: a revocation
 * message often mentions the original mandate, and a mandate confirmation may mention the first
 * debit, so the more specific kinds are checked first.
 */
object SmsClassifier {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val recurringWord = rx("""\b(mandate|e-?mandate|auto\s?pay|standing\s+instruction|si|recurring|subscription|e-?nach|nach)\b""")
    private val revokedWord = rx("""\b(revoked|revocation|cancell?ed|paused|deleted|deactivated|terminated|stopped)\b""")
    private val createdWord = rx("""\b(created|registered|registration|set\s?up|activated|approved)\b""")
    private val preDebitWord = rx("""will\s+be\s+(debited|deducted|charged|executed|processed|auto-?debited)|to\s+be\s+debited|scheduled\s+(for|on)|\bdue\s+(on|for|date)\b|\bupcoming\b|pre-?debit""")
    private val debitWord = rx("""\b(debited|deducted|spent|paid|sent|charged|withdrawn|txn\s+of|transaction\s+of|purchase|payment\s+of)\b""")
    private val failedWord = rx("""\b(failed|declined|unsuccessful|insufficient|reversed|refund(ed)?|could\s+not\s+be)\b""")
    private val otpWord = rx("""\b(otp|one\s+time\s+password|verification\s+code)\b""")
    private val creditWord = rx("""\bcredited\b""")

    fun classify(body: String): SmsKind {
        val recurring = recurringWord.containsMatchIn(body)
        val debit = debitWord.containsMatchIn(body)
        return when {
            otpWord.containsMatchIn(body) && !debit -> SmsKind.IRRELEVANT
            recurring && revokedWord.containsMatchIn(body) -> SmsKind.MANDATE_REVOKED
            recurring && createdWord.containsMatchIn(body) -> SmsKind.MANDATE_CREATED
            preDebitWord.containsMatchIn(body) && (recurring || debit) -> SmsKind.PRE_DEBIT
            failedWord.containsMatchIn(body) -> SmsKind.IRRELEVANT
            debit -> SmsKind.DEBIT
            creditWord.containsMatchIn(body) -> SmsKind.IRRELEVANT
            else -> SmsKind.IRRELEVANT
        }
    }

    fun isRecurring(body: String): Boolean = recurringWord.containsMatchIn(body)
}
