package io.github.austriancanvassociety.firehoseremote.protocol

/**
 * A TV this app has paired with.
 *
 * Carries the host alongside the token because "show the paired TV on relaunch"
 * needs somewhere to send commands — a bare token is not addressable. The name
 * is whatever `Discovery` labelled the device with.
 *
 * The [token] is a credential per `docs/protocol.md § 4`. Store it, never log it.
 *
 * [wakeupMac] is the MAC the TV announced in its SSDP `WAKEUP` header — what a
 * wake sends its magic packet for ([WakeOnLan]). Null for a TV that announced
 * none, and for a pairing made before it was kept, until a scan hears it.
 */
data class PairedDevice(
    val host: String,
    val name: String,
    val token: String,
    val wakeupMac: String? = null
)

/**
 * Persistence seam for the pairing result.
 *
 * Lives in `protocol/` so the flow that drives it stays JVM-testable without a
 * device — same reason [Transport] lives here. The Android-backed implementation
 * is `store/SharedPrefsTokenStore.kt`, which sits outside this package precisely
 * because it imports `android.*`.
 */
interface TokenStore {
    /** The currently-selected pairing from a previous run, or null when nothing has been paired. */
    fun load(): PairedDevice?

    /** Every stored pairing in insertion order, oldest first. Empty when nothing has been paired. */
    fun all(): List<PairedDevice>

    /**
     * Persist [device] and mark it selected. If an entry with the same host is
     * already stored, its name, token and wake-up MAC are replaced in place; the selected
     * pointer moves to [device] either way. A freshly-paired TV is always the
     * one the operator wants to drive, so the selection follows the save.
     */
    fun save(device: PairedDevice)

    /**
     * Change which stored TV is the selected one, returning the newly-selected
     * device or null when [host] is not in the store. A no-op when [host] is
     * already selected — same return, same state.
     */
    fun select(host: String): PairedDevice?

    /**
     * Record the wake-up MAC an SSDP answer heard [host] announce, when [host]
     * is stored; a no-op otherwise. Unlike [save], the selection does not move —
     * a scan is not a choice of TV. This is how a pairing made before the MAC
     * was kept learns it: from a scan, or from the one search the app makes for
     * a stored TV with no MAC.
     */
    fun rememberWakeupMac(host: String, mac: String)

    /**
     * Drop the pairing for [host], if any. When the removed host was selected,
     * the selected pointer advances to the next stored device (in insertion
     * order) or becomes null when the store is empty. A no-op when [host] is
     * not stored, so callers do not have to check first.
     */
    fun remove(host: String)

    /**
     * Drop every stored pairing, so the next launch scans rather than going
     * straight to a paired screen. Kept alongside [remove] because "start
     * over" is a distinct affordance from "drop this TV" — the operator can
     * always reach it, and a per-host remove that has to be looped over the
     * whole list to accomplish it would be a rebuild of this method.
     */
    fun clear()
}
