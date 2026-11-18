package io.github.lgpy.familycare.core.domain.push

/**
 * This install's FCM registration token on the server (D27).
 *
 * The server keeps one `core.push_devices` row per app install and pushes
 * escalations and clear messages to it (M4). A token identifies an **install**,
 * not a person: signing in with another account moves the row, which is why
 * registration is an upsert RPC rather than an insert.
 *
 * Two rules the caller must keep:
 * - the token is personal data (security-and-privacy.md); never log it, and
 *   never put it in an exception message shown to the user;
 * - unregistering only removes *this* user's row, and is best effort: an
 *   unregistered (or deleted) token stops receiving pushes anyway, and the
 *   server deletes a token FCM reports dead.
 *
 * Both calls need a connection; an offline failure is worth retrying later, not
 * showing as an error.
 */
interface PushDeviceRepository {

    /** `core.register_push_device(token)`: upsert this install's token for the caller. */
    suspend fun register(token: String): Result<Unit>

    /** `core.unregister_push_device(token)`: delete the caller's row, if any. */
    suspend fun unregister(token: String): Result<Unit>
}
