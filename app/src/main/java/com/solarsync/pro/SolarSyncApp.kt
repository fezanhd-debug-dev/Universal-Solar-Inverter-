package com.solarsync.pro

import android.app.Application

/**
 * App-level entry point. Referenced by AndroidManifest.xml's
 * android:name=".SolarSyncApp". Kept minimal for now — this is where
 * app-wide setup (logging, crash reporting, default polling interval,
 * saved connection settings) will be initialized as the app grows.
 */
class SolarSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Placeholder for future app-wide initialization.
    }
}
