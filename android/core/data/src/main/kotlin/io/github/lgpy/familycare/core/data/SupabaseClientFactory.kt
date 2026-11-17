package io.github.lgpy.familycare.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Creates the single Supabase client for the app.
 *
 * The URL and the publishable key come from the build environment (see
 * android/environments/dev.properties and the app module's BuildConfig).
 * They are public identifiers: RLS policies, not secrecy, protect the data.
 */
object SupabaseClientFactory {

    fun create(supabaseUrl: String, publishableKey: String): SupabaseClient {
        require(supabaseUrl.isNotBlank()) { "A Supabase URL is required" }
        require(publishableKey.isNotBlank()) { "A Supabase publishable key is required" }

        return createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = publishableKey,
        ) {
            install(Auth)
            // Installed although M1 does not read tables yet, so the PowerSync
            // task extends this client instead of replacing it.
            install(Postgrest)
        }
    }
}
