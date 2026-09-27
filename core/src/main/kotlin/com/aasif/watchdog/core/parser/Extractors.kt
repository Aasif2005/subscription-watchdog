package com.aasif.watchdog.core.parser

import com.aasif.watchdog.core.model.Frequency
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate

/** A date found in an SMS, plus the text just before it, which tells us what the date means. */
data class FoundDate(val date: LocalDate, val start: Int, val before: String)

/** Low-level field extraction shared by all SMS formats. */
internal object Extractors {

    // ---- Amounts ----------------------------------------------------------------------------

    private val amountRegex =
        Regex("""(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val balanceContext = Regex("""(bal|balance|limit)\W*$""", RegexOption.IGNORE_CASE)

    /** Amounts in text order, skipping account balances and credit limits. */
    fun amounts(body: String): List<BigDecimal> =
        amountRegex.findAll(body)
            .filterNot { m -> balanceContext.containsMatchIn(body.substring(maxOf(0, m.range.first - 25), m.range.first)) }
            .mapNotNull { it.groupValues[1].replace(",", "").trimEnd('.').toBigDecimalOrNull() }
            .toList()

    fun amount(body: String): BigDecimal? = amounts(body).firstOrNull()

    // ---- Dates ------------------------------------------------------------------------------

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private const val MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?"

    private val isoDate = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val numericDate = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})\b""")
    private val dayMonthName =
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/]*$MONTH[\s\-/,']*(\d{4}|\d{2})?(?![\d:])""", RegexOption.IGNORE_CASE)
    private val monthNameDay =
        Regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)

    /**
     * All dates in [body], in text order. Indian SMS use day-first numeric dates. When the year
     * is missing it is taken from [received]; a date that would then be months in the past is
     * assumed to be next year (a "due on 02 Jan" notice sent in late December).
     */
    fun dates(body: String, received: LocalDate): List<FoundDate> {
        val found = mutableListOf<FoundDate>()
        val taken = mutableListOf<IntRange>()

        fun add(range: IntRange, y: Int?, m: Int, d: Int) {
            if (taken.any { it.first <= range.last && range.first <= it.last }) return
            val date = build(y, m, d, received) ?: return
            taken += range
            found += FoundDate(date, range.first, body.substring(maxOf(0, range.first - 40), range.first).lowercase())
        }

        isoDate.findAll(body).forEach { add(it.range, it.int(1), it.int(2), it.int(3)) }
        numericDate.findAll(body).forEach { add(it.range, it.int(3), it.int(2), it.int(1)) }
        dayMonthName.findAll(body).forEach {
            add(it.range, it.groupValues[3].toIntOrNull(), months.getValue(it.groupValues[2].lowercase()), it.int(1))
        }
        monthNameDay.findAll(body).forEach {
            add(it.range, it.int(3), months.getValue(it.groupValues[1].lowercase()), it.int(2))
        }
        return found.sortedBy { it.start }
    }

    private fun MatchResult.int(i: Int) = groupValues[i].toInt()

    private fun build(year: Int?, month: Int, day: Int, received: LocalDate): LocalDate? = try {
        when {
            year == null -> LocalDate.of(received.year, month, day)
                .let { if (it.isBefore(received.minusMonths(6))) it.plusYears(1) else it }
            year < 100 -> LocalDate.of(2000 + year, month, day)
            else -> LocalDate.of(year, month, day)
        }
    } catch (_: DateTimeException) {
        null
    }

    // ---- Frequency --------------------------------------------------------------------------

    private val frequencyField = Regex("""frequency\s*(?:is|[:\-])?\s*([a-z0-9 \-]{3,25})""", RegexOption.IGNORE_CASE)
    private val frequencyWords: List<Pair<Regex, Frequency>> = listOf(
        """as\s*(?:and\s*when\s*)?presented|as and when|on demand""" to Frequency.AS_PRESENTED,
        """half[\s\-]?year|semi[\s\-]?annual|every 6 months""" to Frequency.HALF_YEARLY,
        """bi[\s\-]?month|every 2 months""" to Frequency.BIMONTHLY,
        """fortnight""" to Frequency.FORTNIGHTLY,
        """quarter""" to Frequency.QUARTERLY,
        """annual|yearly|every year""" to Frequency.YEARLY,
        """month""" to Frequency.MONTHLY,
        """week""" to Frequency.WEEKLY,
        """daily|every day""" to Frequency.DAILY,
    ).map { (pattern, f) -> Regex(pattern, RegexOption.IGNORE_CASE) to f }

    /** Reads "Frequency: Monthly" if present, otherwise looks for frequency words anywhere. */
    fun frequency(body: String): Frequency? {
        val scope = frequencyField.find(body)?.groupValues?.get(1) ?: body
        return frequencyWords.firstOrNull { it.first.containsMatchIn(scope) }?.second
    }

    // ---- Merchant ---------------------------------------------------------------------------

    // Strongest cue first: "towards X" almost always names the payee, "for X" often does not.
    private val merchantCues = listOf("towards", "merchant name", "merchant", "payee", "info", "at", "to", "for", "by")

    private val stopAfterName = Regex(
        """\s+(?:on|via|of|for|is|has|with|from|ref|ref\s*no|upi|using|avl|avbl|bal|txn|mandate|autopay|a/c|ac|and|will|dated|at|umn|towards|through|under|thru)\b|[,;()]|\.\s|\.$|\s{2,}""",
        RegexOption.IGNORE_CASE,
    )
    private val notAName = Regex(
        """^(?:a/?c|acct|account|your|you|rs\.?|inr|₹|\d|xx|card|bank|the|mandate|autopay|auto pay|si|upi|vpa|umn|ref|txn|transaction|payment|verification|amount|debit|credit|beneficiary|mobile|a\b)""",
        RegexOption.IGNORE_CASE,
    )

    // ICICI: "Acct XX123 debited for Rs 350.00 on 05-Sep-26; SHREE GYM credited."
    private val payeeCredited = Regex("""[;.]\s*([A-Za-z][A-Za-z0-9 .&'_\-]{1,40}?)\s+credited\b""")

    /** The payee name after a cue word, e.g. "to NETFLIX on 05-09-26" gives "NETFLIX". */
    fun merchantPhrase(body: String): String? {
        payeeCredited.find(body)?.groupValues?.get(1)?.trim()
            ?.takeUnless { notAName.containsMatchIn(it) }
            ?.let { return it }
        for (cue in merchantCues) {
            val regex = Regex("""\b$cue\b\s*[:\-]?\s*([A-Za-z0-9][A-Za-z0-9 .&*'_@\-/]{1,60})""", RegexOption.IGNORE_CASE)
            for (m in regex.findAll(body)) {
                var name = m.groupValues[1].split(stopAfterName).first().trim().trimEnd('.', '-', '/')
                // ICICI-style "Info: UPI/123456789/NETFLIX": keep the most name-like segment.
                if ('/' in name) name = name.split('/').filter { seg -> seg.count { it.isLetter() } >= 3 }
                    .filterNot { it.equals("upi", ignoreCase = true) }
                    .maxByOrNull { it.length } ?: continue
                if (name.length < 2 || notAName.containsMatchIn(name)) continue
                if (name.contains('@')) continue // VPAs are handled by vpa()
                return name
            }
        }
        return null
    }

    // A UPI handle has no dot after the '@'; an email address does.
    private val vpaRegex = Regex("""([A-Za-z0-9][A-Za-z0-9._\-]{1,60})@([A-Za-z]{2,})\b(?!\.)""")

    fun vpa(body: String): String? = vpaRegex.find(body)?.value
}
