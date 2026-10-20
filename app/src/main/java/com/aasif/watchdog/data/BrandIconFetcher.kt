package com.aasif.watchdog.data

import com.aasif.watchdog.core.merchant.verifyPlayCandidate
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds the Play Store icon of a service the catalog doesn't know, by its name alone. Only used when the user turned on
 * "Find logos online" in Profile; the only thing sent is that name (and the package ids Play itself returned). A search
 * hit is used only once [verifyPlayCandidate] accepts its listing, so a lookalike app's logo is never shown or kept.
 */
object BrandIconFetcher {
    private const val PLAY = "https://play.google.com/store"
    private val packageId = Regex("""details\?id=([A-Za-z0-9_.]+)""")
    private val ogTitle = Regex("""<meta property="og:title" content="([^"]*)"""")
    private val ogImage = Regex("""<meta property="og:image" content="([^"]*)"""")

    /** The 144 px WebP icon bytes, or null when no verified app was found or the network failed. */
    fun fetch(name: String): ByteArray? = runCatching {
        val search = get("$PLAY/search?q=${URLEncoder.encode(name, "UTF-8")}&c=apps&hl=en_IN").decodeToString()
        // Play lists the closest matches first; a verified app further down is almost always a different one.
        for (pkg in packageId.findAll(search).map { it.groupValues[1] }.distinct().take(3)) {
            val listing = get("$PLAY/apps/details?id=$pkg&hl=en_IN").decodeToString()
            val title = ogTitle.find(listing)?.groupValues?.get(1)?.let(::unescape)?.substringBefore(" – Apps on Google Play") ?: continue
            if (!verifyPlayCandidate(name, pkg, title)) continue
            val image = ogImage.find(listing)?.groupValues?.get(1)?.substringBefore('=') ?: return@runCatching null
            return@runCatching get("$image=s144-rw")
        }
        null
    }.getOrNull()

    private fun get(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}
