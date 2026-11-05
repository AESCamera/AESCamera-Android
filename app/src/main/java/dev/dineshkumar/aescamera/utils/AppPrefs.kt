package dev.dineshkumar.aescamera.utils

import android.content.Context
import androidx.core.content.edit

/** Where the vault directory tree lives; see [VaultStorage]. */
enum class VaultLocation {
    /** The app's internal files directory: readable only by this app (or root). */
    PRIVATE,

    /** Android/media/<package>/AES Camera on shared storage: visible to file managers. */
    SHARED
}

/** The few user settings the app has, in a plain SharedPreferences file. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Null until decided (first run) — [VaultStorage.location] resolves and stores it. */
    var vaultLocation: VaultLocation?
        get() = prefs.getString(KEY_VAULT_LOCATION, null)?.let { stored ->
            runCatching { VaultLocation.valueOf(stored) }.getOrNull()
        }
        set(value) {
            prefs.edit { putString(KEY_VAULT_LOCATION, value?.name) }
        }

    /** Keep the shutter live while earlier photos are still being encrypted and saved. */
    var rapidCapture: Boolean
        get() = prefs.getBoolean(KEY_RAPID_CAPTURE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_RAPID_CAPTURE, value) }
        }

    /**
     * Show the latest capture on the camera screen's gallery button. Off by default, so
     * nothing from the vault is visible on the camera screen unless the user asks for it.
     */
    var showLastCapture: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LAST_CAPTURE, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SHOW_LAST_CAPTURE, value) }
        }

    companion object {
        private const val NAME = "aescamera"
        private const val KEY_VAULT_LOCATION = "vault_location"
        private const val KEY_RAPID_CAPTURE = "rapid_capture"
        private const val KEY_SHOW_LAST_CAPTURE = "show_last_capture"
    }
}
