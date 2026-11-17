package io.github.lgpy.familycare.core.ui.lock

/**
 * Lock state and re-lock policy for the mandatory app lock (D12).
 *
 * Deliberately pure Kotlin with an injected monotonic time source, so the
 * 60-second background rule can be unit tested without Android:
 *
 * - the process starts locked (cold start is always locked);
 * - a failed or cancelled authentication never unlocks;
 * - only returning to the foreground after more than
 *   [DEFAULT_BACKGROUND_TIMEOUT_MILLIS] in the background re-locks.
 *
 * [AppLockController] owns the Android process-lifecycle callbacks and calls
 * into this class.
 */
class AppLockPolicy(
    private val elapsedRealtime: () -> Long,
    private val backgroundTimeoutMillis: Long = DEFAULT_BACKGROUND_TIMEOUT_MILLIS,
) {
    private var locked = true
    private var backgroundedAt: Long? = null

    fun isLocked(): Boolean = locked

    fun onAppForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (locked) return
        if (elapsedRealtime() - since > backgroundTimeoutMillis) {
            locked = true
        }
    }

    fun onAppBackgrounded() {
        backgroundedAt = elapsedRealtime()
    }

    fun onAuthenticationSucceeded() {
        locked = false
        backgroundedAt = null
    }

    /**
     * A failed or cancelled prompt is a no-op on purpose: the app stays
     * locked until a successful authentication.
     */
    fun onAuthenticationFailed() = Unit

    companion object {
        const val DEFAULT_BACKGROUND_TIMEOUT_MILLIS: Long = 60_000L
    }
}
