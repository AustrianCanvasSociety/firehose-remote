package io.github.austriancanvassociety.firehoseremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import io.github.austriancanvassociety.firehoseremote.protocol.Device
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Checks the installed screen and its pixels, including the palette wiring. */
class RemoteAppearanceTest : InstrumentationTestCase() {
    private var activity: Activity? = null
    private val automation get() = instrumentation.getUiAutomation(
        android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val keys = listOf("skin_preset", "skin_mode", "skin_direction", "word_labels")
    private var saved: Map<String, *> = emptyMap<String, Any>()
    private val prefs get() = instrumentation.targetContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private var savedAccessibilitySettings: Map<String, String?>? = null

    private fun setting(namespace: String, key: String, value: String?) {
        require(value == null || value.all { it.isLetterOrDigit() || it in "_./:+-$" })
        // executeShellCommand splits arguments directly; shell quote characters
        // would become part of the stored settings value.
        val command = if (value == null) "settings delete $namespace $key" else
            "settings put $namespace $key $value"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    override fun setUp() {
        super.setUp()
        automation
        saved = prefs.all.filterKeys { it in keys }
        if (name == "testTalkBackTargetsAcrossEveryAppearanceAtLargeText") {
            val resolver = instrumentation.targetContext.contentResolver
            val services = android.provider.Settings.Secure.getString(resolver, "enabled_accessibility_services")
            savedAccessibilitySettings = mapOf(
                "enabled_accessibility_services" to services,
                "accessibility_enabled" to android.provider.Settings.Secure.getString(resolver, "accessibility_enabled"),
                "font_scale" to android.provider.Settings.System.getString(resolver, "font_scale")
            )
            val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
            setting("system", "font_scale", "2.0")
            setting("secure", "enabled_accessibility_services",
                (services.orEmpty().split(':').filter { it.isNotEmpty() } + talkBack).distinct().joinToString(":"))
            setting("secure", "accessibility_enabled", "1")
            val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as android.view.accessibility.AccessibilityManager
            var bound = false
            repeat(50) {
                if (!bound) {
                    bound = manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                        .any { it.id.startsWith("com.google.android.marvin.talkback/") }
                    if (!bound) Thread.sleep(100)
                }
            }
        }
    }

    override fun tearDown() {
        try {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            val edit = prefs.edit()
            keys.forEach { key ->
                when (val value = saved[key]) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    else -> edit.remove(key)
                }
            }
            assertTrue("appearance preferences were not restored", edit.commit())
        } finally {
            savedAccessibilitySettings?.forEach { (key, value) ->
                setting(if (key == "font_scale") "system" else "secure", key, value)
            }
            super.tearDown()
        }
    }

    private fun launch(preset: AccentPreset, mode: SkinMode, direction: SkinDirection, words: Boolean = false) {
        activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
        instrumentation.waitForIdleSync()
        assertTrue(prefs.edit().putString("skin_preset", preset.id)
            .putString("skin_mode", mode.name.lowercase())
            .putString("skin_direction", direction.name.lowercase())
            .putBoolean("word_labels", words).commit())
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(instrumentation.targetContext.packageName, "io.github.austriancanvassociety.firehoseremote.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity = instrumentation.startActivitySync(intent)
        repeat(80) {
            instrumentation.waitForIdleSync()
            if (find("Home")?.height?.let { it > 0 } == true) {
                // Layout can finish before the new window's first frame reaches
                // the display. Wait for events to settle before taking pixels.
                automation.waitForIdle(100, 5000)
                return
            }
            Thread.sleep(100)
        }
        fail("paired remote did not render; this test needs a stored pairing")
    }

    private fun find(description: String): View? {
        var found: View? = null
        instrumentation.runOnMainSync {
            fun walk(view: View) {
                if (view.contentDescription?.toString() == description) found = view
                if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
            activity?.window?.decorView?.let { walk(it) }
        }
        return found
    }

    private fun bounds(view: View): Rect = Rect().also { rect ->
        instrumentation.runOnMainSync {
            val point = IntArray(2)
            view.getLocationOnScreen(point)
            rect.set(point[0], point[1], point[0] + view.width, point[1] + view.height)
        }
    }

    private fun dial(): View {
        var found: View? = null
        instrumentation.runOnMainSync {
            fun walk(view: View) {
                if (view.javaClass.simpleName == "DpadView") found = view
                if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
            walk(activity!!.window.decorView)
        }
        return found ?: throw AssertionError("D-pad missing from the actual screen")
    }

    private fun node(text: String): AccessibilityNodeInfo {
        repeat(50) {
            automation.waitForIdle(100, 5000)
            automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
                ?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("visible control missing: $text")
    }

    private fun click(text: String) {
        var target = node(text)
        while (!target.isClickable) target = target.parent
            ?: throw AssertionError("control cannot be clicked: $text")
        assertTrue("click rejected: $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun openTheme() {
        val overflow = find("More options") ?: throw AssertionError("overflow missing")
        instrumentation.runOnMainSync { overflow.performClick() }
        click("Theme")
    }

    fun testThemeChooserAppliesTogetherCancelsAndPersists() {
        launch(Skin.presetFor("amber"), SkinMode.DARK, SkinDirection.PLATE)
        openTheme()
        assertTrue(node("Plate").isChecked)
        assertTrue(node("Dark").isChecked)
        click("Gradient")
        click("Light")
        click("Amber")
        click("Aqua")
        val bitmap = automation.takeScreenshot()
            ?: throw AssertionError("theme dialog screenshot failed")
        try {
            val directory = File(instrumentation.targetContext.cacheDir, "appearance-review").apply { mkdirs() }
            File(directory, "theme-chooser.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
        click("APPLY")
        assertEquals("gradient", prefs.getString("skin_direction", null))
        assertEquals("light", prefs.getString("skin_mode", null))
        assertEquals("aqua", prefs.getString("skin_preset", null))
        openTheme()
        click("Plate")
        click("Dark")
        click("CANCEL")
        assertEquals("gradient", prefs.getString("skin_direction", null))
        assertEquals("light", prefs.getString("skin_mode", null))
        assertEquals("aqua", prefs.getString("skin_preset", null))
        // Recreate the actual activity without rewriting preferences.
        Thread.sleep(1500)
        instrumentation.runOnMainSync { activity!!.finish() }
        instrumentation.waitForIdleSync()
        activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setClassName(instrumentation.targetContext.packageName, "io.github.austriancanvassociety.firehoseremote.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        openTheme()
        assertTrue(node("Gradient").isChecked)
        assertTrue(node("Light").isChecked)
        node("Aqua")
        click("CANCEL")
    }

    fun testAllFlowPagesUseSharedSpacingAndPalette() {
        launch(Skin.defaultPreset, SkinMode.DARK, SkinDirection.PLATE)
        awaitStartupWork()
        val states = linkedMapOf(
            "first-run" to PairingFlow.State.FirstRun,
            "scan" to PairingFlow.State.Scanning,
            "found" to PairingFlow.State.Discovered(listOf(
                Device("Bedroom TV", "192.0.2.20"),
                Device("Family Room TV", "192.0.2.21"),
                Device("Office TV", "192.0.2.22")
            )),
            "empty" to PairingFlow.State.Discovered(emptyList()),
            "not-answering" to PairingFlow.State.HostDidNotAnswer,
            "refused" to PairingFlow.State.HostRefused,
            "pin" to PairingFlow.State.AwaitingPin(Device("Bedroom TV", "192.0.2.20")),
            "failure" to PairingFlow.State.Failed("Could not connect. Try again.", PairingFlow.State.Cause.TvNotReachable),
            "address-failure" to PairingFlow.State.Failed("Enter the TV's IP address.",
                PairingFlow.State.Cause.MalformedAddress, "192.0.2.22")
        )
        val render = MainActivity::class.java.getDeclaredMethod("render", PairingFlow.State::class.java).apply { isAccessible = true }
        for (mode in SkinMode.values()) for (direction in SkinDirection.values()) {
            val preset = Skin.presetFor(if (mode == SkinMode.DARK) "aqua" else "amber")
            assertTrue(prefs.edit().putString("skin_mode", mode.name.lowercase())
                .putString("skin_direction", direction.name.lowercase())
                .putString("skin_preset", preset.id).commit())
            val palette = Skin.paletteFor(preset, mode, direction)
            for ((name, state) in states) {
                instrumentation.runOnMainSync { render.invoke(activity, state) }
                instrumentation.waitForIdleSync()
                automation.waitForIdle(100, 5000)
                val violations = mutableListOf<String>()
                instrumentation.runOnMainSync {
                    fun inspect(view: View) {
                        if (view is Button && view.height < view.context.dp(48)) violations += "$name: short button ${view.text}"
                        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) {
                            val children = (0 until view.childCount).map { view.getChildAt(it) }.filter { it.visibility != View.GONE }
                            for ((before, after) in children.zipWithNext()) {
                                if (after.top - before.bottom < view.context.dp(PAGE_GAP_DP))
                                    violations += "$name: touching ${before.javaClass.simpleName}/${after.javaClass.simpleName}"
                            }
                        }
                        if (view is android.widget.TextView && view !is Button) {
                            if (view.currentTextColor != palette.ink) violations += "$name: text misses selected palette"
                        }
                        if (view is EditText && view.hintTextColors.defaultColor != palette.ink)
                            violations += "$name: hint misses selected palette"
                        if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
                    }
                    val content = MainActivity::class.java.getDeclaredField("content").apply { isAccessible = true }
                        .get(activity) as View
                    inspect(content)
                }
                assertTrue(violations.joinToString("\n"), violations.isEmpty())
                capture("page-$name-${mode.name.lowercase()}-${direction.name.lowercase()}")
            }
            val busy = MainActivity::class.java.getDeclaredMethod("showBusy", String::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync { busy.invoke(activity, "Scanning for Fire TVs…") }
            capture("page-busy-${mode.name.lowercase()}-${direction.name.lowercase()}")
        }
    }

    private fun capture(name: String) {
        awaitDisplayedFrame()
        automation.waitForIdle(100, 5000)
        val bitmap = automation.takeScreenshot() ?: throw AssertionError("screenshot failed: $name")
        try {
            val directory = File(instrumentation.targetContext.cacheDir, "appearance-review").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    fun testDialogsFollowTheSelectedModeAndPalette() {
        launch(Skin.presetFor("amber"), SkinMode.DARK, SkinDirection.PLATE)
        awaitStartupWork()
        val redraw = MainActivity::class.java.getDeclaredMethod("redraw").apply { isAccessible = true }
        for (mode in SkinMode.values()) {
            val preset = Skin.presetFor(if (mode == SkinMode.DARK) "amber" else "violet")
            val palette = Skin.paletteFor(preset, mode, SkinDirection.PLATE)
            assertTrue(prefs.edit().putString("skin_mode", mode.name.lowercase())
                .putString("skin_preset", preset.id).commit())
            instrumentation.runOnMainSync { redraw.invoke(activity) }
            instrumentation.waitForIdleSync()
            capture("page-remote-${mode.name.lowercase()}")
            val remote = MainActivity::class.java.getDeclaredField("remote").apply { isAccessible = true }
                .get(activity) as RemoteScreen
            instrumentation.runOnMainSync {
                remote.setControlsEnabled(false)
                remote.showWaking("Waking TV…") {}
            }
            capture("page-waking-${mode.name.lowercase()}")
            val cancel = node("Cancel")
            val cancelBounds = Rect().also { cancel.getBoundsInScreen(it) }
            val statusBounds = Rect().also { node("Waking TV…").getBoundsInScreen(it) }
            assertTrue("wake Cancel touches status", cancelBounds.top - statusBounds.bottom >= activity!!.dp(PAGE_GAP_DP))
            instrumentation.runOnMainSync {
                remote.setControlsEnabled(true)
                remote.showStatus("Connection interrupted. Try again.")
            }
            capture("page-status-${mode.name.lowercase()}")
            instrumentation.runOnMainSync { remote.showStatus(null) }
            val overflow = find("More options") ?: throw AssertionError("overflow missing")
            instrumentation.runOnMainSync { overflow.performClick() }
            node("Theme")
            capture("page-overflow-${mode.name.lowercase()}")
            click("About")
            assertDialogPalette("Firehose Remote", palette)
            capture("page-about-${mode.name.lowercase()}")
            click("OK")
            val more = find("More options") ?: throw AssertionError("overflow missing after About")
            instrumentation.runOnMainSync { more.performClick() }
            click("Forget this TV")
            assertDialogPalette("Forget this TV?", palette)
            capture("page-forget-${mode.name.lowercase()}")
            click("CANCEL")
            openTheme()
            assertDialogPalette("Theme", palette)
            capture("page-theme-${mode.name.lowercase()}")
            click("CANCEL")
        }
    }

    fun testThemeNativeRipplesUseTheSelectedAccent() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        for (mode in SkinMode.values()) {
            val preset = Skin.presetFor(if (mode == SkinMode.DARK) "amber" else "violet")
            Thread.sleep(1500)
            launch(preset, mode, SkinDirection.PLATE)
            awaitStartupWork()
            openTheme()
            val palette = Skin.paletteFor(preset, mode, SkinDirection.PLATE)
            var buttons = 0
            instrumentation.runOnMainSync {
                fun inspect(view: View) {
                    if (view is Button && view.visibility == View.VISIBLE && view.rootView !== activity!!.window.decorView) {
                        val ripple = view.background as? android.graphics.drawable.RippleDrawable
                            ?: throw AssertionError("${view.text}: native button ripple is not inspected")
                        assertEquals("${view.text}: native effect color ignores accent",
                            palette.stateLayer, ripple.effectColor.defaultColor)
                        view.isPressed = true
                        buttons++
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { inspect(it) }
            }
            assertEquals("Theme radio/actions were not all inspected", 6, buttons)
            Thread.sleep(350)
            capture("theme-pressed-${mode.name.lowercase()}")
            instrumentation.runOnMainSync {
                fun release(view: View) {
                    view.isPressed = false
                    if (view is ViewGroup) for (index in 0 until view.childCount) release(view.getChildAt(index))
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { release(it) }
            }
            click("CANCEL")
        }
    }

    private fun assertDialogPalette(title: String, palette: SkinPalette) {
        awaitDisplayedFrame()
        val rect = Rect().also { node(title).getBoundsInScreen(it) }
        val bitmap = automation.takeScreenshot() ?: throw AssertionError("dialog screenshot failed")
        try {
            assertEquals("$title: dialog panel misses accent", palette.panel,
                bitmap.getPixel(rect.left - activity!!.dp(8), rect.centerY()))
            var ink = false
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right)
                if (bitmap.getPixel(x, y) == palette.ink) ink = true
            assertTrue("$title: title misses palette", ink)
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitStartupWork() {
        val worker = MainActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(activity) as ExecutorService
        worker.submit {}.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }

    private fun awaitDisplayedFrame() {
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val root = activity!!.window.decorView
            root.postOnAnimation { root.postOnAnimation { drawn.countDown() } }
        }
        assertTrue("screen did not render", drawn.await(3, TimeUnit.SECONDS))
    }

    fun testLiveDiscoveryHasSpacedDeviceBoxes() {
        val preset = Skin.presetFor(saved["skin_preset"] as? String)
        val mode = Skin.modeFor(saved["skin_mode"] as? String, SkinMode.DARK)
        val direction = Skin.directionFor(saved["skin_direction"] as? String, SkinDirection.PLATE)
        launch(preset, mode, direction)
        awaitStartupWork()
        val overflow = find("More options") ?: throw AssertionError("overflow missing")
        instrumentation.runOnMainSync { overflow.performClick() }
        click("Scan for another TV")
        val stateField = MainActivity::class.java.getDeclaredField("currentState").apply { isAccessible = true }
        var discovered = false
        repeat(600) {
            if (!discovered) {
                instrumentation.runOnMainSync { discovered = stateField.get(activity) is PairingFlow.State.Discovered }
                if (!discovered) Thread.sleep(100)
            }
        }
        assertTrue("live scan did not reach its results page", discovered)
        awaitDisplayedFrame()
        val content = MainActivity::class.java.getDeclaredField("content").apply { isAccessible = true }
            .get(activity) as LinearLayout
        val gaps = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            val boxes = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<Button>()
            for ((before, after) in boxes.zipWithNext()) gaps += after.top - before.bottom
        }
        assertTrue("live result boxes touch: $gaps", gaps.isNotEmpty() && gaps.all { it >= activity!!.dp(PAGE_GAP_DP) })
        capture("found-after")
        // Keep the requested real discovery screen open after restoring prefs.
        activity = null
    }

    fun testCompositionAndSymbolsMatchTheReference() {
        launch(Skin.defaultPreset, SkinMode.DARK, SkinDirection.PLATE)
        val home = bounds(find("Home") ?: throw AssertionError("Home missing"))
        val sleep = bounds(find("Sleep") ?: throw AssertionError("Sleep missing"))
        val dial = bounds(dial())
        val bitmap = automation.takeScreenshot()
            ?: throw AssertionError("device screenshot failed")
        try {
            val density = activity!!.resources.displayMetrics.density
            assertTrue("button rows are clustered at the top: $home", home.top > bitmap.height * 0.65)
            assertTrue("bottom row is off screen: $sleep", sleep.bottom < bitmap.height)
            assertTrue("button expanded to fill the viewport: $home", home.height() <= 80 * density)
            assertTrue("dial is not centered in the free area: $dial", dial.centerY() in
                (bitmap.height * 0.35).toInt()..(bitmap.height * 0.65).toInt())
            val ink = 0xFFE8F6FF.toInt()
            val pixels = Rect(home.right, home.bottom, home.left, home.top)
            for (y in home.top until home.bottom) for (x in home.left until home.right) {
                if (bitmap.getPixel(x, y) == ink) {
                    pixels.left = minOf(pixels.left, x)
                    pixels.top = minOf(pixels.top, y)
                    pixels.right = maxOf(pixels.right, x + 1)
                    pixels.bottom = maxOf(pixels.bottom, y + 1)
                }
            }
            assertTrue("Home symbol was not drawn", pixels.width() > 0)
            assertTrue("Home symbol fills its touch target: $pixels", pixels.width() <= 20 * density && pixels.height() <= 20 * density)
            assertEquals("Select disc is covered by an oversized mark", 0xFF17E8FF.toInt(),
                bitmap.getPixel(dial.centerX(), dial.centerY() - (20 * density).toInt()))
        } finally {
            bitmap.recycle()
        }
    }

    fun testEveryPaletteReachesTheWindowButtonsAndDial() {
        for (preset in Skin.presets) for (mode in SkinMode.values()) for (direction in SkinDirection.values()) {
            // Relaunches read capabilities from the TV; keep those requests spaced.
            Thread.sleep(1500)
            launch(preset, mode, direction)
            val home = bounds(find("Home") ?: throw AssertionError("Home missing"))
            val dial = bounds(dial())
            val palette = Skin.paletteFor(preset, mode, direction)
            val bitmap = automation.takeScreenshot()
                ?: throw AssertionError("device screenshot failed")
            try {
                val tag = "${preset.id}/$mode/$direction"
                val captures = File(instrumentation.targetContext.cacheDir, "appearance-review").apply { mkdirs() }
                File(captures, "${preset.id}-${mode.name.lowercase()}-${direction.name.lowercase()}.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                fun background(y: Int): Int = if (direction == SkinDirection.PLATE) palette.ground
                    else Skin.gradientAt(palette, y.toFloat() / bitmap.height)
                fun close(expected: Int, actual: Int): Boolean = listOf(0, 8, 16).all { shift ->
                    kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 4
                }
                // Gutter pixels sample the viewport, beyond all control surfaces.
                for (fraction in listOf(0.15f, 0.5f, 0.8f)) {
                    val y = (bitmap.height * fraction).toInt()
                    assertTrue("$tag: viewport palette at $fraction; expected ${background(y)} actual ${bitmap.getPixel(2, y)}",
                        close(background(y), bitmap.getPixel(2, y)))
                }
                val keyY = home.top + home.height() / 3
                assertTrue("$tag: button fill", close(Skin.composite(palette.keyFill, background(keyY)), bitmap.getPixel(home.centerX(), keyY)))
                val density = activity!!.resources.displayMetrics.density
                assertEquals("$tag: Select disc", palette.select,
                    bitmap.getPixel(dial.centerX(), dial.centerY() - (20 * density).toInt()))
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun testDpadReportsFocusedVirtualNodeAfterAction() {
        launch(Skin.defaultPreset, SkinMode.DARK, SkinDirection.PLATE)
        awaitStartupWork()
        fun flatten(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            return listOf(root) + (0 until root.childCount).flatMap { flatten(root.getChild(it)) }
        }
        val target = flatten(automation.rootInActiveWindow)
            .single { it.contentDescription?.toString() == "Up" }
        assertTrue("Up rejects accessibility focus", target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        instrumentation.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: throw AssertionError("D-pad accepts focus but exposes no focused virtual node")
        assertEquals("Up", focused.contentDescription?.toString())
        assertTrue("focused D-pad node does not report focus", focused.isAccessibilityFocused)
        assertTrue("focused D-pad node has no clear-focus action",
            focused.actions and AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS != 0)
        assertTrue(focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS))
        instrumentation.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        assertNull("D-pad retained focus after clearing", automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
    }

    fun testTalkBackTargetsAcrossEveryAppearanceAtLargeText() {
        val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        automation
        assertTrue("TalkBack must be enabled for this live check",
            manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                .any { it.id.startsWith("com.google.android.marvin.talkback/") })
        assertEquals("run this check at font scale 2.0", 2f,
            android.provider.Settings.System.getFloat(instrumentation.targetContext.contentResolver, "font_scale"))
        val names = listOf("Up", "Down", "Left", "Right", "Select", "Options", "Home", "Back",
            "Play/Pause", "Rewind", "Forward", "Sleep", "More options", "Vol +", "Mute", "Vol −")
        fun flatten(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            return listOf(root) + (0 until root.childCount).flatMap { flatten(root.getChild(it)) }
        }
        for (preset in Skin.presets) for (mode in SkinMode.values()) for (direction in SkinDirection.values()) {
            for (words in listOf(false, true)) {
                Thread.sleep(1500)
                launch(preset, mode, direction, words)
                awaitStartupWork()
                awaitDisplayedFrame()
                val tag = "${preset.id}/$mode/$direction/words=$words"
                val tree = flatten(automation.rootInActiveWindow)
                val targets = tree.filter { it.isClickable && it.isEnabled }
                for (name in names) {
                    val target = targets.singleOrNull { it.contentDescription?.toString() == name }
                        ?: throw AssertionError("$tag: missing or duplicated named control $name")
                    val rect = Rect().also { target.getBoundsInScreen(it) }
                    assertTrue("$tag: $name is not visible: $rect", target.isVisibleToUser && !rect.isEmpty)
                    assertTrue("$tag: $name does not expose click",
                        target.actions and AccessibilityNodeInfo.ACTION_CLICK != 0)
                    assertTrue("$tag: $name cannot receive accessibility focus",
                        target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) || target.isAccessibilityFocused)
                    target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                }
                for (target in targets) {
                    assertTrue("$tag: unnamed clickable control",
                        !target.contentDescription.isNullOrBlank() || !target.text.isNullOrBlank())
                }
                if (words) for (name in names.drop(5)) {
                    val view = find(name) ?: throw AssertionError("$tag: $name view missing")
                    val full = bounds(view)
                    val visible = Rect()
                    var fits = false
                    instrumentation.runOnMainSync {
                        val text = view as android.widget.TextView
                        fits = view.getGlobalVisibleRect(visible) && full == visible &&
                            text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom <= text.height
                    }
                    assertTrue("$tag: $name word label is clipped", fits)
                }
                if (preset.id == "aqua") capture("accessible-${mode.name.lowercase()}-${direction.name.lowercase()}-${if (words) "words" else "symbols"}")
            }
        }
    }

    fun testStoreScreenshots() {
        val scenarios = listOf(
            Triple(SkinMode.DARK, SkinDirection.PLATE, false),
            Triple(SkinMode.LIGHT, SkinDirection.PLATE, true),
            Triple(SkinMode.DARK, SkinDirection.GRADIENT, false)
        )
        for ((index, scenario) in scenarios.withIndex()) {
            Thread.sleep(1500)
            launch(Skin.defaultPreset, scenario.first, scenario.second, scenario.third)
            awaitStartupWork()
            // Only the visible title changes; the saved pairing is untouched.
            instrumentation.runOnMainSync {
                fun anonymize(view: View) {
                    if (view is android.widget.TextView && view !is Button && !view.text.isNullOrEmpty()
                        && view.contentDescription.isNullOrEmpty()) view.text = "Living Room TV"
                    if (view is ViewGroup) for (child in 0 until view.childCount) anonymize(view.getChildAt(child))
                }
                anonymize(activity!!.window.decorView)
            }
            capture("store-${index + 1}")
        }
    }

    fun testDrawnMarksAtSmallDensity() {
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).use { String(it.readBytes()) }
        val density = shell("wm density")
        val override = Regex("Override density: (\\d+)").find(density)?.groupValues?.get(1)
        try {
            shell("wm density 120")
            for (mode in SkinMode.values()) for (direction in SkinDirection.values()) {
                Thread.sleep(1500)
                launch(Skin.defaultPreset, mode, direction)
                awaitStartupWork()
                awaitDisplayedFrame()
                assertEquals("small density was not applied", 120, activity!!.resources.displayMetrics.densityDpi)
                capture("density-120-${mode.name.lowercase()}-${direction.name.lowercase()}")
                val bitmap = automation.takeScreenshot() ?: throw AssertionError("small-density screenshot failed")
                try {
                    for (name in listOf("Home", "Back", "Play/Pause", "Rewind", "Forward", "Sleep", "Mute")) {
                        val view = find(name) ?: throw AssertionError("$name missing")
                        val rect = bounds(view)
                        val half = view.context.dp(9)
                        val background = bitmap.getPixel(rect.left + view.context.dp(4), rect.top + view.context.dp(4))
                        var pixels = 0
                        // Small strokes are antialiased; their pixels need visible
                        // contrast, rather than an exact opaque paint color.
                        for (y in rect.centerY() - half until rect.centerY() + half)
                            for (x in rect.centerX() - half until rect.centerX() + half)
                                if (Contrast.ratio(bitmap.getPixel(x, y), background) >= 3.0) pixels++
                        assertTrue("$name mark disappeared at density 120", pixels >= 4)
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            shell(if (override == null) "wm density reset" else "wm density $override")
        }
    }

    fun testLargeWordLabelsFitTheirControls() {
        val original = android.provider.Settings.System.getString(
            instrumentation.targetContext.contentResolver, "font_scale")
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command)
            ).use { it.readBytes() }
        }
        try {
            shell("settings put system font_scale 2.0")
            launch(Skin.presetFor("amber"), SkinMode.LIGHT, SkinDirection.GRADIENT, words = true)
            for (name in listOf("Home", "Back", "Play/Pause", "Rewind", "Forward", "Sleep", "Options", "More options")) {
                val view = find(name) ?: throw AssertionError("$name missing")
                val full = bounds(view)
                val visible = Rect()
                var onScreen = false
                var textFits = false
                instrumentation.runOnMainSync {
                    onScreen = view.getGlobalVisibleRect(visible)
                    val text = view as android.widget.TextView
                    textFits = text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom <= text.height
                }
                assertTrue("$name is off screen", onScreen)
                assertEquals("$name is clipped by its row", full, visible)
                assertTrue("$name text is clipped inside its control", textFits)
            }
            find("Mute")?.let { view ->
                var lines = 0
                instrumentation.runOnMainSync {
                    lines = (view as android.widget.TextView).layout.lineCount
                }
                assertEquals("Mute broke in the middle of the word", 1, lines)
            }
        } finally {
            shell(if (original == null) "settings delete system font_scale" else "settings put system font_scale $original")
        }
    }
}
