package io.github.lgpy.familycare.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.lgpy.familycare.core.data.auth.GoogleSignInNonce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Sign in with Google" button flow (D12).
 *
 * Credential Manager needs an Activity, which is why this lives in the app
 * module and not in core/data: the Supabase client stays platform-agnostic.
 *
 * [GetSignInWithGoogleOption] is used rather than `GetGoogleIdOption` because
 * the screen has an explicit button: this option shows the Google account
 * chooser sheet ("use another account" included), while `GetGoogleIdOption`
 * is designed for automatic/one-tap sign-in without a button.
 *
 * The Google request carries `sha256(rawNonce)`; the raw nonce only goes to
 * Supabase with the returned ID token, which is what its nonce check expects.
 */
class GoogleSignIn(private val webClientId: String) {

    suspend fun requestIdToken(activity: Activity): GoogleSignInOutcome = withContext(Dispatchers.Main) {
        val rawNonce = GoogleSignInNonce.generate()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(webClientId)
                    .setNonce(GoogleSignInNonce.hash(rawNonce))
                    .build(),
            )
            .build()

        try {
            val credential = CredentialManager.create(activity)
                .getCredential(context = activity, request = request)
                .credential

            if (credential is CustomCredential && credential.type in GOOGLE_ID_TOKEN_TYPES) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInOutcome.IdToken(
                    idToken = googleIdToken.idToken,
                    rawNonce = rawNonce,
                )
            } else {
                // A credential type this app did not ask for: nothing usable.
                GoogleSignInOutcome.Failed
            }
        } catch (_: GetCredentialCancellationException) {
            // The user dismissed the sheet: not an error, show nothing.
            GoogleSignInOutcome.Cancelled
        } catch (exception: GetCredentialException) {
            // No account, Play Services missing, provider misconfigured, ...
            // The exception message can name the account, so it is not surfaced
            // and not logged (security-and-privacy §1 T6).
            GoogleSignInOutcome.Failed
        } catch (_: Exception) {
            // e.g. GoogleIdTokenCredential.createFrom failing on a malformed
            // response. Treated like any other failure.
            GoogleSignInOutcome.Failed
        }
    }

    private companion object {
        /**
         * Both types carry a GoogleIdTokenCredential payload: the classic one
         * and the one issued by the Sign in with Google button flow.
         */
        val GOOGLE_ID_TOKEN_TYPES = setOf(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
        )
    }
}

/** What the Credential Manager sheet produced. */
sealed interface GoogleSignInOutcome {

    /** A Google ID token, with the raw nonce its hash was signed with. */
    data class IdToken(val idToken: String, val rawNonce: String) : GoogleSignInOutcome

    /** The user dismissed the sheet. No message is shown. */
    data object Cancelled : GoogleSignInOutcome

    /** No usable credential; the screen shows a localized error. */
    data object Failed : GoogleSignInOutcome
}
