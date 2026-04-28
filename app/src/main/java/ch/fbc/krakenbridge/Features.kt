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

    /** True once the user has confirmed a selection on the FeatureSelectionScreen. */
    fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)

    fun load(): Features = Features(
        gallery = prefs.getBoolean(KEY_GALLERY, false),
        diveMode = prefs.getBoolean(KEY_DIVE_MODE, false)
    )

    fun save(features: Features) {
        prefs.edit {
            putBoolean(KEY_GALLERY, features.gallery)
            putBoolean(KEY_DIVE_MODE, features.diveMode)
            putBoolean(KEY_CONFIGURED, true)
        }
    }

    companion object {
        private const val PREFS_NAME = "kraken_features"
        private const val KEY_GALLERY = "feature_gallery"
        private const val KEY_DIVE_MODE = "feature_dive_mode"
        private const val KEY_CONFIGURED = "configured"
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
