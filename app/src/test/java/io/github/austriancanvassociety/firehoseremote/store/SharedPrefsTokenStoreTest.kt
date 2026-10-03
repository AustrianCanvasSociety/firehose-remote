package io.github.austriancanvassociety.firehoseremote.store

import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.KEY_PAIRED_HOSTS
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.KEY_SELECTED_HOST
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.LEGACY_KEY_HOST
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.LEGACY_KEY_NAME
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.LEGACY_KEY_TOKEN
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.liftLegacy
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.nameKey
import io.github.austriancanvassociety.firehoseremote.store.SharedPrefsTokenStore.Companion.tokenKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure lift function extracted from [SharedPrefsTokenStore] for the
 * Phase 3 Step 9 multi-TV migration. The store itself is Android-backed and
 * cannot be exercised from the JVM test suite (no Robolectric, zero deps), so
 * the migration lives as a companion function that takes and returns a map
 * snapshot and the write side of [SharedPrefsTokenStore] applies the delta.
 */
class SharedPrefsTokenStoreTest {

    @Test
    fun liftLegacyProducesOneEntryCollection() {
        val host = "192.0.2.22"
        val name = "Living Room"
        val token = "TOKEN-legacy"
        val input = mapOf(
            LEGACY_KEY_HOST to host,
            LEGACY_KEY_NAME to name,
            LEGACY_KEY_TOKEN to token,
        )

        val delta = liftLegacy(input)

        // (a) the collection index contains just this one host
        assertEquals(host, delta[KEY_PAIRED_HOSTS])
        // (b) the selected pointer picks that host
        assertEquals(host, delta[KEY_SELECTED_HOST])
        // (c) the display name lands under the per-host name key
        assertEquals(name, delta[nameKey(host)])
        // (d) the token lands under the per-host token key
        assertEquals(token, delta[tokenKey(host)])
        // (e) the three legacy keys map to null in the delta — the write side
        //     translates that into an editor.remove() so the flat keys are
        //     gone after the lift, not shadowed by the per-host ones.
        assertTrue("legacy host key must be scheduled for removal", delta.containsKey(LEGACY_KEY_HOST))
        assertNull(delta[LEGACY_KEY_HOST])
        assertTrue("legacy name key must be scheduled for removal", delta.containsKey(LEGACY_KEY_NAME))
        assertNull(delta[LEGACY_KEY_NAME])
        assertTrue("legacy token key must be scheduled for removal", delta.containsKey(LEGACY_KEY_TOKEN))
        assertNull(delta[LEGACY_KEY_TOKEN])

        // (f) rerunning against the post-lift snapshot is a no-op — the atomic
        //     lift must not fire twice against the same input class.
        val postLift = mapOf(
            KEY_PAIRED_HOSTS to host,
            KEY_SELECTED_HOST to host,
            nameKey(host) to name,
            tokenKey(host) to token,
        )
        assertTrue(
            "liftLegacy on an already-lifted snapshot must produce no writes, got ${liftLegacy(postLift)}",
            liftLegacy(postLift).isEmpty()
        )
    }

    @Test
    fun liftLegacyOnFreshInstallProducesNoWrites() {
        // A phone that never paired with the legacy schema — nothing in the
        // snapshot — must not trigger a lift. The store's first save() writes
        // straight into the per-host schema.
        assertTrue(liftLegacy(emptyMap()).isEmpty())
    }

    @Test
    fun liftLegacyIgnoresPartialLegacyState() {
        // Half-populated legacy state (host without token, or vice versa) is
        // not a lift candidate — the lift's post-condition needs all three
        // pieces to reconstruct a full PairedDevice. Treating a partial legacy
        // as a lift risks writing a broken pairing under the new schema.
        val partial = mapOf(
            LEGACY_KEY_HOST to "192.0.2.22",
            LEGACY_KEY_NAME to "Living Room",
            // no token
        )
        assertTrue(liftLegacy(partial).isEmpty())
    }

    @Test
    fun liftLegacyDefaultsMissingNameToTheHost() {
        // A very old legacy install may only carry host and token. The lift
        // still succeeds — using the host as the name is the same fallback
        // the pre-Step 9 SharedPrefsTokenStore already applied when reading.
        val host = "192.0.2.22"
        val token = "TOKEN-legacy"
        val input = mapOf(
            LEGACY_KEY_HOST to host,
            LEGACY_KEY_TOKEN to token,
        )

        val delta = liftLegacy(input)

        assertEquals(host, delta[KEY_PAIRED_HOSTS])
        assertEquals(host, delta[KEY_SELECTED_HOST])
        assertEquals("the missing name defaults to the host, not to null", host, delta[nameKey(host)])
        assertEquals(token, delta[tokenKey(host)])
    }
}
