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
        KnownMerchant("prime", "Amazon Prime", OTT, listOf("amazonprime", "primevideo", "primemembership", "amznprime"), trialDays = 30, cancelUrl = "https://www.amazon.in/mc"),
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
        KnownMerchant("apple", "Apple (App Store / iCloud)", CLOUD, listOf("applecombill", "itunes", "appleservices", "applemedia", "icloud"), cancelUrl = "https://support.apple.com/en-in/118428"),
        KnownMerchant("googleone", "Google One", CLOUD, listOf("googleone", "googlestorage"), cancelUrl = "https://one.google.com/settings"),
        KnownMerchant("googleplay", "Google Play subscription", CLOUD, listOf("googleplay", "playstore", "googleservices"), cancelUrl = "https://play.google.com/store/account/subscriptions"),
        KnownMerchant("microsoft", "Microsoft 365", CLOUD, listOf("microsoft", "msbill", "office365", "xboxgamepass"), trialDays = 30),
        KnownMerchant("adobe", "Adobe", CLOUD, listOf("adobe"), trialDays = 7),
        KnownMerchant("canva", "Canva Pro", CLOUD, listOf("canva"), trialDays = 30),
        KnownMerchant("chatgpt", "ChatGPT Plus", CLOUD, listOf("openai", "chatgpt")),
        KnownMerchant("claude", "Claude", CLOUD, listOf("anthropic", "claudeai")),
        KnownMerchant("linkedin", "LinkedIn Premium", CLOUD, listOf("linkedin"), trialDays = 30),
        KnownMerchant("dropbox", "Dropbox", CLOUD, listOf("dropbox")),
        KnownMerchant("notion", "Notion", CLOUD, listOf("notionlabs", "notionso")),

        // Food & delivery
        KnownMerchant("swiggyone", "Swiggy One", FOOD, listOf("swiggyone"), cancelUrl = "https://www.swiggy.com/one"),
        KnownMerchant("zomatogold", "Zomato Gold", FOOD, listOf("zomatogold")),
        KnownMerchant("blinkit", "Blinkit", FOOD, listOf("blinkitplus")),

        // Shopping memberships
        KnownMerchant("flipkartplus", "Flipkart Plus / VIP", SHOPPING, listOf("flipkartvip", "flipkartplus")),
        KnownMerchant("myntrainsider", "Myntra Insider", SHOPPING, listOf("myntrainsider")),

        // Telecom & internet
        KnownMerchant("jio", "Jio", TELECOM, listOf("reliancejio", "jiofiber", "jioairfiber", "jiopostpaid", "myjio")),
        KnownMerchant("airtel", "Airtel", TELECOM, listOf("airtelxstream", "airtelpostpaid", "airtelbroadband", "airtelblack", "bhartiairtel")),
        KnownMerchant("vi", "Vi (Vodafone Idea)", TELECOM, listOf("vodafoneidea", "vodaidea", "myvi")),
        KnownMerchant("actfibernet", "ACT Fibernet", TELECOM, listOf("actfibernet", "atriaconvergence")),

        // News & reading
        KnownMerchant("toiplus", "TOI+", NEWS, listOf("toiplus", "timesprime", "timesinternet")),
        KnownMerchant("thehindu", "The Hindu", NEWS, listOf("thehindu")),
        KnownMerchant("kindle", "Kindle Unlimited", NEWS, listOf("kindleunlimited", "kindle"), trialDays = 30),

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
