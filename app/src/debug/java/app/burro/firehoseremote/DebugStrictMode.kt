package app.burro.firehoseremote

import android.app.Application
import android.os.StrictMode

/**
 * Debug builds only. Named as the application class by
 * `app/src/debug/AndroidManifest.xml`, which is how StrictMode gets installed
 * early enough to see work done before the activity starts — a call from
 * `MainActivity.onCreate` would miss everything ahead of it.
 *
 * The policy logs and does not kill. `penaltyDeath` would abort the process on
 * the first disk read it saw, which both makes the debug build unusable and
 * hides every later offender behind the first one.
 *
 * This class is not in the release build: the debug manifest is not merged into
 * release, so nothing there references it.
 */
class DebugStrictMode : Application() {

    override fun onCreate() {
        // Set before super.onCreate() so the policy is live for any work the
        // Application itself does.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
        super.onCreate()

        // Stand-in pairing for store screenshots — see DemoMode. Off the main
        // thread, and only when the store is empty, so it neither stalls launch
        // nor displaces a real pairing.
        DemoMode.seedIfEmpty(this)
    }
}
