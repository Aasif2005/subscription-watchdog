package com.aasif.watchdog.data.gmail

import android.os.SystemClock
import android.util.Log
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.core.model.Sms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

sealed interface GmailFetch {
    data class Ok(
        val messages: List<Sms>,
        /** Arrival time of the oldest message looked at, parsed or not; null if none were. */
        val oldestScannedMillis: Long?,
        /** True when the query had no further pages, so this window is fully covered. */
        val exhausted: Boolean,
        /** Gmail started throttling partway through, so this run stopped short of the cap. */
        val throttled: Boolean = false,
    ) : GmailFetch

    /** The token was rejected. Only the user can fix this, so the caller should stop quietly. */
    data object AuthRevoked : GmailFetch

    /** Quota or rate limit. Worth retrying later, and the sync cursor must not move. */
    data object RateLimited : GmailFetch

    data object Failed : GmailFetch
}

/**
 * The slice of the Gmail REST API this app needs, over `HttpURLConnection` — a few small calls
 * on a background worker do not justify a networking library.
 */
class GmailApiClient {

    suspend fun fetch(accessToken: String, query: String, maxMessages: Int): GmailFetch =
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val ids = mutableListOf<String>()
            var pageToken: String? = null
            do {
                coroutineContext.ensureActive()
                val url = buildString {
                    append(BASE).append("/messages?maxResults=").append(PAGE_SIZE)
                    append("&q=").append(URLEncoder.encode(query, "UTF-8"))
                    pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
                }
                when (val page = get(url, accessToken)) {
                    is Response.Ok -> {
                        val listed = page.body.optJSONArray("messages")
                        for (i in 0 until (listed?.length() ?: 0)) {
                            if (ids.size >= maxMessages) break
                            listed?.optJSONObject(i)?.optString("id")?.ifBlank { null }?.let(ids::add)
                        }
                        pageToken = page.body.optString("nextPageToken").ifBlank { null }
                    }
                    Response.Auth -> return@withContext GmailFetch.AuthRevoked
                    Response.Rate -> return@withContext GmailFetch.RateLimited
                    Response.Fail -> return@withContext GmailFetch.Failed
                }
            } while (pageToken != null && ids.size < maxMessages)

            val listedAt = SystemClock.elapsedRealtime()
            val messages = mutableListOf<Sms>()
            var oldestScanned: Long? = null
            var throttled = false
            // A few messages in flight at once: one at a time is dominated by round-trip latency,
            // and Gmail allows far more concurrency than this per mailbox.
            for (chunk in ids.chunked(CONCURRENCY)) {
                coroutineContext.ensureActive()
                val results = coroutineScope {
                    chunk.map { id -> async { get("$BASE/messages/$id?format=full", accessToken) } }.awaitAll()
                }
                // Read in list order and stop at the first throttled message: results after it are
                // discarded so the ones kept are an unbroken newest stretch. Keeping a later one
                // would let the backfill frontier step over the throttled message and lose it.
                for (message in results) {
                    when (message) {
                        is Response.Ok -> {
                            // Tracked for every message, not just the ones that parse, so the backfill
                            // frontier keeps moving even through a run of unrelated mail.
                            message.body.optString("internalDate").toLongOrNull()?.let {
                                oldestScanned = minOf(oldestScanned ?: it, it)
                            }
                            GmailMessageMapper.toSms(message.body)?.let(messages::add)
                        }
                        Response.Auth -> return@withContext GmailFetch.AuthRevoked
                        // Messages are read newest first, so the ones already in hand form a complete
                        // stretch of the window. Keeping them lets the caller bank the ground covered
                        // instead of re-reading it — and re-reading it is what exhausts the quota.
                        Response.Rate -> {
                            throttled = true
                            break
                        }
                        // One unreadable message should not throw away everything already fetched.
                        Response.Fail -> Unit
                    }
                }
                if (throttled) break
            }
            // Nothing was read, so there is no progress to report and nothing to bank.
            if (throttled && oldestScanned == null) return@withContext GmailFetch.RateLimited
            if (BuildConfig.DEBUG) {
                val done = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "q=[$query] listed=${ids.size} parsed=${messages.size} throttled=$throttled " +
                        "listMs=${listedAt - startedAt} getMs=${done - listedAt}",
                )
            }
            GmailFetch.Ok(
                messages, oldestScanned,
                // A throttled run stopped early, so it cannot claim the window is covered.
                exhausted = !throttled && pageToken == null && ids.size < maxMessages,
                throttled = throttled,
            )
        }

    /**
     * Tells Google to drop this token's grant, through the token endpoint. (`AuthorizationClient`
     * gained `revokeAccess` in play-services-auth 21.4.0; this predates that.) Disconnecting is
     * this one HTTP call plus clearing what the app remembers locally.
     * Best-effort: a failure here still leaves the app's own state cleared, which is what a user
     * pressing "Disconnect" actually cares about.
     */
    suspend fun revoke(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        val connection = (URL("$REVOKE_URL?token=${URLEncoder.encode(accessToken, "UTF-8")}").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val ok = connection.responseCode == HttpURLConnection.HTTP_OK
            runCatching { connection.inputStream.bufferedReader().use { it.readText() } }
            ok
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "revoke failed", e)
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, token: String): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        // Neither branch calls disconnect(): that drops the pooled socket, and a slice of a few
        // hundred messages would then pay for a fresh TLS handshake on every single one. Both
        // branches drain their stream instead, which is what returns the socket to the pool.
        return try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                Response.Ok(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
            } else {
                logFailure(url, code, connection)
                when (code) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> Response.Auth
                    // 403 is quota-exceeded as often as permission-denied; both want a later retry.
                    TOO_MANY_REQUESTS, HttpURLConnection.HTTP_FORBIDDEN -> Response.Rate
                    else -> Response.Fail
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "request failed: $url", e)
            connection.disconnect()
            Response.Fail
        }
    }

    /** Google explains the real reason in the error body, which is otherwise swallowed. */
    private fun logFailure(url: String, code: Int, connection: HttpURLConnection) {
        // Read even when it will not be logged: draining the error stream is what lets the
        // connection go back to the pool rather than being torn down.
        val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        if (BuildConfig.DEBUG) Log.w(TAG, "HTTP $code for $url\n${body?.take(ERROR_BODY_CHARS)}")
    }

    private sealed interface Response {
        data class Ok(val body: JSONObject) : Response
        data object Auth : Response
        data object Rate : Response
        data object Fail : Response
    }

    private companion object {
        const val TAG = "GmailSync"
        const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val PAGE_SIZE = 100

        /** Gets in flight at once; Gmail documents a per-mailbox concurrency ceiling far above this. */
        const val CONCURRENCY = 4
        const val TOO_MANY_REQUESTS = 429

        /** Google's quota errors carry a long help payload; the first lines say which limit. */
        const val ERROR_BODY_CHARS = 400
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
