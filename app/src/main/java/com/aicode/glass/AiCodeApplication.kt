package com.aicode.glass

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AiCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        installStrictModeInDebug()
    }

    /**
     * Debug builds log (they never crash) any disk or network access that happens on the main
     * thread, so an "initialisation touches the disk" regression is visible in logcat instead of
     * being discovered as an ANR later. Filter with: adb logcat -s StrictMode
     */
    private fun installStrictModeInDebug() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyLog()
                .build()
        )
    }

    /**
     * Device-only startup failures (a stale database left by an older build, a corrupt settings
     * file, a keystore problem) otherwise show up as a bare "Force Close". Keep a stable tag so the
     * real stack trace can be captured with:
     *
     *   adb logcat -b crash
     *   adb logcat -s AiCodeGlassCrash:V AndroidRuntime:E
     *
     * The previous handler is always invoked so the platform still reports and kills the process.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Log.e(TAG, "Uncaught exception on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val TAG = "AiCodeGlassCrash"
    }
}
