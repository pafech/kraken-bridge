package ch.fbc.krakenbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Detect Android 14+ partial photo access: permissions are technically
 * "granted" but the user chose "Select photos" instead of "Allow all", so
 * MediaStore returns only the hand-picked subset (often empty for recent
 * captures).
 *
 * READ_MEDIA_VISUAL_USER_SELECTED granted while READ_MEDIA_IMAGES is not
 * means the user picked "Select photos" — partial access. When both are
 * granted we have full access, and an empty MediaStore is genuinely empty.
 */
internal fun hasPartialMediaAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val hasImages = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_MEDIA_IMAGES
    ) == PackageManager.PERMISSION_GRANTED
    val hasUserSelected = ContextCompat.checkSelfPermission(
        context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    ) == PackageManager.PERMISSION_GRANTED
    return hasUserSelected && !hasImages
}
