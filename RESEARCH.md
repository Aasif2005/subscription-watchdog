# Is SMS-based subscription detection actually useful?

This question was asked directly, after "Swiggy One" showed up as a subscription when it was
really a Swiggy Instamart grocery order. This document is the honest answer, and it's meant to be
kept up to date — not a one-off incident writeup — because the same underlying limit keeps
producing new false positives (Coursera, PVR INOX, Airtel, generic Swiggy orders, now Swiggy One)
one bug report at a time, and it's worth having the actual shape of the problem written down once.

## What a bank/UPI SMS actually contains

A payment SMS carries exactly three facts: who was paid (a payee descriptor, chosen by whoever
registered the merchant with the bank or card network — not by the app, not by the user), how
much, and when. It does not carry a product name, a line item, or a purchase-intent flag. This is
not a parsing gap; it is the entire content of the message. No amount of smarter text extraction —
better regex, a local model, a cloud model — can recover information the bank never sent, because
sending it was never their job. The SMS exists to confirm a debit happened, not to describe what
was bought.

This is a hard ceiling on the whole approach, and it is worth being explicit that it is a ceiling,
not a bug: it bounds how good subscription detection from SMS text can ever get, independent of
how well any given version of the parser is written.

## Three tiers of evidence, ranked by how much they actually earn

The app already encodes something like this ranking (`core/.../engine/SubscriptionEngine.kt`'s
`predict()`, `core/.../parser/SmsClassifier.kt`), but it's worth stating outright, because the
three tiers behave very differently:

**Rule A — an explicit mandate/AutoPay signal.** "UPI-Mandate ... created", "AutoPay mandate ...
registered", a UMN, a mandate revocation. This is close to deterministic: NPCI/RBI mandate a
fairly standardized vocabulary for this class of SMS, banks don't have much room to phrase it
differently, and the signal directly states the thing being inferred ("this recurs") rather than
implying it from a side effect. This tier is genuinely strong and worth building on. It's also
what was almost entirely *missing* before an earlier session's fix — 0 of 731 stored debits had
`viaMandate = true`, because the UMN-without-the-word-"AutoPay" case wasn't recognised. Fixing
that was a real, unambiguous win with no false-positive tradeoff.

**Rule B — catalog membership.** The payee descriptor matches a name in `MerchantCatalog.kt`. This
is only as good as the catalog's aliases and the assumption that a payment descriptor uniquely
identifies a product. That assumption breaks for any merchant whose umbrella brand sells both a
subscription and a one-off product under the same billing identity — see the worked example below.
It also breaks, differently, when a *bare* alias is inherently ambiguous (`"kindle"` names both
the Kindle Unlimited subscription and a one-off Kindle purchase; there's no separate word for the
device).

**Rule C — bare amount+timing pattern matching, no mandate wording, no catalog trust.**
`core/.../engine/RecurrenceDetector.kt` clustering two or three charges of similar amount at a
similar interval. This is the weakest tier by a wide margin, and it is the *specific* mechanism
behind every false positive found across this and the previous session:

- A Coursera Plus marketing email became a subscription the user never had.
- PVR INOX movie visits, coincidentally similar in price and roughly 90 days apart, pattern-matched
  a "quarterly subscription" exactly as well as a real one would.
- Airtel prepaid recharges, monthly out of habit rather than autopay, were trusted the same way a
  real OTT plan is, purely from being a catalog-recognised name.
- Generic Swiggy food orders risked the same thing before merchant-category exclusions existed.
- Swiggy One / Kindle Unlimited (below) — the newest variant: even when the *merchant identity* is
  correctly resolved, the identity itself doesn't disambiguate a membership charge from a sibling
  one-off purchase.

Every one of these has the same shape: inferring "this is a recurring plan" from a coincidence in
the numbers, not from the text actually saying so. Rule C should be trusted least, and the fixes in
this repository have consistently been about *not* letting it produce silent confidence — first by
excluding whole merchant categories that are inherently one-off (movies, food delivery, cabs, fuel,
telecom prepaid — `NonSubscriptionCatalog.kt`, `KnownMerchant.autopayOnly`), and now by requiring
real evidence before a "membership-confusable" catalog entry is trusted
(`KnownMerchant.membershipConfusable`) rather than a bare pattern.

## Worked example: Swiggy One / Bundl Technologies / Instamart

The concrete case that prompted this document. Two ₹1280-ish debits, a day apart, both resolved to
merchant id `swiggyone`. The user confirmed both were Swiggy Instamart grocery orders, not a
membership charge — corroborated by an OTP SMS in the same window reading `"...OTP for your
transaction with Bundl Tech for INR 1281.00 using IDFC FIRST Bank debit card..."`. **Bundl
Technologies is Swiggy's registered legal entity, used as the billing identity across every one of
Swiggy's product lines** — food delivery, Instamart, Dineout, Genie — not something specific to the
Swiggy One membership. A bank SMS naming that payee cannot, in principle, distinguish "this is the
annual Swiggy One renewal" from "this is a grocery order," because the bank was never told which
one it was, and the SMS format has no field for it.

This is why the fix here is not "read the text more carefully" — there was nothing in the text to
read. The fix is to stop letting a bare two-charge pattern under this identity produce silent
confidence, and to ask instead (`KnownMerchant.membershipConfusable`, surfaced through the existing
"Needs your input" card). An actual mandate/AutoPay signal for Swiggy One is still trusted fully —
that tier of evidence is unaffected.

**Would an on-device LLM help here instead of more regex?** No — and it's worth recording that
explicitly so a future session doesn't spend time trying it for this reason. The existing decision
against cloud LLMs for parsing (see the project's `no-cloud-llm-for-parsing` memory) is about
privacy and cost; it's a separate question from whether an LLM, on-device or not, would even solve
*this* class of problem. It wouldn't: an LLM reading `"...transaction with Bundl Tech for INR
1281.00..."` has exactly the same information a regex does — nothing that says "membership" or
"grocery order" — so it would face the identical ambiguity and could only resolve it by guessing
more fluently. An LLM is a plausible answer to *messy text segmentation* (a merchant name
buried in an oddly-formatted SMS); it is not an answer to information that was never transmitted.

## A separate, much bigger problem found while investigating this: fixes weren't reaching the phone

While tracing why Swiggy One was still visible *after* an earlier fix in the same session had
already addressed a related bug, it turned out that classification fixes had never once taken
effect on already-scanned data, for any merchant, ever:

- `WatchdogRepository.scanInbox()` only reads SMS newer than the last-scan cursor
  (`KEY_LAST_SCAN`) — it never revisits an SMS it has already parsed.
- `WatchdogDao.insertEvents` is `OnConflictStrategy.IGNORE`, keyed by `EventEntity.smsKey` — even
  if an old SMS *were* re-parsed, a corrected classification would be silently dropped in favour of
  the stale row already in the table.

The practical effect: every classification fix made this session before this one was discovered —
the movie/food/cab/fuel/telecom category exclusions, the fuzzy-matching length-gap fix — had
**zero observable effect on the user's real phone data**, because nothing ever told the app to
re-derive what it had already stored. This is arguably more consequential than any single parsing
rule, because it meant prior, genuinely-correct fixes were indistinguishable from not having been
made at all.

Fixed via `WatchdogRepository.reclassifyIfNeeded()` (a version-gated one-time full re-scan,
triggered automatically on the app update that introduced it) and a manual "Force full rescan"
button on the Profile page, for verifying a fix without waiting for the next release. See
`CLASSIFIER_VERSION` in `WatchdogRepository.kt` — bump it whenever a future change to
`SmsParser`/`MerchantCatalog` would classify already-scanned SMS differently.

## Is the Gmail analyzer's approach to extracting plan/renewal detail sound?

Asked directly in a follow-up: after connecting Gmail import, it never seemed to finish. Worth
being precise about what was actually wrong, because it was not a parsing-quality problem.

Gmail-sourced mail runs through the exact same `SmsParser`/`SubscriptionEngine` as SMS, just in
the stricter `fromEmail = true` mode (see `README.md`'s "Gmail import" section): the same
evidence gate (`readableAsPayment` — has to look like a receipt *and* show money actually moved),
the same DKIM-based merchant identity instead of the spoofable `From` header, the same
promotional/bulk-mail filtering, and the same `planDuration`/`autoRenews` extraction for reading a
renewal date out of "your 3-month plan" style receipts when no explicit date is stated. Every
protection documented above for SMS — the three-tier evidence ranking, `NonSubscriptionCatalog`,
`membershipConfusable` — applies identically here, because Gmail mail becomes the same
`PaymentEvent` types before the engine ever sees it. So the approach itself is sound and was not
what needed fixing.

What actually needed fixing was infrastructure, not extraction: live device logs showed the
backfill genuinely running, genuinely fetching and storing 22 real messages per slice — but every
slice re-fetched the *same* newest ~75 messages instead of ever walking further back, because the
frontier it tracks (`KEY_GMAIL_BACKFILL` in `SharedPreferences`) was being written with
`apply()` (asynchronous) from inside a background `WorkManager` job with no foreground service
keeping the process alive — so the write regularly lost the race against the OS killing the
process right after the job returned, and the next run started the 400-day window over from
scratch. Fixed by switching that write (and the two other Gmail-cursor writes on the same
background path) to `commit()` — synchronous, guaranteed durable before the job can return.

The lesson generalizes past this one bug: **the actual reliability problems in this codebase, both
times they've been investigated properly, turned out to be about whether a correct classification
or a correct fetch ever survives to be *used* — not about whether the classification or fetch
logic itself was wrong.** The retroactive-reclassification gap above is the same shape of problem
one layer up (a correct answer, silently never reaching the data) as this backfill race (a correct
fetch, silently never reaching disk). Worth checking for this pattern specifically — "is the
result of this operation actually durable/visible after the fact" — before assuming a user-visible
symptom means the parsing rules are wrong.

## What's still an open, deliberately deferred risk

`SmsParser.merchant()`'s SMS-path first check (`normalizer.findKnownIn(body)`) searches the
**entire SMS body** for a catalog alias as a substring, before any narrower merchant-phrase
extraction is tried. This means a catalog alias appearing anywhere in a long message — a
marketing cross-sell line, a footer — would match, even outside any payee-labeled region. This
was investigated for the Swiggy One case specifically and ruled out as the cause *this time* (the
actual messages shown didn't contain the substring "swiggyone" anywhere), but it remains a live,
unconfirmed risk in general, especially for brands that actively cross-sell a membership inside
unrelated transactional SMS. A narrower search — bounded to text near payment-relevant cue words,
the way `Extractors.merchantPhrase` already scopes itself — is the likely direction, but changing
it risks recall for legitimate terse bank SMS that only ever mention the merchant once, so it's
left for a dedicated session rather than bundled into an unrelated fix.

## Verdict

SMS-based subscription detection is genuinely useful and worth keeping — but the three tiers of
evidence it can produce are not equally trustworthy, and the app's job is to be honest about which
tier any given subscription actually rests on, not to smooth that over with a confident-looking
UI. Rule A (explicit mandate/AutoPay signals) is strong and close to deterministic. Rule B
(catalog identity) is only as good as its aliases and breaks for shared umbrella descriptors. Rule
C (bare pattern matching) is structurally the weakest and has caused every false positive found so
far — its job should be to *surface a candidate for confirmation*, not to *assert* a subscription,
whenever the merchant it names is one where that assertion can't actually be earned from the text.
That's the design this repository is converging on: `NonSubscriptionCatalog` and `autopayOnly` stop
rule C from firing at all on inherently one-off categories; `membershipConfusable` stops it from
firing confidently on merchants where the identity itself is ambiguous. Neither of those closes the
information-theoretic gap described above — nothing can — but both stop the app from pretending the
gap isn't there.
