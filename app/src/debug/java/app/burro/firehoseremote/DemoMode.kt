package app.burro.firehoseremote

import android.content.Context
import app.burro.firehoseremote.protocol.PairedDevice
import app.burro.firehoseremote.store.SharedPrefsTokenStore

/**
 * Debug builds only. Puts a stand-in TV in the pairing store so screenshots for
 * the store listing never carry a real device name or a real address.
 *
 * The stand-in is seeded only when the store is empty, so it can never displace
 * a real pairing, and it is replaced the moment one is made. Its host is an
 * RFC 5737 documentation address, which is reserved for exactly this and is
 * routed nowhere.
 *
 * There is no on/off switch, and that is deliberate: the app has to *open* on a
 * paired screen for the screenshot, and `Application.onCreate` runs before any
 * activity intent exists, so a launch extra could not reach this point. Acting
 * on "the store is empty" is the one trigger available that early.
 *
 * Seeding runs off the main thread — it is a read and a write of the same
 * prefs file the app reads at startup, and doing it inline would reintroduce
 * the launch stall that [app.burro.firehoseremote.ui.MainActivity] was changed
 * to avoid.
 */
object DemoMode {

    /** RFC 5737 TEST-NET-1. Reserved for documentation; never routed. */
    private const val DEMO_HOST = "192.0.2.10"

    private const val DEMO_NAME = "Living Room TV"

    /** Not a real token, and shaped so it cannot be mistaken for one. */
    private const val DEMO_TOKEN = "00000000000000000000000000000000"

    fun seedIfEmpty(context: Context) {
        Thread {
            val store = SharedPrefsTokenStore(context)
            if (store.load() == null) {
                store.save(
                    PairedDevice(
                        host = DEMO_HOST,
                        name = DEMO_NAME,
                        token = DEMO_TOKEN,
                        wakeupMac = null
                    )
                )
            }
        }.start()
    }
}
