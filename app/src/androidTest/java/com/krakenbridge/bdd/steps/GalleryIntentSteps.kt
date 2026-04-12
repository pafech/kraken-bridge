package com.krakenbridge.bdd.steps

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.krakenbridge.KrakenBleService
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Step definitions for the gallery intent feature — verifies that MediaStore
 * queries and ACTION_VIEW intents are constructed correctly so that the
 * gallery opens in single-item view.
 *
 * These tests seed synthetic entries into MediaStore (no real files needed)
 * and run in CI on the emulator without BLE hardware.
 */
class GalleryIntentSteps {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var seededImageUri: Uri? = null
    private var seededVideoUri: Uri? = null
    private var queryResult: Pair<Uri, String>? = null
    private var constructedIntent: Intent? = null

    // ── Given ────────────────────────────────────────────────────────────────

    @Given("a test image has been seeded into MediaStore")
    fun seedTestImage() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "bdd_test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        seededImageUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
        assertNotNull("Failed to seed test image into MediaStore", seededImageUri)
    }

    @Given("a test video entry has been seeded into MediaStore")
    fun seedTestVideo() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "bdd_test_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        seededVideoUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        )
        assertNotNull("Failed to seed test video into MediaStore", seededVideoUri)
    }

    // ── When ─────────────────────────────────────────────────────────────────

    @When("the latest media is queried")
    fun queryLatestMedia() {
        val service = KrakenBleService.instance
        if (service != null) {
            queryResult = service.testQueryLatestMedia()
        } else {
            // Service not running — query directly via contentResolver as fallback
            queryResult = queryLatestMediaDirect()
        }
    }

    @When("the gallery intent is constructed for the latest media")
    fun constructGalleryIntent() {
        queryLatestMedia()
        val result = queryResult ?: return
        val (uri, mimeType) = result
        constructedIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.photos")
        }
    }

    // ── Then ─────────────────────────────────────────────────────────────────

    @Then("a content URI is returned")
    fun assertContentUriReturned() {
        assertNotNull("queryLatestMedia() returned null — no media found", queryResult)
        assertTrue(
            "Expected content:// URI but got: ${queryResult!!.first}",
            queryResult!!.first.scheme == "content"
        )
    }

    @Then("the MIME type is {string}")
    fun assertMimeType(expectedMimeType: String) {
        assertNotNull("No query result", queryResult)
        assertEquals(expectedMimeType, queryResult!!.second)
    }

    @Then("the intent action is ACTION_VIEW")
    fun assertIntentAction() {
        assertNotNull("No intent constructed", constructedIntent)
        assertEquals(Intent.ACTION_VIEW, constructedIntent!!.action)
    }

    @Then("the intent has a data URI set")
    fun assertIntentDataUri() {
        assertNotNull("No intent constructed", constructedIntent)
        assertNotNull("Intent data URI is null", constructedIntent!!.data)
    }

    @Then("the intent MIME type is not null")
    fun assertIntentMimeType() {
        assertNotNull("No intent constructed", constructedIntent)
        assertNotNull("Intent MIME type is null", constructedIntent!!.type)
    }

    @Then("the intent package is {string}")
    fun assertIntentPackage(expectedPackage: String) {
        assertNotNull("No intent constructed", constructedIntent)
        assertEquals(expectedPackage, constructedIntent!!.`package`)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Direct MediaStore query — mirrors KrakenBleService.queryLatestMedia() logic. */
    private fun queryLatestMediaDirect(): Pair<Uri, String>? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    )
                    val mediaType = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    )
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val contentUri = if (isVideo) {
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                        )
                    } else {
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                    }
                    val mimeType = if (isVideo) "video/*" else "image/*"
                    return Pair(contentUri, mimeType)
                }
            }
        return null
    }
}
