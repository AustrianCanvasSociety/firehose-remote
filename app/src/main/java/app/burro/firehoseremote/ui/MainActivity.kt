package app.burro.firehoseremote.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.burro.firehoseremote.R
import app.burro.firehoseremote.net.HttpUrlTransport
import app.burro.firehoseremote.net.MulticastSsdpClient
import app.burro.firehoseremote.net.UdpWakeOnLan
import app.burro.firehoseremote.protocol.Capabilities
import app.burro.firehoseremote.protocol.Device
import app.burro.firehoseremote.protocol.PairedDevice
import app.burro.firehoseremote.store.SharedPrefsTokenStore
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The single screen. It owns the Android types — views, threads, the local
 * network read — and delegates every decision to [PairingFlow], which is where
 * the pairing logic is tested.
 *
 * The pairing token is never logged.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Taps and hold repeats, in order, on [worker]; the rules are in [PressQueue]. */
    private val presses = PressQueue(worker)

    /**
     * Press-and-hold on the D-pad, timed on the main thread. The threshold is
     * the platform's long-press timeout, which follows the user's "Touch & hold
     * delay" accessibility setting.
     */
    private val hold = HoldRepeat(
        timer = { delayMs, task ->
            val run = Runnable { task() }
            main.postDelayed(run, delayMs)
            HoldRepeat.Cancellable { main.removeCallbacks(run) }
        },
        thresholdMs = ViewConfiguration.getLongPressTimeout().toLong()
    )

    /** The live remote while the paired screen is showing, else null. */
    private var remote: RemoteScreen? = null

    /**
     * What the TV said it can do, and which TV said it.
     *
     * Held rather than re-read on every render, because the redraw a read
     * triggers would otherwise start another read. Keyed by host so that
     * re-pairing cannot inherit the previous device's verdict — the whole point
     * of this read is that it is one device's answer about itself.
     */
    private var capabilities: Capabilities? = null
    private var capabilitiesHost: String? = null

    /** What is on screen, so Back can be answered against it rather than guessed at. */
    private var currentState: PairingFlow.State? = null

    /**
     * Which screen is showing, as navigation sees it. Every [render] moves it
     * on; a [redraw] keeps it. A press that finishes later draws only while
     * its token still matches — and then into whichever remote is showing now,
     * because a re-draw replaces the views without changing the screen.
     */
    private var screenToken = 0

    // What a re-draw has to put back that the flow's state does not hold. Each
    // belongs to the screen showing, so [render] drops them all.
    private var busyLabel: String? = null
    private var wakingLine: String? = null
    private var statusLine: String? = null
    private var pinDraft = ""
    private var addressDraft: String? = null

    /** The configuration the screen was last drawn for; see [onConfigurationChanged]. */
    private var drawnConfig: Configuration? = null

    /** Registered (API 33+) only while Back has somewhere in the app to go. */
    private var backCallback: OnBackInvokedCallback? = null

    /**
     * Address text to pre-fill the next mount of the address entry with. Set on
     * the way out of a typed-address [PairingFlow.State.Failed] and read (and
     * cleared) by [addressEntry]. A single-use carry rather than a persistent
     * setting: pre-fill applies only to the next screen the user lands on.
     */
    private var pendingAddressPrefill: String? = null

    private lateinit var flow: PairingFlow
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        content = pageColumn().apply {
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        // One listener on the one root covers every screen — the pairing screens
        // are added to [content] too, so none of them needs inset code of its
        // own. `clipToPadding = false` lets content scroll under a bar rather
        // than being cut at it.
        scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
            clipToPadding = false
            setOnApplyWindowInsetsListener { _, insets ->
                applyInsets(insets)
                insets
            }
        }
        setContentView(scroll)
        drawnConfig = Configuration(resources.configuration)

        // Building the flow reads the pairing store, and the store's first
        // `getSharedPreferences` is what creates the app's private prefs
        // directory — `File.exists` plus the mkdir, measured at 184 ms on the
        // Redmi Note 13 against a 16 ms frame. So the build, and the "which
        // screen do we open on?" question with it, go to a worker.
        //
        // Only the *first* such call costs anything: `ContextImpl` caches the
        // resolved prefs directory, so every later one — including the two
        // lambdas below — is a map lookup. That is why this is the only read
        // StrictMode reports.
        //
        // Until the worker answers, [content] is empty and no listener is wired.
        // There is nothing on screen that could reach `flow`, and `currentState`
        // stays null, which [redraw] and every other accessor already guard on.
        Thread {
            // Touch both prefs files here, so neither one's first read can land
            // on the main thread. Warming only the pairing store was not enough:
            // `start()` short-circuits `introSeen()` once a device is paired, so
            // the `ui` file would go untouched until `wordLabelsEnabled()` opened
            // it while drawing the paired screen.
            getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

            val built = PairingFlow(
                transport = HttpUrlTransport(log = requestLog()),
                tokenStore = SharedPrefsTokenStore(this),
                cidrProvider = { localCidr() },
                ssdp = MulticastSsdpClient(this),
                wakeOnLan = UdpWakeOnLan(broadcastAddresses = { localBroadcasts() }, log = requestLog()),
                vpnOn = { vpnOn() },
                introSeen = {
                    getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(PREF_INTRO_SEEN, false)
                },
                markIntroSeen = {
                    getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_INTRO_SEEN, true).apply()
                }
            )
            val initial = built.start()
            runOnUiThread {
                // A language change recreates the activity while this worker may
                // still be running; its answer belongs to the dead instance.
                if (isFinishing || isDestroyed) return@runOnUiThread
                flow = built
                render(initial)
            }
        }.start()
    }

    /**
     * Fold the system bars and any display cutout into [content]'s padding, on
     * top of its own 16dp.
     *
     * Below API 35 the window is not drawn edge to edge, so the insets arrive as
     * zero and this is a no-op; at 35+ they are the only thing keeping the top
     * bar out from under the status bar. There is deliberately no
     * `setDecorFitsSystemWindows` call — it is API 30+, and at 35+ it is ignored
     * anyway because edge-to-edge is forced.
     *
     * Two getters because the typed [WindowInsets] accessors are API 30+ and
     * `minSdk` is 24.
     *
     * The cutout is asked for separately at 30+ rather than folded in:
     * `systemBars()` is status + navigation + caption, with no cutout member.
     */
    private fun applyInsets(insets: WindowInsets) {
        val safe: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
            safe = Rect(
                maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top),
                maxOf(bars.right, cutout.right),
                maxOf(bars.bottom, cutout.bottom)
            )
        } else {
            // Below 30 there is no `displayCutout` inset type, so the safe
            // insets are the only route to the cutout.
            @Suppress("DEPRECATION")
            safe = Rect(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let { cutout ->
                    safe.left = maxOf(safe.left, cutout.safeInsetLeft)
                    safe.top = maxOf(safe.top, cutout.safeInsetTop)
                    safe.right = maxOf(safe.right, cutout.safeInsetRight)
                    safe.bottom = maxOf(safe.bottom, cutout.safeInsetBottom)
                }
            }
        }
        val pad = dp(16)
        content.setPadding(pad + safe.left, pad + safe.top, pad + safe.right, pad + safe.bottom)
    }

    /**
     * A change this Activity handles itself instead of being recreated (the
     * manifest's `configChanges` lists them, and says why). A size change —
     * rotation, split-screen — re-lays the views out on its own. Night mode,
     * font scale and density are baked into a built view: colours are read
     * when it is built and sizes converted to pixels, so those three draw the
     * screen again. The window background comes from the theme, applied once,
     * so it is set again by hand.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val drawn = drawnConfig
        drawnConfig = Configuration(newConfig)
        val baked = drawn == null ||
            (drawn.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ||
            drawn.fontScale != newConfig.fontScale ||
            drawn.densityDpi != newConfig.densityDpi
        if (!baked) return
        redraw()
    }

    /**
     * System Back below Android 13, and on any version that still routes Back
     * here. Where the screen has nowhere in the app to go, the platform's own
     * Back runs — it leaves the app.
     *
     * `@SuppressLint("GestureBackNavigation")`: lint reads any `onBackPressed`
     * override as a predictive-back handler that was never migrated, and its own
     * explanation concedes the check "does not consider per-activity
     * opt-in/opt-out". This app does migrate — the API 33+ callback is
     * registered on the dispatcher just below — and this override survives only
     * for the versions that have no dispatcher to register on.
     */
    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+; still the live path below it.")
    @Suppress("DEPRECATION") // the platform's own Back is what leaves the app on this route
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    /**
     * Back, decided against the screen the user is actually on
     * ([PairingFlow.back]). Returns false when Back should leave the app —
     * from the remote and the start screen — having done nothing.
     *
     * A typed-address failure backs out to the scan-result screen with the
     * address entry pre-filled with the text the user typed — no re-scan for
     * a typo.
     */
    private fun handleBack(): Boolean {
        val state = currentState ?: return false
        val next = flow.back(state) ?: return false
        if (state is PairingFlow.State.Failed && state.attemptedHost != null) {
            pendingAddressPrefill = state.attemptedHost
        }
        render(next)
        return true
    }

    /**
     * From Android 13 Back can reach the app through this dispatcher instead
     * of [onBackPressed]. A registered callback takes Back from the system on
     * every screen, so it is registered only while [PairingFlow.back] has
     * somewhere in the app to go. Unregistered, the system's own Back runs —
     * it leaves the app, with Android 16's predictive-back animation. Both
     * routes ask the same rule, so which one a given phone fires does not
     * change where Back goes.
     */
    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val staysInApp = currentState?.let { flow.back(it) } != null
        val registered = backCallback
        if (staysInApp && registered == null) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
            backCallback = callback
        } else if (!staysInApp && registered != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(registered)
            backCallback = null
        }
    }

    /**
     * Interrupts whatever the worker is running. A press stopped once its
     * keyDown may have landed still sends its keyUp on the way out
     * (`FireTvClient.sendKey`); everything else
     * ends at its next wait, and each task catches the interrupt and stops
     * without drawing.
     */
    override fun onDestroy() {
        hold.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * A hold ends when the screen loses the user — another app, a dialog, the
     * lock screen. The finger's release may never reach this window, and a hold
     * waiting for it would keep sending presses.
     */
    override fun onPause() {
        hold.stop()
        super.onPause()
    }

    /**
     * Hand [block] to the main thread, to run only if this Activity is still
     * alive when it gets there. A worker task can finish after the screen is
     * gone — a press waiting out a 40 s wake, say — and drawing into a
     * destroyed Activity's views does nothing but keep it in memory.
     */
    private fun postIfAlive(block: () -> Unit) {
        main.post { if (!isDestroyed) block() }
    }

    // --- rendering -------------------------------------------------------

    /**
     * Go to [state]: a new screen. What a re-draw would carry over belongs to
     * the screen being left, so it is dropped here, and the token moves on so
     * a press still running for the old screen draws nothing into this one.
     */
    private fun render(state: PairingFlow.State) {
        screenToken++
        busyLabel = null
        wakingLine = null
        statusLine = null
        pinDraft = ""
        addressDraft = null
        draw(state)
    }

    /**
     * Draw the screen that is showing again, with new views — the same screen,
     * so the token stays and a wake, a status line, typed text and a busy
     * message all come back. Without this, a dark-mode switch mid-wake would
     * leave live controls and no Cancel on the new remote while the wake
     * finished into the old one.
     */
    private fun redraw() {
        val state = currentState ?: return
        val busy = busyLabel
        if (busy != null) {
            // The flow's state is still the screen that started the work; drawing
            // it would offer the PIN screen again mid-pairing.
            showBusy(busy)
            return
        }
        val hadFocus = currentFocus is EditText
        draw(state)
        val screen = remote
        if (screen != null) {
            wakingLine?.let { showWaking(screen, it) }
            statusLine?.let { screen.showStatus(it) }
        }
        if (hadFocus) firstEditText(content)?.requestFocus()
    }

    private fun firstEditText(view: View): EditText? = when (view) {
        is EditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstEditText(view.getChildAt(it)) }
        else -> null
    }

    private fun draw(state: PairingFlow.State) {
        currentState = state
        applySkin()
        content.removeAllViews()
        // Every render rebuilds the view tree, so the previous remote is gone
        // the moment its views are. Dropping the reference here is what lets an
        // in-flight press notice its screen no longer exists. A hold on the old
        // D-pad ends with it.
        remote = null
        hold.stop()
        when (state) {
            is PairingFlow.State.FirstRun -> showFirstRun()
            is PairingFlow.State.Scanning -> showScan()
            is PairingFlow.State.Discovered -> showDevices(state.devices)
            is PairingFlow.State.HostDidNotAnswer -> showHostDidNotAnswer()
            is PairingFlow.State.HostRefused -> showHostRefused()
            is PairingFlow.State.AwaitingPin -> showPinEntry(state.device)
            is PairingFlow.State.Paired -> showPaired(state.device)
            is PairingFlow.State.Failed -> showFailure(state)
        }
        updateBackCallback()
    }

    /**
     * The first-run screen: what the app needs, before it asks for anything.
     *
     * The two facts a first-time user cannot guess — the phone has to be on the
     * same Wi-Fi as the TV, and the PIN comes off the TV once — said before the
     * Scan button rather than after a scan that found nothing. Shown once; the
     * flow decides that, not this screen.
     */
    private fun showFirstRun() {
        content.addView(heading(getString(R.string.first_run_heading)))
        content.addView(message(getString(R.string.first_run_body)))
        content.addView(actionButton(getString(R.string.first_run_button)) {
            render(flow.dismissIntro())
        })
    }

    private fun showScan() {
        content.addView(actionButton(getString(R.string.scan_button)) { beginScan() })
    }

    /**
     * The device list. Always ends with a way to scan again: a sleeping TV does
     * not answer the control API at all, so a scan run before the TV is awake
     * comes back short — and without this the only way out would be to kill the
     * app.
     */
    private fun showDevices(devices: List<Device>) {
        if (devices.isEmpty()) {
            content.addView(heading(getString(R.string.no_devices)))
        } else {
            content.addView(heading(getString(R.string.found_devices)))
            val storedHosts = flow.storedDevices().map { it.host }.toSet()
            // A name that two rows share does not say which TV is which, so
            // those rows carry their address too. The address attaches to the
            // name, ahead of the answering and paired suffixes, because the
            // name is what it disambiguates.
            val shared = flow.namesNeedingAddress(devices)
            val format: (Int, List<Any>) -> String = { resId, args -> getString(resId, *args.toTypedArray()) }
            for (device in devices) {
                val label = flow.scanRowLabel(device, storedHosts, shared, format)
                content.addView(actionButton(label) { beginPairing(device) })
            }
        }
        // Stored TVs join the list whether or not they answered, so "nothing
        // answered" is the test here, not an empty list.
        if (devices.none { it.answering }) addVpnNote()
        // Offered on every scan result, not only the empty one. The deliverable
        // asks for the typed route "when the scan comes back empty, or on the
        // user's own initiative", and the second half is precisely the case
        // where the scan found something and the TV the user wants was not in
        // it — on another /24 of the same network, where no scan will reach it.
        content.addView(addressEntry())
        content.addView(scanAgainButton())
    }

    /**
     * The manual route, built once and mounted wherever a scan result shows.
     *
     * One builder for every mounting point, so the screens cannot drift into
     * asking for the same thing in two slightly different ways.
     *
     * [pendingAddressPrefill] is read here and cleared: the retry lane sets it
     * on the way out of a typed-address failure, and the next mount of the
     * address entry consumes it. Every other mount renders empty.
     */
    private fun addressEntry(): View {
        val prefill = pendingAddressPrefill
        pendingAddressPrefill = null
        if (prefill != null) addressDraft = prefill
        return PairingScreen(skinnedContext()).buildAddressEntry(
            prefill = prefill,
            draft = addressDraft,
            onChanged = { addressDraft = it }
        ) { address ->
            beginPairingByAddress(address)
        }
    }

    private fun scanAgainButton() = actionButton(getString(R.string.scan_again_button)) { beginScan() }

    private fun showPinEntry(device: Device) {
        content.addView(
            PairingScreen(skinnedContext()).build(
                deviceLabel = device.name,
                initialPin = pinDraft,
                onPinChanged = { pinDraft = it },
                onSubmit = { pin -> submitPin(device, pin) },
                onBack = { render(flow.goBackFromPin()) }
            )
        )
    }

    /**
     * A scan that came back empty while a host on the network went quiet. A
     * different screen from an empty scan list on purpose — "nothing is out
     * there" and "something is out there and will not answer" are different
     * facts, and only the second one tells the user to go turn the TV on.
     */
    private fun showHostDidNotAnswer() {
        content.addView(heading(getString(R.string.no_devices)))
        content.addView(message(getString(R.string.host_did_not_answer)))
        addVpnNote()
        content.addView(addressEntry())
        content.addView(scanAgainButton())
    }

    /**
     * On a scan that heard nothing, say a VPN is on when one is. A VPN that
     * tunnels everything hides every TV on the network, and the screens above
     * would otherwise send the user off to check a TV that is fine.
     */
    private fun addVpnNote() {
        if (vpnOn()) content.addView(message(getString(R.string.vpn_may_block)))
    }

    /**
     * A scan that came back empty while a host refused the connection. Kept off
     * [showHostDidNotAnswer] because the two are different facts — that host took
     * the connection and stopped talking, this one would not take it at all —
     * and both are different from an empty network. Both end in the same
     * instruction, and neither says which of sleeping-or-wedged the TV is.
     */
    private fun showHostRefused() {
        content.addView(heading(getString(R.string.no_devices)))
        content.addView(message(getString(R.string.host_refused)))
        content.addView(addressEntry())
        content.addView(scanAgainButton())
    }

    /**
     * The paired screen. Every route back to scanning lives inside the remote
     * screen now — the top-bar overflow menu carries Scan-for-another / Forget /
     * About, so the paired screen is itself one composite view.
     *
     * Without a way to re-scan the app could never be pointed at a different TV
     * — the only escape would be clearing its data — so the overflow items are
     * an invariant on the paired screen, not a nice-to-have.
     */
    private fun showPaired(device: PairedDevice) {
        // A read cached against a different TV says nothing about this one.
        val known = capabilities.takeIf { capabilitiesHost == device.host }
        // Built from the skinned context, so every `R.color.*` the screen reads
        // resolves against the chosen mode rather than the system's.
        val screen = RemoteScreen(
            context = skinnedContext(),
            deviceName = device.name,
            rows = drawnRows(known),
            rocker = drawnRocker(known),
            wordLabels = wordLabelsEnabled(),
            accent = Skin.accentFor(skinPreset(), skinMode()),
            gradient = skinDirection() == SkinDirection.GRADIENT,
            onPress = { control -> pressControl(control) },
            onOverflow = { anchor -> showOverflowMenu(anchor) },
            hold = hold,
            onRepeat = { control -> repeatPress(control) }
        )
        content.addView(screen.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        remote = screen

        if (known == null) readCapabilities(device)
    }

    /** Word labels on the volume rocker instead of `＋` / `−`. */
    private fun wordLabelsEnabled(): Boolean = prefs().getBoolean(PREF_WORD_LABELS, false)

    private fun setWordLabels(enabled: Boolean) {
        prefs().edit().putBoolean(PREF_WORD_LABELS, enabled).apply()
    }

    private fun prefs() = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

    /** The chosen direction, or the one closest to the icon. */
    private fun skinDirection(): SkinDirection =
        Skin.directionFor(prefs().getString(PREF_SKIN_DIRECTION, null), SkinDirection.PLATE)

    /** The chosen mode. Chosen in the app, not followed from the system. */
    private fun skinMode(): SkinMode =
        Skin.modeFor(prefs().getString(PREF_SKIN_MODE, null), SkinMode.DARK)

    /** The chosen accent preset, or the default. */
    private fun skinPreset(): AccentPreset = Skin.presetFor(prefs().getString(PREF_SKIN_PRESET, null))

    /**
     * A context whose resources resolve against the chosen mode rather than the
     * system's.
     *
     * The colours are ordinary resources — `values/colors.xml` and
     * `values-night/colors.xml` — so picking a mode in the app means resolving
     * them against a configuration that says so, rather than reading the
     * system's. Building the screen from this context is what makes the choice
     * real; without it the menu would set a preference nothing acted on.
     */
    private fun skinnedContext(): Context {
        val configuration = Configuration(resources.configuration)
        val withoutNight = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()
        configuration.uiMode = withoutNight or when (skinMode()) {
            SkinMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            SkinMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
        }
        return SkinContext(
            android.view.ContextThemeWrapper(createConfigurationContext(configuration),
                if (skinMode() == SkinMode.LIGHT) android.R.style.Theme_Material_Light_NoActionBar
                else android.R.style.Theme_Material_NoActionBar),
            Skin.paletteFor(skinPreset(), skinMode(), skinDirection()),
            skinDirection()
        )
    }

    /** Apply the chosen mode to the whole viewport, including the system bars. */
    @Suppress("DEPRECATION") // Bar colors and flags remain necessary below API 30.
    private fun applySkin() {
        val skinContext = skinnedContext()
        val palette = skinContext.skin!!
        window.setBackgroundDrawable(ColorDrawable(palette.ground))
        scroll.background = skinContext.skinBackground()
        val light = skinMode() == SkinMode.LIGHT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = palette.ground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
        } else {
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            window.decorView.systemUiVisibility =
                (window.decorView.systemUiVisibility and flags.inv()) or if (light) flags else 0
        }
    }

    /** Redraw also preserves a wake, its Cancel control and any status line. */
    private fun redrawSkin() = redraw()

    private fun dialogContext(): Context {
        val dialogTheme = if (skinMode() == SkinMode.LIGHT)
            android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
        // Keep the activity's window token while choosing the dialog's mode.
        return SkinContext(android.view.ContextThemeWrapper(this, dialogTheme),
            Skin.paletteFor(skinPreset(), skinMode(), skinDirection()), skinDirection())
    }

    private fun styleDialog(dialog: AlertDialog) {
        val context = dialog.context
        val palette = context.skin!!
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(palette.panel)
            cornerRadius = context.dp(BUTTON_CORNER_DP).toFloat()
            setStroke(context.dp(1), palette.edge)
        })
        fun tint(view: View) {
            (view.background as? android.graphics.drawable.RippleDrawable)?.let { ripple ->
                val color = android.content.res.ColorStateList.valueOf(palette.stateLayer)
                ripple.setColor(color)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ripple.setEffectColor(color)
            }
            if (view is TextView) view.setTextColor(palette.ink)
            if (view is RadioButton) view.buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.select, palette.dim)
            )
            if (view is ViewGroup) for (index in 0 until view.childCount) tint(view.getChildAt(index))
        }
        dialog.window?.decorView?.let { tint(it) }
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = PAGE_BODY_SP
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply {
                setTextColor(palette.select)
                textSize = ACTION_TEXT_SP
                minHeight = context.dp(48)
            }
        }
    }

    private fun showThemeDialog() {
        val context = dialogContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        }
        fun label(text: Int, control: View) {
            control.id = View.generateViewId()
            content.addView(TextView(context).apply {
                setText(text)
                textSize = PAGE_BODY_SP
                setTextColor(context.skin!!.ink)
                labelFor = control.id
                setPadding(0, context.dp(12), 0, context.dp(4))
            })
            content.addView(control, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        fun choices(title: Int, options: List<Int>, selected: Int): RadioGroup {
            val group = RadioGroup(context).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEach { text ->
                group.addView(RadioButton(context).apply {
                    id = View.generateViewId()
                    setText(text)
                    textSize = PAGE_BODY_SP
                    minHeight = context.dp(48)
                }, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            group.check(group.getChildAt(selected).id)
            label(title, group)
            return group
        }
        val directions = SkinDirection.values()
        val modes = SkinMode.values()
        val style = choices(R.string.theme_style,
            listOf(R.string.theme_style_plate, R.string.theme_style_gradient), directions.indexOf(skinDirection()))
        val mode = choices(R.string.theme_mode,
            listOf(R.string.theme_mode_dark, R.string.theme_mode_light), modes.indexOf(skinMode()))
        val accent = Spinner(context).apply {
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, Skin.presets.map { it.label }) {
                private fun style(view: View): View = (view as TextView).apply {
                    setTextColor(context.skin!!.ink)
                    textSize = PAGE_BODY_SP
                    minHeight = context.dp(48)
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    style(super.getView(position, convertView, parent))
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    style(super.getDropDownView(position, convertView, parent)).apply {
                        setBackgroundColor(context.skin!!.panel)
                    }
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.skin!!.dim)
            minimumHeight = context.dp(48)
            setSelection(Skin.presets.indexOf(skinPreset()))
        }
        label(R.string.theme_accent, accent)
        AlertDialog.Builder(context)
            .setTitle(R.string.menu_theme)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.theme_apply) { _, _ ->
                val direction = directions[style.indexOfChild(style.findViewById(style.checkedRadioButtonId))]
                val chosenMode = modes[mode.indexOfChild(mode.findViewById(mode.checkedRadioButtonId))]
                prefs().edit()
                    .putString(PREF_SKIN_DIRECTION, direction.name.lowercase())
                    .putString(PREF_SKIN_MODE, chosenMode.name.lowercase())
                    .putString(PREF_SKIN_PRESET, Skin.presets[accent.selectedItemPosition].id)
                    .apply()
                redrawSkin()
            }
            .show()
            .also { styleDialog(it) }
    }

    /**
     * The overflow popup: Switch / Scan / Forget / About / Word labels,
     * anchored on the `⋯` icon. The Switch surface appears only when more than
     * one TV is stored, because there is nothing to switch to otherwise. With
     * exactly two TVs stored, the not-current one gets its own top-level entry
     * — one tap to switch. With three or more, the switch entries live under a
     * submenu so the popup does not overflow with per-TV rows next to the
     * Scan/Forget/About entries.
     *
     * A native [PopupMenu] rather than a `res/menu` XML resource — items are
     * declared here so the file surface stays the same one the wire actions
     * live in, and there is no marker XML to fall out of step with the routing.
     */
    private fun showOverflowMenu(anchor: View) {
        val stored = flow.storedDevices()
        val currentHost = (currentState as? PairingFlow.State.Paired)?.device?.host
        val switchable = stored.filter { it.host != currentHost }
        PopupMenu(skinnedContext(), anchor).apply {
            // Track dynamically-assigned item ids for the per-TV switch entries,
            // so the click listener can route by id back to the host string
            // without parsing the label text.
            val switchTargets = mutableMapOf<Int, String>()

            if (switchable.size == 1) {
                val other = switchable.single()
                val id = MENU_ID_SWITCH_BASE
                menu.add(0, id, 0, getString(R.string.action_switch_to_named_tv, other.name))
                switchTargets[id] = other.host
            } else if (switchable.size >= 2) {
                val submenu = menu.addSubMenu(0, MENU_ID_SWITCH_SUBMENU, 0, R.string.switch_to_tv_menu)
                // The currently-selected TV appears in the submenu with a
                // "(current)" suffix so the entire stored set is visible at
                // one glance; tapping it is a harmless no-op.
                stored.forEachIndexed { idx, device ->
                    val id = MENU_ID_SWITCH_BASE + idx
                    val label = if (device.host == currentHost) {
                        getString(R.string.overflow_current_suffix, device.name)
                    } else {
                        device.name
                    }
                    submenu.add(0, id, idx, label)
                    switchTargets[id] = device.host
                }
            }

            menu.add(0, MENU_ID_SCAN, 100, R.string.scan_for_another_button)
            menu.add(0, MENU_ID_FORGET, 101, R.string.forget_button)
            menu.add(0, MENU_ID_ABOUT, 102, R.string.menu_about)
            // Last, because these are preferences rather than actions.
            menu.add(0, MENU_ID_WORD_LABELS, 103, R.string.menu_word_labels).apply {
                isCheckable = true
                isChecked = wordLabelsEnabled()
            }

            menu.add(0, MENU_ID_THEME, 104, R.string.menu_theme)

            setOnMenuItemClickListener { item ->
                when (val id = item.itemId) {
                    MENU_ID_SCAN -> { beginScan(); true }
                    MENU_ID_FORGET -> { confirmForget(); true }
                    MENU_ID_ABOUT -> { showAboutDialog(); true }
                    MENU_ID_WORD_LABELS -> {
                        setWordLabels(!wordLabelsEnabled())
                        // The same in-place rebuild step 4 built for a config
                        // change: labels swap, the screen token stays.
                        redraw()
                        true
                    }
                    MENU_ID_THEME -> { showThemeDialog(); true }
                    MENU_ID_SWITCH_SUBMENU -> false // let Android open the submenu
                    else -> {
                        val host = switchTargets[id]
                        if (host != null) {
                            flow.switchTo(host)?.let { render(it) }
                            true
                        } else false
                    }
                }
            }
            show()
        }
    }

    /**
     * Ask before dropping a pairing.
     *
     * Forget used to be one tap, and it was the only entry in the overflow
     * that destroys something the user cannot get back without walking to the
     * TV and reading a fresh PIN off it — while sitting one row from Scan. A
     * mis-tap cost a pairing. Cancel does nothing at all, which is exactly what
     * keeps the pairing; only the positive button touches the store.
     *
     * The host is named explicitly rather than left to `forget()`'s default, so
     * a switch between opening the menu and answering the dialog cannot drop a
     * different TV than the one the dialog named.
     */
    private fun confirmForget() {
        val device = (currentState as? PairingFlow.State.Paired)?.device ?: return
        AlertDialog.Builder(dialogContext())
            .setTitle(R.string.forget_dialog_title)
            .setMessage(getString(R.string.forget_dialog_body, device.name))
            .setPositiveButton(R.string.forget_confirm_button) { _, _ -> render(flow.forget(device.host)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { styleDialog(it) }
    }

    /**
     * The About dialog: app name, version, and F-Droid source URL.
     *
     * Native [AlertDialog] via `AlertDialog.Builder` — again, no third-party
     * runtime deps. The version comes off [android.content.pm.PackageManager]
     * so a release with a bumped `versionName` reads correctly here without a
     * matching edit to the string, and without turning on Gradle's BuildConfig
     * generation (off by default in AGP 8).
     */
    private fun showAboutDialog() {
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "?"
        }
        AlertDialog.Builder(dialogContext())
            .setTitle(R.string.about_dialog_title)
            .setMessage(getString(R.string.about_dialog_body, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .also { styleDialog(it) }
    }

    /**
     * Ask the TV what it can do. A cancellation, not a refusal: the controls are
     * already on screen and this can only take some away.
     *
     * Drawn first and asked second, because the read costs a round trip — up to
     * the read timeout against a sleeping TV, which answers nothing. Only a
     * *suppression* is worth rebuilding the tree for, and only while this is
     * still the screen on show: a re-scan landing mid-read replaces the view
     * tree, and redrawing into a discarded screen would be invisible at best.
     */
    private fun readCapabilities(device: PairedDevice) {
        worker.execute {
            // A fault here is no answer, the same as a read the network lost:
            // the controls already drawn stay.
            val read = try {
                flow.capabilities()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            } catch (e: RuntimeException) {
                null
            }
            postIfAlive {
                if (read == null) return@postIfAlive
                val showing = currentState
                if (showing !is PairingFlow.State.Paired || showing.device.host != device.host) return@postIfAlive
                // What is on screen right now is the ungated set — this ran
                // because there was no cached answer to draw from.
                val changed = capabilitiesTakeSomethingAway(read)
                capabilities = read
                capabilitiesHost = device.host
                // The same screen with fewer controls — a re-draw, so a wake
                // or a message already showing stays.
                if (changed) redraw()
            }
        }
    }

    /**
     * The failure screen. On the typed-address route the state carries the
     * text the user typed, so the Try-again button lands them on a screen with
     * the address entry pre-filled — no re-scan for a typo. From other failure
     * causes the field is null and the screen keeps its original shape: just a
     * Back button to the start.
     */
    private fun showFailure(state: PairingFlow.State.Failed) {
        content.addView(heading(getString(R.string.pairing_failed)))
        content.addView(message(state.message))
        if (state.attemptedHost != null) {
            content.addView(
                actionButton(getString(R.string.try_again_button)) {
                    pendingAddressPrefill = state.attemptedHost
                    render(flow.goBackFromFailure())
                }
            )
        }
        content.addView(actionButton(getString(R.string.back_button)) { handleBack() })
    }

    private fun showBusy(label: String) {
        busyLabel = label
        content.removeAllViews()
        // Set here rather than inside [message] so it covers the busy line only.
        // Every other caller of [message] — failures, empty scans — is a screen
        // the user deliberately arrived at, and re-announcing those would talk
        // over the focus move that got them there. A scan or pairing in progress
        // is the case nothing else reports.
        content.addView(message(label).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
    }

    // --- actions ---------------------------------------------------------
    // Every one of these blocks on network I/O, so each runs on `worker` and
    // hops back to the main thread to render.

    /**
     * Run a flow call off the main thread and render what it returns.
     *
     * The catch-all is why every action routes through here. An exception that
     * escapes a task handed to `execute` is not swallowed: it reaches the
     * thread's uncaught-exception handler, and on Android that kills the app.
     * So an unexpected `RuntimeException` becomes a failure screen instead.
     * An `InterruptedException` means [onDestroy] stopped the worker — there is
     * no screen left to report to, so the task just ends. `Error` is
     * deliberately not caught — a fault in this process is not something to
     * render as a failed action.
     */
    private fun runFlow(work: () -> PairingFlow.State) {
        worker.execute {
            val state = try {
                work()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            } catch (e: RuntimeException) {
                PairingFlow.State.Failed(
                    getString(R.string.unexpected_failure),
                    PairingFlow.State.Cause.Unexpected
                )
            }
            postIfAlive { render(state) }
        }
    }

    private fun beginScan() {
        showBusy(getString(R.string.scanning))
        runFlow { flow.scan() }
    }

    private fun beginPairing(device: Device) {
        showBusy(getString(R.string.pairing_state))
        runFlow { flow.select(device) }
    }

    /**
     * Pair with an address the user typed, rather than one a scan found.
     *
     * A malformed address never leaves the flow — it is refused before the
     * first request is built — so this reaches the network only when there is
     * an address worth dialling. That is the whole reason the check lives in
     * [PairingFlow] and not here.
     */
    private fun beginPairingByAddress(address: String) {
        showBusy(getString(R.string.pairing_state))
        runFlow { flow.selectByAddress(address) }
    }

    private fun submitPin(device: Device, pin: String) {
        showBusy(getString(R.string.pairing_state))
        runFlow { flow.pair(device, pin) }
    }

    /**
     * One tap of one control, sent after any taps still in line ([presses]).
     *
     * The controls stay live, so taps made faster than the TV answers queue
     * in order instead of landing on disabled buttons — the walkthrough's
     * rapid-tap run sent 2 of 8 when every press switched them off. They go
     * dead only while a press has to wake the TV, with Cancel on screen, and
     * come back when that press returns. A result is written only if this
     * screen is still the one on display — a re-scan landing mid-press
     * replaces the view tree, and writing into a discarded screen would be
     * invisible at best.
     */
    private fun pressControl(control: RemoteControl) {
        // A tap on another control mid-hold would disable the held D-pad under
        // the finger; the hold owns the press path until it ends.
        if (hold.isHolding) return
        val screen = remote ?: return
        val token = screenToken
        // The TV this press is for, read on the main thread where the screen
        // state lives — the MAC search below is for this TV only.
        val host = (currentState as? PairingFlow.State.Paired)?.device?.host
        showStatus(screen, null)
        presses.enqueue({ startedWaking ->
            // `press` reports failure through its callback and returns an
            // optional follow-up state — non-null when the TV rejected the
            // stored token (401/403) and the flow has already started pairing
            // that host again: the PIN screen, or a failure when the TV would
            // not start pairing. The catch-all is what keeps a fault
            // from leaving the controls disabled forever with the TV
            // unreachable and no message.
            var reported: String? = null
            var followUp: PairingFlow.State? = null
            // Set on this worker thread by the wake callback and read back
            // here, on the same thread, once the press returns.
            var woke = false
            try {
                // The control travels whole: its action names the endpoint and
                // its `keyed` flag names the wire shape, so neither can be
                // routed independently of the other.
                followUp = flow.press(
                    control.action,
                    control.keyed,
                    onWaking = { line ->
                        woke = true
                        startedWaking()
                        postIfAlive { remoteFor(token)?.let { showWaking(it, line) } }
                    }
                ) { reported = it }
            } catch (e: InterruptedException) {
                // Cancelled, or the screen is being torn down. A keyUp the TV
                // was owed has already gone out (`FireTvClient.sendKey`), and
                // Cancel gives the controls back itself; nothing to draw.
                Thread.currentThread().interrupt()
                return@enqueue true
            } catch (e: RuntimeException) {
                reported = getString(R.string.unexpected_failure)
            }
            val message = reported
            val next = followUp
            // A press that landed proves the TV is awake — the only time its
            // `WAKEUP` MAC can be heard. Queued behind this press rather than
            // inside it, so the controls come back without waiting out the
            // search, and only by the last press in line: the search holds the
            // worker for a whole SSDP window, which mid-burst would stall the
            // taps behind it. Once per TV per screen. The catches keep a fault
            // in a background fill from killing the app — including the
            // queueing itself, which the worker refuses once [onDestroy] has
            // shut it down.
            if (host != null && message == null && next == null && !presses.hasQueuedBehind) {
                try {
                    worker.execute {
                        try {
                            flow.learnWakeupMac(host)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (e: RuntimeException) {
                            // Nothing to report: the press already landed, and a
                            // scan still fills the MAC.
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    // Shut down between the press and here; the next scan fills the MAC.
                }
            }
            postIfAlive {
                if (next != null) {
                    // The stored token was rejected — render the re-pair state
                    // for the failing host (its PIN screen, or the failure when
                    // the TV would not start pairing). `next` is authoritative here; the
                    // per-screen guard below does not apply, because a token
                    // rejection is app-wide state, not a per-remote message.
                    render(next)
                    return@postIfAlive
                }
                // A press that neither woke nor failed changed nothing on
                // screen, and writing anyway could clear a message a later
                // press has put there.
                if (woke || message != null) endWake(token, message)
            }
            // A failure drops the taps queued behind it: each would run its
            // own wake and fail the same way.
            message == null && next == null
        })
    }

    /**
     * A press has started waking the TV: the controls go dead until it
     * returns, and Cancel is offered in their place.
     */
    private fun showWaking(screen: RemoteScreen, line: String) {
        wakingLine = line
        screen.setControlsEnabled(false)
        screen.showWaking(line) { cancelWake(screen) }
    }

    /**
     * A press that woke or failed has returned: the controls come back, with
     * its message or none. Drawn into the remote showing now if the screen is
     * still the one the press was made on — the remote may have been re-drawn
     * since, which is why this goes by [token] and not by the views.
     */
    private fun endWake(token: Int, message: String?) {
        val screen = remoteFor(token) ?: return
        wakingLine = null
        screen.setControlsEnabled(true)
        showStatus(screen, message)
    }

    /** The remote showing, if the user is still on the screen [token] names. */
    private fun remoteFor(token: Int): RemoteScreen? = remote.takeIf { token == screenToken }

    /** Show [line] on [screen], remembered so a re-draw can put it back. */
    private fun showStatus(screen: RemoteScreen, line: String?) {
        statusLine = line
        screen.showStatus(line)
    }

    /**
     * Leave the wake. The waking press is interrupted and ends without
     * drawing, and taps queued behind it are dropped, so the controls come
     * back here, at once, rather than when the worker gets to them.
     */
    private fun cancelWake(screen: RemoteScreen) {
        presses.cancelWake()
        hold.stop()
        if (remote === screen) {
            wakingLine = null
            screen.setControlsEnabled(true)
            showStatus(screen, null)
        }
    }

    /**
     * One repeat of a held D-pad direction: a single body-less press.
     *
     * `keyed = false` is deliberate, not drift. Body-less is the whole point of
     * a hold — each press is complete on the TV, so no `keyDown` is ever left
     * waiting for a `keyUp` a dead app would never send. The tap path still
     * sends the keyed pair.
     *
     * Unlike a tap, the controls stay live, because disabling the D-pad under
     * the finger would hide its release. That changes the moment the press has
     * to wake the TV: the hold ends there, and from then on the press behaves
     * like a tap — controls off, "Waking…" with Cancel, and back on when it
     * lands. A failure ends the hold too, with its message.
     *
     * Queued through [presses] like a tap, so Cancel reaches a wake a hold
     * started. Never stacked there: [HoldRepeat] sends no repeat while one is
     * in flight, so every path out of here — landed, failed, cancelled or
     * dropped from the queue — reports [HoldRepeat.repeatDone].
     */
    private fun repeatPress(control: RemoteControl) {
        val screen = remote
        if (screen == null) {
            hold.stop()
            hold.repeatDone()
            return
        }
        val token = screenToken
        showStatus(screen, null)
        val queued = presses.enqueue(
            { startedWaking ->
                var reported: String? = null
                var followUp: PairingFlow.State? = null
                // Set on this worker thread by the wake callback and read back
                // here, on the same thread, once the press returns.
                var woke = false
                try {
                    followUp = flow.press(
                        control.action,
                        keyed = false,
                        onWaking = { line ->
                            woke = true
                            startedWaking()
                            postIfAlive {
                                hold.stop()
                                remoteFor(token)?.let { showWaking(it, line) }
                            }
                        }
                    ) { reported = it }
                } catch (e: InterruptedException) {
                    // Cancelled, or torn down mid-press. A body-less press
                    // leaves nothing held; the hold may start again.
                    Thread.currentThread().interrupt()
                    postIfAlive { hold.repeatDone() }
                    return@enqueue true
                } catch (e: RuntimeException) {
                    reported = getString(R.string.unexpected_failure)
                }
                val message = reported
                val next = followUp
                val endsHold = woke || message != null || next != null
                postIfAlive {
                    hold.repeatDone()
                    if (!endsHold) return@postIfAlive
                    hold.stop()
                    if (next != null) {
                        render(next)
                        return@postIfAlive
                    }
                    endWake(token, message)
                }
                message == null && next == null
            },
            onSkipped = { postIfAlive { hold.repeatDone() } }
        )
        if (!queued) {
            // Shut down between the tick and here; the screen is going away.
            hold.stop()
            hold.repeatDone()
        }
    }

    // --- platform reads --------------------------------------------------

    /**
     * The network the TV can be on: the phone's Wi-Fi or Ethernet, or null
     * when it is on neither. With Wi-Fi off and mobile data on, the active
     * network is the carrier's and has an address of its own, so "has a
     * network" is not the test.
     *
     * A VPN over Wi-Fi is the active network too, and reports the Wi-Fi
     * transport, but its addresses are the tunnel's — on the Redmi 13 with
     * a VPN app (2026-09-28), `tun0` with a single /32, so a scan would have
     * searched the VPN's range and a wake broadcast gone into the tunnel. The
     * Wi-Fi network underneath is the one to read addresses from.
     */
    private fun homeNetwork(manager: ConnectivityManager): Network? {
        val active = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(active) ?: return null
        if (!caps.isHomeTransport()) return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active
        @Suppress("DEPRECATION") // the replacement callback API needs a listener for a one-shot read
        return manager.allNetworks.firstOrNull { network ->
            val c = manager.getNetworkCapabilities(network)
            c != null && c.isHomeTransport() && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: active
    }

    private fun NetworkCapabilities.isHomeTransport() =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    /** Whether the phone's traffic is going through a VPN right now. */
    private fun vpnOn(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /**
     * The /24 this phone sits on, as a CIDR.
     *
     * `ConnectivityManager` rather than `WifiManager`: it answers from
     * `ACCESS_NETWORK_STATE`, which the manifest already declares, while
     * `WifiManager` would drag in location permissions on modern Android.
     *
     * The phone's link prefix is not assumed to be a /24 — one real device was
     * measured on a /22 (`192.0.2.36/22`). `Discovery` only enumerates a
     * single /24 today, so a TV inside the phone's wider subnet but outside the
     * phone's own /24 will not be found. Widening that is `Discovery`'s job.
     *
     * Null unless the phone is on Wi-Fi or Ethernet ([homeNetwork]), so a press
     * off Wi-Fi fails at once instead of running its whole 40 s wake.
     */
    private fun localCidr(): String? {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = homeNetwork(manager) ?: return null
        val link = manager.getLinkProperties(network) ?: return null
        val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val octets = (v4.address as Inet4Address).hostAddress?.split(".") ?: return null
        if (octets.size != 4) return null
        return "${octets[0]}.${octets[1]}.${octets[2]}.0/24"
    }

    /**
     * Where a Wake-on-LAN packet goes: this network's own broadcast address —
     * the kind a packet from a laptop on the same network proved against
     * `the test TV` on 2026-09-25 — and the all-ones broadcast, for when the first
     * cannot be read. Taken from the interface itself, so the prefix is the real
     * one rather than the /24 [localCidr] assumes.
     */
    private fun localBroadcasts(): List<InetAddress> {
        val allOnes = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return listOf(allOnes)
        val network = homeNetwork(manager) ?: return listOf(allOnes)
        val name = manager.getLinkProperties(network)?.interfaceName
            ?: return listOf(allOnes)
        val subnet = try {
            NetworkInterface.getByName(name)?.interfaceAddresses?.mapNotNull { it.broadcast }.orEmpty()
        } catch (e: SocketException) {
            emptyList()
        }
        return (subnet + allOnes).distinct()
    }

    /**
     * Where the transport's one line per request goes: logcat under
     * [REQUEST_LOG_TAG] in a debuggable build, nowhere in a release. It is how a
     * wake is timed on real hardware, by reading the device log filtered to
     * that tag.
     */
    private fun requestLog(): (String) -> Unit =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            { line -> Log.d(REQUEST_LOG_TAG, line) }
        } else {
            {}
        }

    // --- small view helpers ----------------------------------------------

    private fun heading(text: String) = TextView(skinnedContext()).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = PAGE_HEADING_SP
        setTextColor(context.skinColor(R.color.on_surface))
    }

    private fun message(text: String) = TextView(skinnedContext()).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = PAGE_BODY_SP
        setTextColor(context.skinColor(R.color.on_surface))
    }

    private fun actionButton(label: CharSequence, onClick: () -> Unit) =
        skinnedContext().actionButton(label, onClick = onClick)

    private companion object {
        const val MENU_ID_SCAN = 1
        const val MENU_ID_FORGET = 2
        const val MENU_ID_ABOUT = 3
        const val MENU_ID_SWITCH_SUBMENU = 4
        const val MENU_ID_WORD_LABELS = 5
        const val MENU_ID_THEME = 6

        // Dynamic id range for per-TV switch entries. Starts well above the
        // fixed ids so the ranges cannot collide even with more stored TVs
        // than a phone could reasonably show.
        const val MENU_ID_SWITCH_BASE = 100

        // The one UI preference. Its own file rather than `SharedPrefsTokenStore`'s,
        // which is a credential store and should not carry a display setting.
        const val UI_PREFS_NAME = "ui"
        const val PREF_WORD_LABELS = "word_labels"
        const val PREF_INTRO_SEEN = "intro_seen"
        const val PREF_SKIN_DIRECTION = "skin_direction"
        const val PREF_SKIN_MODE = "skin_mode"
        const val PREF_SKIN_PRESET = "skin_preset"

        const val REQUEST_LOG_TAG = "FirehoseTransport"
    }
}
