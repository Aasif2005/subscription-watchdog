package com.aasif.watchdog.data.gmail

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface GmailAuth {
    data class Granted(val accessToken: String) : GmailAuth

    /** Play Services needs the user to approve the scope; only a foreground screen can show this. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : GmailAuth

    /** Declined, revoked, or Play Services unavailable. Nothing to do without the user. */
    data object Unavailable : GmailAuth
}

/**
 * Obtains Gmail read access through Play Services, which owns the consent record and refreshes
 * tokens itself. Nothing is persisted here on purpose: no access or refresh token is ever
 * written to disk by this app.
 */
class GmailAuthManager(private val context: Context) {
    private val client get() = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_READONLY))).build()

    /**
     * Asks for a token. Returns [GmailAuth.NeedsConsent] instead of throwing when the user has to
     * approve it, so a background worker can give up quietly and the UI can pick it up later.
     */
    suspend fun authorize(): GmailAuth = try {
        val result = client.authorize(request).await()
        val pending = result.pendingIntent
        when {
            result.hasResolution() && pending != null -> GmailAuth.NeedsConsent(pending)
            else -> result.accessToken?.let(GmailAuth::Granted) ?: GmailAuth.Unavailable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        GmailAuth.Unavailable
    }

    /** Reads the token back out of the consent screen's result. */
    fun tokenFrom(data: Intent?): String? = data?.let {
        runCatching { client.getAuthorizationResultFromIntent(it).accessToken }.getOrNull()
    }

    private companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
