package dev.openstream.tv

import android.app.Application
import dev.openstream.tv.local.LocalControlRuntime

class OpenStreamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.IS_MOBILE) LocalControlRuntime.start(this)
    }
}
