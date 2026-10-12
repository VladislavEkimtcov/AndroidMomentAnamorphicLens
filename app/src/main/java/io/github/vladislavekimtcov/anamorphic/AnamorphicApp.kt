package io.github.vladislavekimtcov.anamorphic

import android.app.Application
import com.google.android.material.color.DynamicColors

class AnamorphicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
