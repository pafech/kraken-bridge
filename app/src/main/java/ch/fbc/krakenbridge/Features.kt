package ch.fbc.krakenbridge

import android.content.Context
import androidx.core.content.edit

/**
 * User-chosen feature set.
 *
 * Camera (photo + video capture via the housing shutter) is the core of the
 * app and always on — it does not appear here. Gallery and Dive Mode are
 * opt-in: each brings additional permissions a diver may not want to grant.
 */
data class Features(
    val gallery: Boolean,
    val diveMode: Boolean
) {
    companion object {
        /** Default for fresh installs and existing users on first v1.5.0 launch. */
        val CameraOnly = Features(gallery = false, diveMode = false)
    }
}

class FeatureRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Features = Features(
        gallery = prefs.getBoolean(KEY_GALLERY, false),
        diveMode = prefs.getBoolean(KEY_DIVE_MODE, false)
    )

    fun save(features: Features) {
        prefs.edit {
            putBoolean(KEY_GALLERY, features.gallery)
            putBoolean(KEY_DIVE_MODE, features.diveMode)
        }
    }

    companion object {
        private const val PREFS_NAME = "kraken_features"
        private const val KEY_GALLERY = "feature_gallery"
        private const val KEY_DIVE_MODE = "feature_dive_mode"
    }
}

/**
 * Records which runtime permissions have been requested at least once.
 * Combined with shouldShowRequestPermissionRationale() this lets us detect
 * the "permanently denied" state — where the system silently rejects further
 * requestPermissions calls, and the only recovery is a manual grant in the
 * system app-info screen.
 */
class PermissionRequestLog(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markRequested(permission: String) {
        prefs.edit { putBoolean(permission, true) }
    }

    fun wasRequested(permission: String): Boolean =
        prefs.getBoolean(permission, false)

    companion object {
        private const val PREFS_NAME = "kraken_permission_log"
    }
}

/**
 * One-time UI hints that should not repeat on every app launch. Currently:
 * `mainPageOpened` — flips true the first time the user reaches the Main
 * page so the Settings → Main edge handle stops glowing as a CTA. Backed
 * up so a returning user on a new device doesn't see the glow again.
 *
 * `a11yDisclosureAccepted` — prominent-disclosure consent for the
 * AccessibilityService per Google Play User Data Policy. Persisted because
 * once the user has affirmatively consented, re-prompting on every launch
 * is hostile. Backed up so a Play Store reinstall doesn't drop the consent
 * record. Reset only by Clear Data or uninstall.
 */
class UiHints(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mainPageOpened: Boolean
        get() = prefs.getBoolean(KEY_MAIN_OPENED, false)
        set(value) { prefs.edit { putBoolean(KEY_MAIN_OPENED, value) } }

    var a11yDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_A11Y_DISCLOSURE_ACCEPTED, false)
        set(value) { prefs.edit { putBoolean(KEY_A11Y_DISCLOSURE_ACCEPTED, value) } }

    companion object {
        private const val PREFS_NAME = "kraken_ui_hints"
        private const val KEY_MAIN_OPENED = "main_page_opened"
        private const val KEY_A11Y_DISCLOSURE_ACCEPTED = "a11y_disclosure_accepted"
    }
}
