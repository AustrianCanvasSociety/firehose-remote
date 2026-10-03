package io.github.austriancanvassociety.firehoseremote.store

import android.content.Context
import io.github.austriancanvassociety.firehoseremote.protocol.PairedDevice
import io.github.austriancanvassociety.firehoseremote.protocol.TokenStore

/**
 * [TokenStore] backed by `SharedPreferences` — the platform's own storage, so it
 * costs no dependency and survives process death.
 *
 * The token is a credential (`docs/protocol.md § 4`). It is written here and
 * read back for command headers; it is never logged anywhere.
 *
 * Lives outside `protocol/` because it imports `android.*` — that separation is
 * what keeps `PairingFlow` testable on the JVM.
 *
 * Schema (per-host, since 2026-09-20):
 *
 * - `paired_hosts` — comma-separated list of stored hosts in insertion order.
 * - `selected_host` — the currently-selected host (absent when the store is empty).
 * - `paired_name_<host>` — display name for that host.
 * - `paired_token_<host>` — token for that host.
 * - `paired_wakeup_mac_<host>` — the MAC that host announced in SSDP `WAKEUP`
 *   (since 2026-09-25). Optional: absent for a TV that announced none, and for
 *   every pairing made before it was kept, until a scan hears it. Its absence
 *   is not a schema break, so no lift is needed.
 *
 * A pre-2026-09-20 install carries three flat keys — `paired_host`,
 * `paired_name`, `paired_token` — and gets lifted into the per-host schema
 * atomically on first read-or-mutate through [liftLegacy]. The lift is
 * idempotent: rerunning it against an already-lifted store writes nothing.
 * Lift-on-mutate exists alongside lift-on-read so a fresh pair on a legacy
 * install cannot leave a two-entry state — the write goes through the new
 * schema, and the read that follows sees only that.
 */
class SharedPrefsTokenStore(context: Context) : TokenStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var lifted = false

    override fun load(): PairedDevice? {
        liftIfNeeded()
        val host = prefs.getString(KEY_SELECTED_HOST, null)?.takeIf { it.isNotEmpty() }
            ?: return null
        return readDevice(host)
    }

    override fun all(): List<PairedDevice> {
        liftIfNeeded()
        return hosts().mapNotNull { readDevice(it) }
    }

    override fun save(device: PairedDevice) {
        liftIfNeeded()
        val hostsList = hosts().toMutableList().also {
            it.remove(device.host)
            it.add(device.host)
        }
        val editor = prefs.edit()
            .putString(KEY_PAIRED_HOSTS, hostsList.joinToString(HOSTS_SEPARATOR))
            .putString(KEY_SELECTED_HOST, device.host)
            .putString(nameKey(device.host), device.name)
            .putString(tokenKey(device.host), device.token)
        if (device.wakeupMac != null) editor.putString(wakeupMacKey(device.host), device.wakeupMac)
        else editor.remove(wakeupMacKey(device.host))
        editor.apply()
    }

    override fun select(host: String): PairedDevice? {
        liftIfNeeded()
        val target = readDevice(host) ?: return null
        prefs.edit().putString(KEY_SELECTED_HOST, host).apply()
        return target
    }

    override fun rememberWakeupMac(host: String, mac: String) {
        liftIfNeeded()
        if (host !in hosts()) return
        prefs.edit().putString(wakeupMacKey(host), mac).apply()
    }

    override fun remove(host: String) {
        liftIfNeeded()
        val hostsList = hosts().toMutableList()
        if (!hostsList.remove(host)) return
        val currentSelected = prefs.getString(KEY_SELECTED_HOST, null)
        val editor = prefs.edit()
            .putString(KEY_PAIRED_HOSTS, hostsList.joinToString(HOSTS_SEPARATOR))
            .remove(nameKey(host))
            .remove(tokenKey(host))
            .remove(wakeupMacKey(host))
        if (currentSelected == host) {
            if (hostsList.isEmpty()) editor.remove(KEY_SELECTED_HOST)
            else editor.putString(KEY_SELECTED_HOST, hostsList.first())
        }
        editor.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
        lifted = false
    }

    private fun liftIfNeeded() {
        if (lifted) return
        val delta = liftLegacy(currentSnapshot())
        if (delta.isNotEmpty()) {
            val editor = prefs.edit()
            for ((k, v) in delta) {
                if (v == null) editor.remove(k) else editor.putString(k, v)
            }
            editor.apply()
        }
        lifted = true
    }

    private fun currentSnapshot(): Map<String, String?> =
        prefs.all.mapValues { (_, v) -> v as? String }

    private fun hosts(): List<String> {
        val csv = prefs.getString(KEY_PAIRED_HOSTS, null) ?: return emptyList()
        return csv.split(HOSTS_SEPARATOR).filter { it.isNotEmpty() }
    }

    private fun readDevice(host: String): PairedDevice? {
        val name = prefs.getString(nameKey(host), null) ?: return null
        val token = prefs.getString(tokenKey(host), null) ?: return null
        return PairedDevice(
            host = host,
            name = name,
            token = token,
            wakeupMac = prefs.getString(wakeupMacKey(host), null)
        )
    }

    companion object {
        const val PREFS_NAME = "firehose-remote-pairing"

        const val KEY_PAIRED_HOSTS = "paired_hosts"
        const val KEY_SELECTED_HOST = "selected_host"
        const val KEY_NAME_PREFIX = "paired_name_"
        const val KEY_TOKEN_PREFIX = "paired_token_"
        const val KEY_WAKEUP_MAC_PREFIX = "paired_wakeup_mac_"
        const val HOSTS_SEPARATOR = ","

        const val LEGACY_KEY_HOST = "paired_host"
        const val LEGACY_KEY_NAME = "paired_name"
        const val LEGACY_KEY_TOKEN = "paired_token"

        internal fun nameKey(host: String) = "$KEY_NAME_PREFIX$host"
        internal fun tokenKey(host: String) = "$KEY_TOKEN_PREFIX$host"
        internal fun wakeupMacKey(host: String) = "$KEY_WAKEUP_MAC_PREFIX$host"

        /**
         * Compute the write-delta that lifts a legacy three-key pairing into
         * the per-host schema, or an empty map when there is nothing to lift.
         *
         * Pure — extracted from the store's mutating path so the migration is
         * testable on the JVM without `SharedPreferences`. Callers apply the
         * delta with per-key `putString` for non-null values and `remove` for
         * null values.
         *
         * Idempotent. Returns an empty map when the input already carries the
         * new `paired_hosts` key, or when the input has no legacy pairing to
         * lift (fresh install). A partial legacy state (only some of the three
         * flat keys populated) is treated as no lift, because the lift's
         * post-condition needs all three.
         */
        fun liftLegacy(current: Map<String, String?>): Map<String, String?> {
            if (current[KEY_PAIRED_HOSTS] != null) return emptyMap()
            val host = current[LEGACY_KEY_HOST] ?: return emptyMap()
            val token = current[LEGACY_KEY_TOKEN] ?: return emptyMap()
            val name = current[LEGACY_KEY_NAME] ?: host
            return mapOf(
                KEY_PAIRED_HOSTS to host,
                KEY_SELECTED_HOST to host,
                nameKey(host) to name,
                tokenKey(host) to token,
                LEGACY_KEY_HOST to null,
                LEGACY_KEY_NAME to null,
                LEGACY_KEY_TOKEN to null,
            )
        }
    }
}
