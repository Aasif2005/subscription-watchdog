# Subscription Watchdog

Android app that reads bank / UPI payment SMS **on the device** to find every recurring charge (UPI AutoPay, card e-mandates, OTT and app subscriptions, free trials that turn paid), shows what they cost per month, and warns before each renewal.

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
core/   Pure Kotlin: SMS parser, merchant catalog, recurrence detection, renewal prediction. No Android dependency.
app/    Android (Kotlin + Jetpack Compose): SMS reading, Room storage, WorkManager reminders, UI.
```

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

## Privacy

- SMS are parsed on the device. Only the merchant, amount, dates and frequency are stored; the SMS text is never kept.
- Cloud backup and device-to-device transfer are disabled (`data_extraction_rules.xml`).
- There is no network permission.

## Before publishing to Play Store

- `READ_SMS` / `RECEIVE_SMS` are restricted permissions. Submit the **Permissions Declaration Form** under the "SMS-based money management" exception, with a demo video.
- Publish a privacy policy that states SMS are processed on-device only.
- Replace the placeholder launcher icon with an adaptive icon.

## Roadmap (premium)

- Step-by-step cancellation guides per merchant (`cancelUrl` in the catalog is the start).
- Yearly savings report (`List<Subscription>.yearlySavings()` already computes the number).
- Optional encrypted cloud backup, and Play Billing.
