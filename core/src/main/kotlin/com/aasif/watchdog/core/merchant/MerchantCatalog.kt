package com.aasif.watchdog.core.merchant

data class KnownMerchant(
    val id: String,
    val name: String,
    val category: String,
    /**
     * Spellings seen in bank SMS, card descriptors and UPI handles. Matched against text that has
     * been lowercased with everything except letters and digits removed, so "NETFLIX.COM" and
     * "netflix@icici" both contain "netflix". Keep them specific: "google" alone would also match
     * "Google Pay", which is a payment app, not a subscription.
     */
    val aliases: List<String>,
    /** Typical free-trial length, used when a trial mandate does not state its first debit date. */
    val trialDays: Int? = null,
    val cancelUrl: String? = null,
    /**
     * True for a billing channel that charges on behalf of many different apps (Google Play, the
     * App Store). A bank text can only name the channel, never the app behind it, so what the user
     * is paying for has to come from mail or from the user.
     */
    val sharedBilling: Boolean = false,
    /**
     * True for a merchant whose typical India purchase is a one-off top-up (a prepaid mobile
     * recharge), not a plan — most Jio/Airtel/Vi customers are prepaid, and a recharge repeats on
     * a schedule as a matter of habit, not because it auto-renews. Being in this catalog is still
     * useful for naming the merchant correctly, but [engine.RecurrenceDetector]'s amount+timing
     * pattern match must not trust that alone the way it trusts an OTT or cloud plan; only an
     * explicit mandate/AutoPay signal on the debit ([model.PaymentEvent.Debit.viaMandate]) or an
     * actual mandate-registration SMS (a [model.PaymentEvent.MandateCreated] event) proves it.
     */
    val autopayOnly: Boolean = false,
    /**
     * True for a catalog entry whose payment descriptor is shared with a non-subscription sibling
     * product under the same umbrella brand or legal entity — a Swiggy Instamart grocery order is
     * billed by the same "Bundl Technologies" descriptor as a Swiggy One membership charge; a
     * one-off Kindle book or device purchase shares the bare word "kindle" with Kindle Unlimited.
     * Amount and timing are all a bank SMS ever carries, and that is not enough to tell these
     * apart, so [engine.SubscriptionEngine] does not let a bare pattern match promote one of these
     * straight to a confident subscription — it is surfaced as "Needs your input" instead, same as
     * an amount or frequency the app never saw stated. An explicit mandate/AutoPay signal
     * ([model.PaymentEvent.Debit.viaMandate] or an actual [model.PaymentEvent.MandateCreated]) is
     * still trusted, since that is real evidence the text does contain.
     */
    val membershipConfusable: Boolean = false,
)

/** Subscriptions commonly paid by UPI AutoPay or card e-mandate in India. */
object MerchantCatalog {
    private const val OTT = "Video streaming"
    private const val MUSIC = "Music"
    private const val CLOUD = "Cloud & software"
    private const val FOOD = "Food & delivery"
    private const val TELECOM = "Telecom & internet"
    private const val NEWS = "News & reading"
    private const val FITNESS = "Health & fitness"
    private const val LEARNING = "Learning"
    private const val SHOPPING = "Shopping memberships"
    private const val DATING = "Dating"
    private const val FINANCE = "Finance"

    val all: List<KnownMerchant> = listOf(
        // Video streaming
        KnownMerchant("netflix", "Netflix", OTT, listOf("netflix"), cancelUrl = "https://www.netflix.com/cancelplan"),
        KnownMerchant("prime", "Amazon Prime", OTT, listOf("amazonprime", "primevideo", "primemembership", "amznprime"), trialDays = 30, cancelUrl = "https://www.amazon.in/mc", membershipConfusable = true),
        KnownMerchant("hotstar", "JioHotstar", OTT, listOf("jiohotstar", "hotstar", "disneyplus", "novidigital"), cancelUrl = "https://www.hotstar.com/in/mygst"),
        KnownMerchant("sonyliv", "SonyLIV", OTT, listOf("sonyliv", "culvermax"), cancelUrl = "https://www.sonyliv.com/myaccount"),
        KnownMerchant("zee5", "ZEE5", OTT, listOf("zee5"), cancelUrl = "https://www.zee5.com/myaccount/subscription"),
        KnownMerchant("youtube", "YouTube Premium", OTT, listOf("youtubepremium", "youtube", "googleyoutube"), trialDays = 30, cancelUrl = "https://www.youtube.com/paid_memberships"),
        KnownMerchant("appletv", "Apple TV+", OTT, listOf("appletv"), trialDays = 7),
        KnownMerchant("aha", "aha", OTT, listOf("ahavideo", "arhamedia")),
        KnownMerchant("sunnxt", "Sun NXT", OTT, listOf("sunnxt")),
        KnownMerchant("mxplayer", "MX Player", OTT, listOf("mxplayer")),
        KnownMerchant("altbalaji", "ALTT", OTT, listOf("altbalaji", "alttapp")),
        KnownMerchant("hoichoi", "hoichoi", OTT, listOf("hoichoi")),
        KnownMerchant("discovery", "discovery+", OTT, listOf("discoveryplus")),
        KnownMerchant("lionsgate", "Lionsgate Play", OTT, listOf("lionsgateplay")),
        KnownMerchant("tataplay", "Tata Play", OTT, listOf("tataplay", "tatasky")),
        KnownMerchant("crunchyroll", "Crunchyroll", OTT, listOf("crunchyroll"), trialDays = 7),

        // Music
        KnownMerchant("spotify", "Spotify", MUSIC, listOf("spotify"), trialDays = 30, cancelUrl = "https://www.spotify.com/account/subscription/"),
        KnownMerchant("jiosaavn", "JioSaavn Pro", MUSIC, listOf("jiosaavn", "saavn")),
        KnownMerchant("gaana", "Gaana Plus", MUSIC, listOf("gaana")),
        KnownMerchant("wynk", "Wynk Music", MUSIC, listOf("wynk")),
        KnownMerchant("applemusic", "Apple Music", MUSIC, listOf("applemusic"), trialDays = 30),
        KnownMerchant("audible", "Audible", MUSIC, listOf("audible"), trialDays = 30),
        KnownMerchant("pocketfm", "Pocket FM", MUSIC, listOf("pocketfm")),
        KnownMerchant("kukufm", "Kuku FM", MUSIC, listOf("kukufm")),

        // Cloud & software
        KnownMerchant("apple", "Apple (App Store / iCloud)", CLOUD, listOf("applecombill", "itunes", "appleservices", "applemedia", "icloud"), cancelUrl = "https://support.apple.com/en-in/118428", sharedBilling = true),
        KnownMerchant("googleone", "Google One", CLOUD, listOf("googleone", "googlestorage"), cancelUrl = "https://one.google.com/settings"),
        KnownMerchant("googleplay", "Google Play subscription", CLOUD, listOf("googleplay", "playstore", "googleservices"), cancelUrl = "https://play.google.com/store/account/subscriptions", sharedBilling = true),
        KnownMerchant("microsoft", "Microsoft 365", CLOUD, listOf("microsoft", "msbill", "office365", "xboxgamepass"), trialDays = 30),
        KnownMerchant("adobe", "Adobe", CLOUD, listOf("adobe"), trialDays = 7),
        KnownMerchant("canva", "Canva Pro", CLOUD, listOf("canva"), trialDays = 30),
        KnownMerchant("chatgpt", "ChatGPT", CLOUD, listOf("openai", "chatgpt")),
        KnownMerchant("claude", "Claude", CLOUD, listOf("anthropic", "claudeai")),
        KnownMerchant("linkedin", "LinkedIn Premium", CLOUD, listOf("linkedin"), trialDays = 30),
        KnownMerchant("dropbox", "Dropbox", CLOUD, listOf("dropbox")),
        KnownMerchant("notion", "Notion", CLOUD, listOf("notionlabs", "notionso")),

        // Food & delivery. Swiggy One's charge and a plain Swiggy/Instamart order are both billed
        // by the same umbrella descriptor ("Bundl Technologies"); see [KnownMerchant.membershipConfusable].
        KnownMerchant("swiggyone", "Swiggy One", FOOD, listOf("swiggyone"), cancelUrl = "https://www.swiggy.com/one", membershipConfusable = true),
        KnownMerchant("zomatogold", "Zomato Gold", FOOD, listOf("zomatogold"), membershipConfusable = true),
        KnownMerchant("blinkit", "Blinkit", FOOD, listOf("blinkitplus")),

        // Shopping memberships
        KnownMerchant("flipkartplus", "Flipkart Plus / VIP", SHOPPING, listOf("flipkartvip", "flipkartplus"), membershipConfusable = true),
        KnownMerchant("myntrainsider", "Myntra Insider", SHOPPING, listOf("myntrainsider")),

        // Telecom & internet. Mobile plans are overwhelmingly prepaid in India, so a recharge here
        // proves nothing about autopay on its own — see [KnownMerchant.autopayOnly]. Fixed
        // broadband (ACT Fibernet) is ordinarily a postpaid monthly bill, so it keeps the default.
        KnownMerchant("jio", "Jio", TELECOM, listOf("reliancejio", "jiofiber", "jioairfiber", "jiopostpaid", "myjio"), autopayOnly = true),
        KnownMerchant("airtel", "Airtel", TELECOM, listOf("airtelxstream", "airtelpostpaid", "airtelbroadband", "airtelblack", "bhartiairtel"), autopayOnly = true),
        KnownMerchant("vi", "Vi (Vodafone Idea)", TELECOM, listOf("vodafoneidea", "vodaidea", "myvi"), autopayOnly = true),
        KnownMerchant("actfibernet", "ACT Fibernet", TELECOM, listOf("actfibernet", "atriaconvergence")),

        // News & reading
        KnownMerchant("toiplus", "TOI+", NEWS, listOf("toiplus", "timesprime", "timesinternet")),
        KnownMerchant("thehindu", "The Hindu", NEWS, listOf("thehindu")),
        // "kindle" is a direct alias (not just a fuzzy typo match), so a one-off Kindle purchase
        // hits it exactly; see [KnownMerchant.membershipConfusable].
        KnownMerchant("kindle", "Kindle Unlimited", NEWS, listOf("kindleunlimited", "kindle"), trialDays = 30, membershipConfusable = true),

        // Health & fitness
        KnownMerchant("cultfit", "cult.fit", FITNESS, listOf("cultfit", "curefit")),
        KnownMerchant("healthifyme", "HealthifyMe", FITNESS, listOf("healthifyme")),
        KnownMerchant("practo", "Practo Plus", FITNESS, listOf("practo")),

        // Learning
        KnownMerchant("duolingo", "Duolingo", LEARNING, listOf("duolingo"), trialDays = 14),
        KnownMerchant("coursera", "Coursera", LEARNING, listOf("coursera"), trialDays = 7),
        KnownMerchant("unacademy", "Unacademy", LEARNING, listOf("unacademy", "sortinghat")),
        KnownMerchant("byjus", "BYJU'S", LEARNING, listOf("byjus", "thinkandlearn")),

        // Dating
        KnownMerchant("tinder", "Tinder", DATING, listOf("tinder")),
        KnownMerchant("bumble", "Bumble", DATING, listOf("bumble")),

        // Finance
        KnownMerchant("sip", "Mutual fund SIP", FINANCE, listOf("bsestarmf", "nsemfss", "mfutility", "camsonline", "kfintech", "zerodhacoin")),
        KnownMerchant("insurance", "Insurance premium", FINANCE, listOf("licofindia", "lifeinsurancecorp", "hdfclife", "iciciprulife", "sbilife", "policybazaar")),
    )

    val byId: Map<String, KnownMerchant> = all.associateBy { it.id }
}
