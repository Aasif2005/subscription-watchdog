# Subscription Watchdog

Android app that reads bank / UPI payment SMS (and, if allowed, payment alerts from UPI and bank app notifications) **on the device** to find every recurring charge (UPI AutoPay, card e-mandates, OTT and app subscriptions, free trials that turn paid), shows what they cost per month, and warns before each renewal.

Subscriptions that only ever send an e-mail receipt are invisible to all of that, so there is also an **opt-in [Gmail import](#gmail-import-optional-uses-the-internet)** — the one part of the app that uses the network.

## How the renewal date is found

Each SMS is classified as one of four kinds (`core/.../parser/SmsClassifier.kt`), and the renewal date comes from the strongest signal available (`core/.../engine/SubscriptionEngine.kt`):

| Priority | Signal | Example SMS | What it gives |
|---|---|---|---|
| 1 | **Pre-debit notice** (RBI requires one ≥24h before each mandate debit) | "Rs 199 will be debited on 12-10-2026 towards Spotify AutoPay" | Exact date |
| 2 | **Mandate registration** | "UPI-Mandate for Rs.649 created towards NETFLIX. Frequency: Monthly, Start date: 05/09/26" | Start date + frequency → every future date |
| 3 | **Debit history** | "Spent INR 649.00 … NETFLIX.COM" on 05 Jul, 05 Aug, 05 Sep | Interval between charges → next date |
| 4 | **Trial estimate** | Mandate with ₹1 verification and no date | Typical trial length from the merchant catalog |

Other details:
- Merchant names are normalised ("NETFLIX.COM", "razorpay.netflix@icici", "NETFLX ENT" all become Netflix) using the catalog in `MerchantCatalog.kt` plus fuzzy matching.
- Monthly plans keep their billing day: a 31 Jan charge renews on 28 Feb and then on 31 Mar.
- A revoked mandate marks the subscription cancelled. No charge for two billing periods marks it lapsed.
- Unknown payees need 3 regular monthly (or longer) charges before they count, so weekly grocery runs don't show up as subscriptions.

## Project layout

```
core/   Pure Kotlin: SMS parser, merchant catalog, recurrence detection, renewal prediction,
        HTML-to-text, Gmail query building, duplicate rules. No Android dependency.
app/    Android (Kotlin + Jetpack Compose): SMS reading, notification listener, Gmail sync,
        Room storage, WorkManager reminders, UI.
```

Everything that can be tested without a device lives in `core/`, which is why the Gmail parsing rules are there and only the network and OAuth glue is in `app/`.

## Build

Requirements: JDK 17+ and an Android SDK (platform 37).

```sh
./gradlew :core:test          # parser + engine tests; no Android SDK needed
./gradlew :app:assembleDebug  # needs ANDROID_HOME or sdk.dir in local.properties
```

If no Android SDK is configured, `settings.gradle.kts` skips `:app` so the core tests still run.

### Try it on an emulator

Debug builds accept SMS from ordinary phone numbers, so you can inject test messages:

```sh
adb emu sms send 5556 "Rs 649.00 debited from A/c XX1234 on 05-09-26 to NETFLIX via UPI AutoPay"
adb emu sms send 5556 "Rs.199.00 will be debited from your A/c XX1234 on <tomorrow dd-mm-yyyy> towards Spotify AutoPay mandate"
```

Release builds ignore SMS from regular 10-digit phone numbers, so a forwarded or spoofed message cannot create a subscription.

To test the notification path, grant access and post as the shell (debug builds accept it):

```sh
adb shell cmd notification allow_listener com.aasif.watchdog/com.aasif.watchdog.data.notification.PaymentNotificationListener
adb shell cmd notification post -S bigtext -t "'UPI AutoPay reminder'" t1 "'Rs 149 will be debited on <dd-mm-yy> towards YouTube Premium AutoPay mandate'"
```

## Notification alerts

Some banks send the AutoPay pre-debit notice only inside the UPI app. With notification access, `PaymentNotificationListener` reads notifications from an allowlist of UPI and bank apps (GPay, PhonePe, Paytm, BHIM, CRED, major banks) and runs them through the same parser. Notifications from every other app are dropped without being read. UPI apps also push marketing ("set up AutoPay, get ₹50 cashback"), so notifications that look promotional are skipped (`SmsClassifier.isPromotional`). An alert and the SMS for the same charge don't double-count: identical debits are merged, and the earliest pre-debit date wins.

## Gmail import (optional, uses the internet)

Card subscriptions and services that only e-mail a receipt never produce a payment SMS, so nothing on the device can see them. Gmail import is the answer to that, and it is the **one** feature that leaves the phone.

It is off until the user turns it on from the dashboard card, which explains what happens before Google's consent screen appears. The scope requested is `gmail.readonly` and nothing else. Play Services owns the consent and refreshes the token, so **this app never stores an access or refresh token**; only "import is on" and a sync cursor are saved.

A sync asks Gmail for mail matching a payment-word query (`GmailQueryBuilder`), flattens each body to plain text (`HtmlText`) and runs it through the same `SmsClassifier`/`SmsParser` as SMS. As with SMS, only merchant, amount, dates and frequency are stored — the e-mail text is never kept. Merchant names are deliberately kept out of the search query, and Promotions are deliberately *not* excluded, because Gmail files some genuine billing mail there; marketing is dropped later by `isPromotional`.

Mail is read more strictly than SMS (`SmsParser`'s `fromEmail`). A bank's DLT-header SMS is terse, so the cue words that find a payee in it are reliable; an e-mail runs to thousands of characters and ends in a marketing footer, where the same cues match ordinary prose. So mail has to name a service the catalog knows or say outright that the payment recurs, and its merchant is read from the sending domain rather than from the body. Without that, a bank's own transaction alerts arrived as a recurring charge called "Idfcfirst Bank Instead" and a NordVPN receipt was filed under "Monitor".

History is covered in slices, newest first, because Gmail lists newest first and a capped run only ever sees the newest part of a window. Each slice reads 75 messages and moves a saved frontier back to the oldest message it read; the next slice follows about a minute later. Both the size and the pause come from Gmail's per-minute quota, which stops a full-format read at roughly a hundred messages. A slice that is throttled part-way keeps what it already read and banks that ground, and only a slice that read something counts against the slice budget — otherwise a spell of throttling spends the whole budget and ends the backfill having imported nothing.

A charge that arrives by SMS, by app notification and by e-mail is stored once: `isLikelyDuplicateOf` (in `core/.../engine/EventDedup.kt`) compares merchant, date and amount and ignores which source it came from.

## Where a payment was spotted

Each charge on the detail screen shows its origin and arrival time — "via PhonePe", "via Gmail", "via SMS (VM-HDFCBK)". Charges recorded before this was added show the date only, since the arrival time was never stored.

## Privacy

- SMS and notifications are parsed on the device and never leave it. Only the merchant, amount, dates and frequency are stored; the message text is never kept.
- Cloud backup and device-to-device transfer are disabled (`data_extraction_rules.xml`).
- Notification access is optional. Only allowlisted payment apps are read, and like SMS, only the extracted fields are stored.
- The network is used by Gmail import and nothing else. With it switched off, the app makes no requests at all; `INTERNET` is declared solely for that opt-in feature.

## Before publishing to Play Store

- `READ_SMS` / `RECEIVE_SMS` are restricted permissions. Submit the **Permissions Declaration Form** under the "SMS-based money management" exception, with a demo video.
- Publish a privacy policy that states SMS and notifications are processed on-device only, and that Gmail import is opt-in and network-based.
- Notification access needs a prominent in-app disclosure before the user is sent to Settings (Play User Data policy). The dashboard card explains it; review the wording against the policy.
- `gmail.readonly` is a restricted scope. While the OAuth consent screen stays in **Testing** with the developer as the only test user, no Google review is needed — but the app cannot be shared with anyone else until it passes OAuth verification and a CASA security assessment. Budget for that before any public release.
- The OAuth client is Android-type, matched by package name and signing certificate, so a release build signed with a different key needs its SHA-1 added in the Google Cloud console.
- Replace the placeholder launcher icon with an adaptive icon.

## Roadmap (premium)

- Step-by-step cancellation guides per merchant (`cancelUrl` in the catalog is the start).
- Yearly savings report (`List<Subscription>.yearlySavings()` already computes the number).
- Optional encrypted cloud backup, and Play Billing.
