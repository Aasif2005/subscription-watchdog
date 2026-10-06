package com.aasif.watchdog.data

/**
 * Where a stored event came from, written as `channel:origin` — `app:com.phonepe.app`,
 * `gmail:me@gmail.com|billing@netflix.com`.
 *
 * Deliberately not part of the duplicate rules: one charge seen both by SMS and by e-mail is
 * still one charge. This exists so the detail screen can say where a payment was spotted.
 */
object EventSource {
    const val SMS = "sms"
    const val APP = "app"
    const val GMAIL = "gmail"

    /** UPI and bank apps whose notifications are read, and the name to show for each. */
    val paymentApps = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "com.mobikwik_new" to "MobiKwik",
        "com.naviapp" to "Navi",
        "com.sbi.lotusintouch" to "SBI YONO",
        "com.csam.icici.bank.imobile" to "iMobile",
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.axis.mobile" to "Axis Mobile",
        "com.msf.kbank.mobile" to "Kotak",
    )

    fun of(channel: String, origin: String) = "$channel:$origin"

    /** Short label for the detail screen: "PhonePe", "Gmail (me@gmail.com)". */
    fun label(source: String): String {
        if (source.isBlank()) return "SMS"
        val channel = source.substringBefore(':')
        val origin = source.substringAfter(':', "")
        return when {
            channel == APP -> paymentApps[origin] ?: "payment app"
            // Tagged `account|sender` since mail from several accounts can be read at once.
            channel == GMAIL -> if ('|' in origin) "Gmail (${origin.substringBefore('|')})" else "Gmail"
            channel == SMS -> if (origin.isBlank()) "SMS" else "SMS ($origin)"
            // Rows stored before provenance existed keep the bare sender, recovered from smsKey.
            source in paymentApps -> paymentApps.getValue(source)
            "@" in source -> "Gmail"
            else -> "SMS ($source)"
        }
    }
}
