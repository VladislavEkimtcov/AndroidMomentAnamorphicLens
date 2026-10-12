package io.github.vladislavekimtcov.anamorphic

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException

/** Reads shared photos and writes the processed copies through MediaStore. */
class MediaStoreRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** A shared photo, plus its MediaStore row when we could find it. */
    class Source(
        val uri: Uri,
        val displayName: String?,
        val size: Long?,
        /** The original's own MediaStore item; needed to move it to the trash. */
        val mediaUri: Uri?,
        val relativePath: String?,
        val volumeName: String?,
        val dateTaken: Long?,
    )

    class Content(
        val bytes: ByteArray,
        /** False when Android may have blanked GPS data while reading (see [read]). */
        val complete: Boolean,
    )

    fun describe(uri: Uri): Source {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.stringOrNull(OpenableColumns.DISPLAY_NAME)
                    size = c.longOrNull(OpenableColumns.SIZE)
                }
            }
        }
        val row = findMediaRow(uri, name, size)
        return Source(
            uri = uri,
            displayName = row?.displayName ?: name,
            size = row?.size ?: size,
            mediaUri = row?.uri,
            relativePath = row?.relativePath,
            volumeName = row?.volumeName,
            dateTaken = row?.dateTaken,
        )
    }

    /**
     * Reads the whole file. Android removes the location from photos read through MediaStore
     * unless the app holds ACCESS_MEDIA_LOCATION and asks for the original bytes.
     */
    fun read(source: Source): Content {
        val fromMediaStore = source.uri.authority == MediaStore.AUTHORITY
        if (fromMediaStore && MediaPermissions.canReadLocation(context)) {
            try {
                resolver.openInputStream(MediaStore.setRequireOriginal(source.uri))?.use {
                    return Content(it.readBytes(), complete = true)
                }
            } catch (_: SecurityException) {
                // Fall through to the redacted stream.
            } catch (_: UnsupportedOperationException) {
            }
        }
        val bytes = resolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open the photo")
        return Content(bytes, complete = !fromMediaStore)
    }

    /**
     * Saves [jpeg] as a new photo next to the original (same volume and folder) so it shows up
     * with it in the gallery and Google Photos backs it up the same way. Falls back to the
     * camera folder, which Google Photos backs up by default.
     */
    fun insert(jpeg: ByteArray, displayName: String, source: Source): Uri {
        val folder = source.relativePath?.takeIf { path -> ALLOWED_ROOTS.any { path.startsWith(it) } }
        val volume = source.volumeName.takeIf { folder != null } ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        return try {
            insertInto(volume, folder ?: DEFAULT_FOLDER, jpeg, displayName, source.dateTaken)
        } catch (e: Exception) {
            if (folder == null && volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) throw e
            insertInto(MediaStore.VOLUME_EXTERNAL_PRIMARY, DEFAULT_FOLDER, jpeg, displayName, source.dateTaken)
        }
    }

    private fun insertInto(volume: String, folder: String, jpeg: ByteArray, displayName: String, dateTaken: Long?): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTaken != null) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(volume), values)
            ?: throw IOException("Could not create the new photo")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(jpeg) } ?: throw IOException("Could not write the new photo")
            // Clearing IS_PENDING makes MediaStore scan the file and publish it to galleries.
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** A system request that moves [uris] to the trash (recoverable for 30 days). */
    fun trashRequest(uris: List<Uri>): IntentSender =
        MediaStore.createTrashRequest(resolver, uris, true).intentSender

    // --- Finding the original ----------------------------------------------------------------

    private class MediaRow(
        val uri: Uri,
        val displayName: String?,
        val size: Long?,
        val relativePath: String?,
        val volumeName: String?,
        val dateTaken: Long?,
    )

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.DATE_TAKEN,
    )

    private fun findMediaRow(uri: Uri, name: String?, size: Long?): MediaRow? {
        // Shared straight from MediaStore (Files, most galleries).
        if (uri.authority == MediaStore.AUTHORITY) queryRow(uri)?.let { return it }
        // A document (e.g. from the Files app) that MediaStore also knows about.
        if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
            runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()?.let { mediaUri ->
                queryRow(mediaUri)?.let { return it }
            }
        }
        // Any other app (Google Photos shares through its own provider): match name and size.
        if (name == null || size == null || !MediaPermissions.canReadMedia(context)) return null
        return runCatching {
            resolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(name, size.toString()),
                null,
            )?.use { c -> if (c.count == 1 && c.moveToFirst()) row(c, null) else null }
        }.getOrNull()
    }

    private fun queryRow(uri: Uri): MediaRow? = runCatching {
        resolver.query(uri, projection, null, null, null)?.use { c -> if (c.moveToFirst()) row(c, uri) else null }
    }.getOrNull()

    private fun row(c: Cursor, knownUri: Uri?): MediaRow {
        val volume = c.stringOrNull(MediaStore.MediaColumns.VOLUME_NAME)
        val uri = knownUri ?: ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL),
            c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
        )
        return MediaRow(
            uri = uri,
            displayName = c.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME),
            size = c.longOrNull(MediaStore.MediaColumns.SIZE),
            relativePath = c.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
            volumeName = volume,
            dateTaken = c.longOrNull(MediaStore.MediaColumns.DATE_TAKEN),
        )
    }

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }

    companion object {
        const val DEFAULT_FOLDER = "DCIM/Camera/"
        private val ALLOWED_ROOTS = listOf("DCIM/", "Pictures/")
    }
}
