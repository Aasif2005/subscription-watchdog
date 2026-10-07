package com.aasif.watchdog.data.gmail

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface GmailAuth {
    /** [accountEmail] is null when Play Services granted the token without saying which account. */
    data class Granted(val accessToken: String, val accountEmail: String?) : GmailAuth

    /** Play Services needs the user to approve the scope; only a foreground screen can show this. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : GmailAuth

    /** Declined, revoked, or Play Services unavailable. Nothing to do without the user. */
    data object Unavailable : GmailAuth
}

/**
 * Obtains Gmail read access through Play Services, which owns the consent record and refreshes
 * tokens itself. Nothing is persisted here on purpose: no access or refresh token is ever
 * written to disk by this app.
 *
 * "Disconnect" in [com.aasif.watchdog.data.WatchdogRepository] clears what this app remembers
 * locally and asks Google's token endpoint to drop the grant. Play-services-auth 21.4.0 added
 * `AuthorizationClient.revokeAccess`, which could replace that HTTP call; 21.3.0, which this was
 * first written against, had no revoke method.
 */
class GmailAuthManager(private val context: Context) {
    private val client get() = Identity.getAuthorizationClient(context)

    private fun request(account: Account? = null) =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_READONLY)))
            .apply { account?.let { setAccount(it) } }
            .build()

    /**
     * Asks for a token. Returns [GmailAuth.NeedsConsent] instead of throwing when the user has to
     * approve it, so a background worker can give up quietly and the UI can pick it up later.
     */
    suspend fun authorize(): GmailAuth = authorize(request())

    /** Same as [authorize], but scoped to one Google account — how "switch account" asks for a token. */
    suspend fun authorize(account: Account): GmailAuth = authorize(request(account))

    private suspend fun authorize(req: AuthorizationRequest): GmailAuth = try {
        toGmailAuth(client.authorize(req).await())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        GmailAuth.Unavailable
    }

    /**
     * Drops a token Gmail rejected from Play Services' cache. Without this, [authorize] keeps
     * handing back the same dead access token until it expires on its own.
     */
    suspend fun clearToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the retry that follows simply fails the same way and asks for consent.
        }
    }

    /** Reads the token back out of the consent screen's result. */
    fun fromConsentResult(data: Intent?): GmailAuth = data?.let {
        runCatching { toGmailAuth(client.getAuthorizationResultFromIntent(it)) }.getOrNull()
    } ?: GmailAuth.Unavailable

    private fun toGmailAuth(result: AuthorizationResult): GmailAuth {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return GmailAuth.NeedsConsent(pending)
        val token = result.accessToken ?: return GmailAuth.Unavailable
        val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
        return GmailAuth.Granted(token, email)
    }

    /** The system's "choose a Google account" screen, for switching which inbox is read. */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT_TYPE), null, null, null, null)

    fun account(name: String) = Account(name, GOOGLE_ACCOUNT_TYPE)

    private companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
