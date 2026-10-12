package io.github.vladislavekimtcov.anamorphic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

object MediaPermissions {
    private val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    val all: Array<String> get() = arrayOf(readPermission, Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Needed to find the original photo in MediaStore when it was shared by another app. */
    fun canReadMedia(context: Context) = granted(context, readPermission)

    /** Needed to read GPS coordinates of photos opened through MediaStore. */
    fun canReadLocation(context: Context) = granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun allGranted(context: Context) = canReadMedia(context) && canReadLocation(context)

    /** Android 12+ "Media management" special access: trash requests need no confirmation. */
    fun canManageMedia(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)
}
