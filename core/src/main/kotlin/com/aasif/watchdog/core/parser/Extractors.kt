package com.aasif.watchdog.core.parser

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Period

/** A date found in an SMS, plus the text just before it, which tells us what the date means. */
data class FoundDate(val date: LocalDate, val start: Int, val before: String)

/** Low-level field extraction shared by all SMS formats. */
internal object Extractors {

    // ---- Amounts ----------------------------------------------------------------------------

    /**
     * Symbols and codes that introduce a price. Mail from services billing outside India
     * (NordVPN, GitHub) states dollars, so matching rupees alone silently lost their amount.
     */
    private const val CURRENCY = """(?:rs\.?|inr|₹|us\s?\$|\$|usd|eur|€|gbp|£)"""

    private val amountRegex =
        Regex("""($CURRENCY)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val balanceContext = Regex("""(bal|balance|limit)\W*$""", RegexOption.IGNORE_CASE)

    /**
     * Words that mean the amount left the reader's account, checked just before the figure —
     * "your payment of ₹649" — since that is how a written-out receipt reads.
     */
    private val paidBefore = Regex(
        """(paid|pay(ment|ing)?(\s+of)?|charged?|billed?|debited|deducted|total|subtotal|amount(\s+due)?|invoice|receipt|order\s+total|grand\s+total|you\s+were|has\s+been)\W{0,12}$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The same words checked just after — "Rs 649.00 debited from A/c" — which is how a bank's
     * terse SMS phrasing reads instead. Bank SMS and receipt mail put the verb on opposite sides
     * of the number, so both directions have to be checked.
     */
    private val paidAfter = Regex(
        """^\s*(was\s+|has\s+been\s+|is\s+)?(paid|charged|billed|debited|deducted|spent)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * What a price tag looks like. "₹7,499/year" and "from ₹199 per month" are the shape of an
     * advert, not a receipt; a Coursera Plus promotion reached the dashboard through exactly this.
     */
    private val priceTagAfter = Regex(
        """^\s*(?:/|\s+per\s+|\s+a\s+|\s+each\s+)\s*(?:year|yr|month|mo|week|user|seat)|^\s*(?:onwards|only|\+\s*(?:gst|tax))""",
        RegexOption.IGNORE_CASE,
    )
    private val priceTagBefore = Regex(
        """(only|just|from|starting\s+at|starts\s+at|worth|save|upto|up\s+to|as\s+low\s+as|flat|get|off|upgrade\s+(for|to)|valued?\s+at)\W{0,12}$""",
        RegexOption.IGNORE_CASE,
    )

    private val currencyCodes = mapOf(
        "rs" to Money.INR, "rs." to Money.INR, "inr" to Money.INR, "₹" to Money.INR,
        "$" to "USD", "us$" to "USD", "us $" to "USD", "usd" to "USD",
        "€" to "EUR", "eur" to "EUR", "£" to "GBP", "gbp" to "GBP",
    )

    /** Amounts in text order, skipping account balances and credit limits. */
    fun amounts(body: String): List<Money> = found(body).map { it.money }

    fun amount(body: String): Money? = amounts(body).firstOrNull()

    /**
     * Amounts that the text says were actually paid, charged or billed — and that are not
     * advertised prices. This is the filter that separates a receipt from a promotion.
     */
    fun paidAmounts(body: String): List<Money> =
        found(body).filter { it.paid && !it.priceTag }.map { it.money }

    fun paidAmount(body: String): Money? = paidAmounts(body).firstOrNull()

    private data class FoundAmount(val money: Money, val paid: Boolean, val priceTag: Boolean)

    /**
     * The amount written right after a label, like "Next payment ₹139.00/month". The label is what
     * says what the number is, so the price-tag shape that [paidAmounts] rejects is fine here.
     */
    fun amountAfter(body: String, label: Regex): Money? =
        label.findAll(body).firstNotNullOfOrNull { at ->
            found(body.substring(at.range.last + 1).take(24)).firstOrNull()?.money
        }

    /** The first date written right after a label, like "Payment date 15 Aug 2026". */
    fun dateAfter(body: String, label: Regex, received: LocalDate): LocalDate? =
        label.findAll(body).firstNotNullOfOrNull { at ->
            dates(body.substring(at.range.last + 1).take(32), received).firstOrNull()?.date
        }

    private fun found(body: String): List<FoundAmount> =
        amountRegex.findAll(body).mapNotNull { m ->
            val before = body.substring(maxOf(0, m.range.first - 40), m.range.first)
            val after = body.drop(m.range.last + 1).take(20)
            if (balanceContext.containsMatchIn(before.takeLast(25))) return@mapNotNull null
            val value = m.groupValues[2].replace(",", "").trimEnd('.').toBigDecimalOrNull()
                ?: return@mapNotNull null
            val code = currencyCodes[m.groupValues[1].lowercase().replace(" ", "")] ?: Money.INR
            FoundAmount(
                money = Money(value, code),
                paid = paidBefore.containsMatchIn(before) || paidAfter.containsMatchIn(after),
                priceTag = priceTagAfter.containsMatchIn(after) || priceTagBefore.containsMatchIn(before),
            )
        }.toList()

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
        // Not preceded by a count ("3 months", "12-month"): that names a plan's length, which
        // [planDuration] reads instead — "3 months plan" is not the same claim as "billed monthly".
        """(?<!\d)(?<!\d )(?<!\d-)month""" to Frequency.MONTHLY,
        """(?<!\d)(?<!\d )(?<!\d-)week""" to Frequency.WEEKLY,
        """daily|every day""" to Frequency.DAILY,
    ).map { (pattern, f) -> Regex(pattern, RegexOption.IGNORE_CASE) to f }

    /** Reads "Frequency: Monthly" if present, otherwise looks for frequency words anywhere. */
    fun frequency(body: String): Frequency? {
        val scope = frequencyField.find(body)?.groupValues?.get(1) ?: body
        return frequencyWords.firstOrNull { it.first.containsMatchIn(scope) }?.second
    }

    // ---- Free trials ------------------------------------------------------------------------

    /** What a trial receipt promises: when paying starts, and at what price. */
    data class TrialTerms(val firstCharge: LocalDate, val price: Money?)

    private val trialEnds = Regex("""\btrial\b[^.]{0,80}?\b(?:ends?|ending|expires?)\s+on\s+""", RegexOption.IGNORE_CASE)
    private val renewalPrice = Regex("""\b(?:currently|then|thereafter|after\s+(?:the\s+)?trial)[\s(:,]{0,4}""", RegexOption.IGNORE_CASE)

    /**
     * "Your trial will end on 15 Feb 2027 ... (currently ₹139.00/month)". The receipt's own total is
     * zero, so the only amount worth keeping is the one quoted for after the trial — which is
     * exactly the price-tag shape [paidAmounts] rejects, hence read here on purpose.
     */
    fun trialTerms(body: String, received: LocalDate): TrialTerms? {
        val ends = trialEnds.find(body) ?: return null
        val date = dates(body.substring(ends.range.last + 1).take(30), received).firstOrNull()?.date ?: return null
        val price = renewalPrice.find(body)?.let { m ->
            found(body.substring(m.range.last + 1).take(30)).firstOrNull()?.money
        }
        return TrialTerms(date, price)
    }

    /** An introductory price that gives way to the real one: [price] per [frequency] from [firstCharge]. */
    data class IntroTerms(val price: Money, val frequency: Frequency?, val firstCharge: LocalDate)

    private val introThen = Regex(
        """\bcharged\s+\S{1,15}\s+for\s+\d{1,2}\s+(?:days?|weeks?|months?|years?),?\s+then\b[\s(:,]{0,4}""",
        RegexOption.IGNORE_CASE,
    )
    private val startingOn = Regex("""\bstarting\s+(?:on\s+)?""", RegexOption.IGNORE_CASE)

    /**
     * "You will be automatically charged ₹99.00 for 2 months, then ₹139.00/month starting on 15 Aug
     * 2026": what is paid now is the introductory price, and the plan that renews is the second one.
     * Like [trialTerms], the renewal price is in the shape [paidAmounts] rejects, so it is read here.
     */
    fun introTerms(body: String, received: LocalDate): IntroTerms? {
        val then = introThen.find(body) ?: return null
        val tail = body.substring(then.range.last + 1).take(70)
        val price = found(tail).firstOrNull()?.money ?: return null
        val starts = startingOn.find(tail) ?: return null
        val date = dates(tail.substring(starts.range.last + 1).take(30), received).firstOrNull()?.date ?: return null
        return IntroTerms(price, frequency(tail.substring(0, starts.range.first)), date)
    }

    // ---- Plan length ------------------------------------------------------------------------

    private val numberedPlan = Regex(
        """\b(\d{1,2})[\s\-]*(month|mo|year|yr|week|day)s?\b|\b(\d{2,3})[\s\-]*days?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val namedPlan = listOf(
        """\b(annual|yearly|1[\s\-]?year)\s+(plan|subscription|membership|billing)""" to Period.ofYears(1),
        """\bquarterly\s+(plan|subscription|membership|billing)""" to Period.ofMonths(3),
        """\bhalf[\s\-]?yearly\s+(plan|subscription|membership|billing)""" to Period.ofMonths(6),
        """\bmonthly\s+(plan|subscription|membership|billing)""" to Period.ofMonths(1),
    ).map { (p, period) -> Regex(p, RegexOption.IGNORE_CASE) to period }

    /** The mail says the plan renews by itself, so its end date is also its next charge. */
    private val autoRenewWord = Regex(
        """auto(matic(ally)?)?[\s\-]?renew|renews?\s+automatically|will\s+(be\s+)?renew|recurring\s+(billing|payment)|until\s+you\s+cancel""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * How long a plan was bought for: "3 months plan", "12-month subscription", "90 days".
     *
     * A receipt that never names a renewal date very often names the length instead, which is the
     * only thing that turns "you subscribed on 1 Sep" into a date worth warning about.
     */
    fun planDuration(body: String): Period? {
        numberedPlan.find(body)?.let { m ->
            m.groupValues[3].toIntOrNull()?.let { return Period.ofDays(it) }
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            if (n <= 0) return@let
            return when (m.groupValues[2].lowercase().take(2)) {
                "mo" -> Period.ofMonths(n)
                "ye", "yr" -> Period.ofYears(n)
                "we" -> Period.ofWeeks(n)
                else -> Period.ofDays(n)
            }
        }
        return namedPlan.firstOrNull { it.first.containsMatchIn(body) }?.second
    }

    fun autoRenews(body: String): Boolean = autoRenewWord.containsMatchIn(body)

    /** The [Frequency] a plan length lines up with, if any. "3 months" is a quarterly plan. */
    fun frequencyOf(period: Period): Frequency? {
        val months = period.toTotalMonths().toInt()
        if (period.days == 0 && months > 0) {
            return Frequency.scheduled.firstOrNull { it.approxDays in 1..400 && monthsOf(it) == months }
        }
        if (months == 0) return Frequency.fromGap(period.days.toLong())
        return null
    }

    private fun monthsOf(f: Frequency): Int = when (f) {
        Frequency.MONTHLY -> 1
        Frequency.BIMONTHLY -> 2
        Frequency.QUARTERLY -> 3
        Frequency.HALF_YEARLY -> 6
        Frequency.YEARLY -> 12
        else -> 0
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

    /**
     * A UPI mandate's Unique Mandate Number, as in "UMN: 4f3a...@okhdfcbank".
     *
     * Banks put it in every AutoPay debit, including the many that never use the word "AutoPay" —
     * which is why not one of the 731 debits read from this phone was recognised as recurring.
     */
    private val umnRegex = Regex("""\bumn\b\W{0,3}([A-Za-z0-9._\-]{6,}@[A-Za-z]{2,})?""", RegexOption.IGNORE_CASE)

    fun hasUmn(body: String): Boolean = umnRegex.containsMatchIn(body)

    // "<hex>@okicici": a UMN printed with its handle.
    private val umnWithHandle = Regex("""(?<![0-9A-Za-z])([0-9a-f]{20,40})@[a-z]{2,}""", RegexOption.IGNORE_CASE)

    // "UMN: x", "Mandate ID x", "UMRN x", "SI Hub ID x": a number the text itself calls the mandate's.
    private val labelledRef = Regex(
        """\b(?:umn|umrn|mandate\s*(?:id|ref(?:erence)?|no\.?|number)|si\s*hub\s*id)\b\W{0,4}([A-Za-z0-9]{12,40})""",
        RegexOption.IGNORE_CASE,
    )

    // A UUID with its dashes, as some systems print a mandate number.
    private val dashedRef = Regex(
        """(?<![0-9A-Za-z])([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![0-9A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )

    // IOB and others print the number bare, after the masked account ("A/C No. ##AccNum##.795fe2..."),
    // sometimes cut short of its 32 characters. Anywhere in the text, but hex only, with both letters
    // and digits, and never inside a link, so an amount, phone number, account number or transaction
    // id is not taken for one.
    private val bareRef = Regex("""(?<![0-9A-Za-z/=?&\-])([0-9a-f]{20,40})(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)

    /**
     * The number that ties a mandate's texts together: the one in the text that creates it is the
     * one in the text that revokes it. Returned as a short hash, so the stored event can be matched
     * to its mandate without keeping the number, which names the mandate to the bank. Null when the
     * text carries none.
     */
    fun mandateRef(body: String, allowBare: Boolean = true): String? {
        val raw = umnWithHandle.find(body)?.groupValues?.get(1)
            ?: labelledRef.find(body)?.groupValues?.get(1)
            ?: dashedRef.find(body)?.groupValues?.get(1)?.replace("-", "")
            // A mail's bare hex is mostly a tracking id; only a bank text prints the number unlabelled.
            ?: bareRef.takeIf { allowBare }?.findAll(body)?.map { it.groupValues[1] }
                ?.firstOrNull { t -> t.any(Char::isDigit) && t.any(Char::isLetter) }
            ?: return null
        return shortHash(raw.lowercase())
    }

    private fun shortHash(raw: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    // "Order number: GPA.1234-5678-9012-34567" (Google One orders start SOP.): a renewal's "..0" suffix is left out, so every charge
    // of one purchase names the purchase.
    private val playOrder = Regex("""\border\s*(?:number|id|no\.?)\W{0,3}([A-Z]{3}\.\d{4}-\d{4}-\d{4}-\d{5})""", RegexOption.IGNORE_CASE)

    /**
     * The store order a mail is about, as a short hash like [mandateRef] (the number itself is never
     * kept). A store's receipt and its cancellation mail carry the same one, which is what says which
     * purchase a cancellation ends. Hashed with a prefix, so it can never equal a mandate number.
     */
    fun orderRef(body: String): String? =
        playOrder.find(body)?.let { shortHash("order:" + it.groupValues[1].uppercase()) }

    // A store receipt's table: "Item Price Premium Standard (Spotify: Music and Podcasts) ₹119.00/month". Play prints
    // every item as "<the developer's plan title> (<app name>)", so the bracket names the platform and the title is
    // free text ("Individual Offer", "Premium Career Monthly").
    private val receiptItem = Regex("""\bItem\s+Price\s+([^()₹$€£\n]{2,60}?)\s*\(([^()]{2,60})\)""", RegexOption.IGNORE_CASE)
    private val appTail = Regex("""\s*(:|\s-\s|\sby\s).*$""", RegexOption.IGNORE_CASE)
    private val nameChars = Regex("""[A-Za-z0-9][A-Za-z0-9 &+.\-]*""")

    /** Words that really name a tier of a service; the rest of a plan title is the developer's own wording. */
    private val tierWords = listOf("Premium", "Pro", "Plus", "Go", "Max", "Ultra", "Super", "Lite", "Family", "Duo", "Student")

    /**
     * What to call the plan a store receipt is for: the platform the receipt's bracket names, then any real
     * tier word of its title. "ChatGPT Go (ChatGPT)" is "ChatGPT Go", "Premium Standard (Spotify: Music and
     * Podcasts)" is "Spotify Premium", "Individual Offer (Apple Music)" is "Apple Music". The bracket only counts
     * when [isPlatform] agrees it is the service the mail is from; otherwise [catalogName] stands in. Null when
     * the mail has no item line, so the caller keeps the catalog name.
     */
    fun itemName(body: String, catalogName: String, isPlatform: (String) -> Boolean): String? {
        val match = receiptItem.find(body) ?: return null
        val title = match.groupValues[1].trim()
        val app = appTail.replace(match.groupValues[2].trim(), "").trim()
        val squash = { t: String -> t.lowercase().filter { it.isLetterOrDigit() } }
        var platform = app.takeIf { it.length in 2..30 && nameChars.matches(it) && isPlatform(it) } ?: catalogName
        // "Sony LIV" and "Zee 5" are the catalog's "SonyLIV" and "ZEE5": one service, one spelling.
        if (squash(platform) == squash(catalogName)) platform = catalogName
        val have = platform.lowercase().split(' ').toSet()
        val tiers = title.split(Regex("""[\s&+/\-]+""")).mapNotNull { w -> tierWords.firstOrNull { it.equals(w, ignoreCase = true) } }
            .distinct().filter { it.lowercase() !in have }.take(2)
        // No tier in the title: "LinkedIn" alone would drop the "Premium" the catalog knows it to be.
        if (tiers.isEmpty() && catalogName.length > platform.length && catalogName.startsWith("$platform ", ignoreCase = true)) return catalogName
        return (listOf(platform) + tiers).joinToString(" ")
    }

    /**
     * A payee that is a phone number or a person's handle: money sent to a friend, never a
     * subscription. These are what fill the repeated-amount clusters that look like plans.
     */
    private val personVpa = Regex("""\b(\+?91)?[6-9]\d{9}@[a-z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val p2pWord = Regex(
        """\b(sent\s+to|transferred\s+to|paid\s+to\s+(mr|mrs|ms|shri|smt)\b|upi/p2p|/p2p/|self\s+transfer|to\s+your\s+own)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isPeerToPeer(body: String): Boolean =
        personVpa.containsMatchIn(body) || p2pWord.containsMatchIn(body)
}
