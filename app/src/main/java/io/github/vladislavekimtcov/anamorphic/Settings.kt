package io.github.vladislavekimtcov.anamorphic

import android.content.Context
import androidx.core.content.edit
import io.github.vladislavekimtcov.anamorphic.core.SqueezeFactor

/** The three user settings from the main screen. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var factor: SqueezeFactor
        get() = prefs.getString(KEY_FACTOR, null)
            ?.let { name -> SqueezeFactor.entries.firstOrNull { it.name == name } }
            ?: SqueezeFactor.X133
        set(value) = prefs.edit { putString(KEY_FACTOR, value.name) }

    /** The lens was rotated 90 degrees from the usual orientation (see Desqueeze.axisFor). */
    var rotated: Boolean
        get() = prefs.getBoolean(KEY_ROTATED, false)
        set(value) = prefs.edit { putBoolean(KEY_ROTATED, value) }

    var replaceOriginal: Boolean
        get() = prefs.getBoolean(KEY_REPLACE_ORIGINAL, true)
        set(value) = prefs.edit { putBoolean(KEY_REPLACE_ORIGINAL, value) }

    var askedForPermission: Boolean
        get() = prefs.getBoolean(KEY_ASKED_PERMISSION, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_PERMISSION, value) }

    private companion object {
        const val KEY_FACTOR = "factor"
        const val KEY_ROTATED = "rotated"
        const val KEY_REPLACE_ORIGINAL = "replace_original"
        const val KEY_ASKED_PERMISSION = "asked_permission"
    }
}
