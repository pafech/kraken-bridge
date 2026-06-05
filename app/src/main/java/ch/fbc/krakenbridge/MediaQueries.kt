package ch.fbc.krakenbridge

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Query MediaStore for the most recently added image or video.
 * Returns the content URI and MIME type, or null if nothing is found.
 * The MIME type is required so ACTION_VIEW resolves to a gallery viewer
 * that can render the URI directly in single-item view.
 *
 * Top-level pure function (resolver in, result out) so [KrakenBleService]
 * and BDD step definitions share one implementation.
 */
internal fun queryLatestMedia(contentResolver: ContentResolver): Pair<Uri, String>? {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

    contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val mediaType = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))

            val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val contentUri = if (isVideo) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            val mimeType = if (isVideo) "video/*" else "image/*"

            Log.d(KrakenBleService.TAG, "Latest media: id=$id, type=$mediaType, uri=$contentUri")
            return Pair(contentUri, mimeType)
        }
    }
    return null
}
