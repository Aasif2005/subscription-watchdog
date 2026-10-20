package com.aasif.watchdog.core.merchant

import com.aasif.watchdog.core.model.Subscription

/**
 * The Android app behind each catalog merchant, for its logo: the installed app's own icon when it is on the phone,
 * otherwise the Play Store icon bundled as `brand_<id>` (see scripts/fetch-brand-icons.sh). Merchants with no app of
 * their own (a shared billing channel, a generic SIP or insurance premium) are absent and keep a letter tile.
 */
object BrandApps {
    val packages: Map<String, String> = mapOf(
        "netflix" to "com.netflix.mediaclient",
        "prime" to "com.amazon.avod.thirdpartyclient",
        "hotstar" to "in.startv.hotstar",
        "sonyliv" to "com.sonyliv",
        "zee5" to "com.graymatrix.did",
        "youtube" to "com.google.android.youtube",
        "appletv" to "com.apple.atve.androidtv.appletv",
        "aha" to "ahaflix.tv",
        "sunnxt" to "com.suntv.sunnxt",
        "mxplayer" to "com.mxtech.videoplayer.ad",
        "hoichoi" to "com.viewlift.hoichoi",
        "discovery" to "com.discovery.discoveryplus.mobile",
        "lionsgate" to "com.lionsgateplay.videoapp",
        "tataplay" to "com.ryzmedia.tatasky",
        "crunchyroll" to "com.crunchyroll.crunchyroid",
        "spotify" to "com.spotify.music",
        "jiosaavn" to "com.jio.media.jiobeats",
        "gaana" to "com.gaana",
        "wynk" to "com.bsbportal.music",
        "applemusic" to "com.apple.android.music",
        "audible" to "com.audible.application",
        "pocketfm" to "com.radio.pocketfm",
        "kukufm" to "com.vlv.aravali",
        "googleone" to "com.google.android.apps.subscriptions.red",
        "microsoft" to "com.microsoft.office.officehubrow",
        "adobe" to "com.adobe.reader",
        "canva" to "com.canva.editor",
        "chatgpt" to "com.openai.chatgpt",
        "claude" to "com.anthropic.claude",
        "linkedin" to "com.linkedin.android",
        "dropbox" to "com.dropbox.android",
        "notion" to "notion.id",
        "swiggyone" to "in.swiggy.android",
        "zomatogold" to "com.application.zomato",
        "blinkit" to "com.grofers.customerapp",
        "flipkartplus" to "com.flipkart.android",
        "myntrainsider" to "com.myntra.android",
        "jio" to "com.jio.myjio",
        "airtel" to "com.myairtelapp",
        "vi" to "com.mventus.selfcare.activity",
        "actfibernet" to "com.act.mobile.apps",
        "toiplus" to "com.toi.reader.activities",
        "thehindu" to "com.mobstac.thehindu",
        "kindle" to "com.amazon.kindle",
        "cultfit" to "fit.cure.android",
        "healthifyme" to "com.healthifyme.basic",
        "practo" to "com.practo.fabric",
        "duolingo" to "com.duolingo",
        "coursera" to "org.coursera.android",
        "unacademy" to "com.unacademyapp",
        "byjus" to "com.byjus.thelearningapp",
        "tinder" to "com.tinder",
        "bumble" to "com.bumble.app",
    )
}

private val brandNames = MerchantNormalizer()

/**
 * The catalog merchant whose logo this subscription wears, if any: its own merchant, or for one the user added by hand
 * (id "manual:..."), the known service named in what they typed. A shared billing charge has none until it is named.
 */
fun Subscription.brandId(): String? {
    if (sharedBilling) return null
    if (merchantId in BrandApps.packages) return merchantId
    if (merchantId.startsWith("manual:")) return brandNames.findKnownIn(name)?.id?.takeIf { it in BrandApps.packages }
    return null
}

/** The Android app package behind [brandId], if it has one. */
fun Subscription.brandPackage(): String? = brandId()?.let { BrandApps.packages[it] }

/**
 * Whether a Play Store search hit is really the service named [name], so its icon may be used. A matching title alone
 * only nominates a candidate (a "Gym" search finds a gym tracker, a clone copies a famous title); it is accepted only
 * when the squashed title equals the squashed name and that name is also a whole segment of the package id, which a
 * clone cannot take ("com.headspace.android" for Headspace).
 */
fun verifyPlayCandidate(name: String, pkg: String, title: String): Boolean {
    val wanted = MerchantNormalizer.squash(name)
    if (wanted.length < 4) return false
    val titleSquashed = MerchantNormalizer.squash(title.substringBefore(':').substringBefore(" - "))
    return titleSquashed == wanted && pkg.lowercase().split('.').any { MerchantNormalizer.squash(it) == wanted }
}
