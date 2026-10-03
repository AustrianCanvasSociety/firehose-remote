package io.github.austriancanvassociety.firehoseremote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.austriancanvassociety.firehoseremote.R
import io.github.austriancanvassociety.firehoseremote.protocol.Capabilities

/**
 * Which device capability a control depends on before it may be drawn.
 *
 * [NONE] is "always drawable". The other two are the halves of the capability
 * read that `GET /v1/FireTV` answers (`docs/protocol.md § 2`, "Volume, mute and
 * power") — the device's own statement about what it can do.
 */
enum class Gate { NONE, VOLUME, POWER }

/**
 * One control on the remote: what it says, what it sends, and whether it ships.
 *
 * This exists because a control used to be written down three times — a string
 * resource, a literal in `RemoteScreen.control(...)`, and membership in a keyed
 * set held by the client — so the three could disagree, and the keyed set failed
 * *silently* when it drifted: a keyed action sent body-less looks exactly like a
 * TV that ignored the press. This is the one place a control is written.
 *
 * @param action the action string the request carries, and it is the string the
 *   vendor app was captured sending — never one authored by analogy to a
 *   neighbouring control. `docs/protocol.md § 2`, Control coverage, is where
 *   each of these is accounted for.
 * @param keyed true for controls that carry a press *duration* (keyDown, then
 *   keyUp after a measured gap) rather than one discrete body-less POST.
 * @param present true only for controls observed working on real hardware. A
 *   control whose effect was never seen stays absent however sound its wire
 *   shape looks — a button present and broken is worse than one absent.
 * @param repeatsWhileHeld true for the four D-pad directions only: held, they
 *   repeat as body-less presses ([HoldRepeat]). OK stays one press, because a
 *   held OK is a long press on a Fire TV. Rewind and Forward stay one press,
 *   because a `scan` starts a shuttle that each press speeds up and only Play
 *   stops (`.22`, 2026-09-28).
 */
data class RemoteControl(
    val labelRes: Int,
    val action: String,
    val keyed: Boolean,
    val present: Boolean,
    val gate: Gate = Gate.NONE,
    val repeatsWhileHeld: Boolean = false
)

/**
 * The D-pad's four directions, **indexed by quadrant**.
 *
 * The donut is drawn, not laid out, so these have no rows to live in — but they
 * stay [RemoteControl]s so the press path and the disable guard see exactly the
 * descriptors the bands do. The index is the quadrant the hit-test returns:
 * `floor(((angle + 45) mod 360) / 90)` runs clockwise from 3 o'clock, so 0 is
 * right, then down, left, up.
 */
val DPAD_DIRECTIONS: List<RemoteControl> = listOf(
    RemoteControl(R.string.remote_right_button, "dpad_right", keyed = true, present = true, repeatsWhileHeld = true),
    RemoteControl(R.string.remote_down_button, "dpad_down", keyed = true, present = true, repeatsWhileHeld = true),
    RemoteControl(R.string.remote_left_button, "dpad_left", keyed = true, present = true, repeatsWhileHeld = true),
    RemoteControl(R.string.remote_up_button, "dpad_up", keyed = true, present = true, repeatsWhileHeld = true)
)

/** The circle at the donut's centre, which is its own target, not a quadrant. */
val DPAD_CENTRE: RemoteControl =
    RemoteControl(R.string.remote_select_button, "select", keyed = true, present = true)

/**
 * The Options control, rendered as an `≡` icon in the column left of the D-pad.
 *
 * Wire action is still `menu` — the "Menu" → "Options" rename lives in
 * [R.string.remote_menu_button] alone, per `docs/protocol.md § 2` — the vendor
 * captured `menu` and that is the string that goes on the wire. Kept out of
 * [REMOTE_ROWS] because the control zone owns its rendering (left column,
 * mirroring the volume rocker on the right), but still routed through the same
 * `onPress` callback so the press path is one code path.
 */
val OPTIONS_CONTROL: RemoteControl =
    RemoteControl(R.string.remote_menu_button, "menu", keyed = false, present = true)

/**
 * The volume rocker's three touch zones, in vertical order top-to-bottom.
 *
 * Kept out of [REMOTE_ROWS] because the rocker is one control-shaped view with
 * three internal zones rather than three side-by-side buttons — the pill reads
 * as a rocker in the way three separate buttons would not. Each zone still
 * carries its own [RemoteControl] descriptor so the wire shape is written down
 * once and the press path is the same as every other control.
 */
val ROCKER_CONTROLS: List<RemoteControl> = listOf(
    RemoteControl(R.string.remote_volume_up_button, "volume_up", keyed = false, present = true, gate = Gate.VOLUME),
    RemoteControl(R.string.remote_mute_button, "mute", keyed = false, present = true, gate = Gate.VOLUME),
    RemoteControl(R.string.remote_volume_down_button, "volume_down", keyed = false, present = true, gate = Gate.VOLUME)
)

/**
 * The power control, kept as a decision-record.
 *
 * `power` is present-but-absent on purpose: its wire shape was captured, but no
 * capture ever recorded what it *did*, and the control-coverage table records
 * that rather than guessing. It lives on its own here rather than in
 * [REMOTE_ROWS] so removing the top row's volume-and-power band could not
 * silently drop the record too.
 */
val POWER_CONTROL: RemoteControl =
    RemoteControl(R.string.remote_power_button, "power", keyed = false, present = false, gate = Gate.POWER)

/**
 * The remote's two 3-column button rows, in draw order.
 *
 * Row 1 is a navigation-and-media band; Row 2 is a media-and-state band. The
 * rocker and Options control live outside these rows because their rendering is
 * different from a row of equal-weight buttons — but every one is still a
 * [RemoteControl] so the press path is the same across the screen.
 *
 * Step 7 shrank these to two 3-cell rows from the pre-refactor row-of-four and
 * row-of-seven. Row 1 lost `menu` (moved to the top-bar `≡`); row 2 lost
 * volume-and-mute (moved to the vertical rocker) and `power` (kept as
 * [POWER_CONTROL], still unshipped).
 */
val REMOTE_ROWS: List<List<RemoteControl>> = listOf(
    listOf(
        RemoteControl(R.string.remote_home_button, "home", keyed = false, present = true),
        RemoteControl(R.string.remote_back_button, "back", keyed = false, present = true),
        RemoteControl(R.string.remote_play_pause_button, PairingFlow.ACTION_PLAY, keyed = false, present = true)
    ),
    listOf(
        RemoteControl(R.string.remote_rewind_button, PairingFlow.ACTION_SCAN_BACK, keyed = false, present = true),
        RemoteControl(R.string.remote_forward_button, PairingFlow.ACTION_SCAN_FORWARD, keyed = false, present = true),
        RemoteControl(R.string.remote_sleep_button, "sleep", keyed = false, present = true)
    )
)

/**
 * Every control the remote knows, D-pad + Options + rocker + power + rows.
 *
 * Assembled from the individual structures rather than a hand-copied flat list,
 * so a control added to one of the halves cannot be silently lost by iterating
 * the wrong half — which is exactly what would have happened to the five D-pad
 * controls the moment they moved out of the rows.
 */
val ALL_CONTROLS: List<RemoteControl> =
    DPAD_DIRECTIONS +
        DPAD_CENTRE +
        OPTIONS_CONTROL +
        ROCKER_CONTROLS +
        POWER_CONTROL +
        REMOTE_ROWS.flatten()

/**
 * The rows to draw, given what the device said about itself.
 *
 * [capabilities] is null when the read did not happen or did not answer. That
 * draws the gated controls rather than hiding them: a failed read is not a
 * verdict, and only the device saying **no** — an explicit `false` /
 * `NotSupported` — is allowed to take a control away. An empty row is dropped so
 * a fully-suppressed band leaves no gap.
 */
fun drawnRows(capabilities: Capabilities?): List<List<RemoteControl>> =
    REMOTE_ROWS
        .map { row -> row.filter { it.isDrawn(capabilities) } }
        .filter { row -> row.isNotEmpty() }

/**
 * The rocker's touch zones, given what the device said about itself.
 *
 * Same rule as [drawnRows]: null / yes leaves the zones intact, explicit-no
 * hides them. Returning an empty list is what tells the layout to omit the
 * rocker entirely rather than draw an empty pill.
 */
fun drawnRocker(capabilities: Capabilities?): List<RemoteControl> =
    ROCKER_CONTROLS.filter { it.isDrawn(capabilities) }

/**
 * Every control on screen for the given capabilities read.
 *
 * The D-pad and Options are always drawn — neither is gated — so they land here
 * unconditionally. The rocker and the rows come through the gate filters.
 * Tests that reason about the drawn set use this rather than reassembling it
 * from the pieces; a control moved between structures cannot then quietly
 * escape the assertion.
 */
fun allDrawnControls(capabilities: Capabilities?): List<RemoteControl> =
    DPAD_DIRECTIONS +
        DPAD_CENTRE +
        OPTIONS_CONTROL +
        drawnRocker(capabilities) +
        drawnRows(capabilities).flatten()

/**
 * Whether a capability read takes any control off a screen drawn before the
 * answer arrived.
 *
 * The paired screen is drawn ungated first and asked second, and only a read
 * that suppresses something is worth rebuilding the view tree for. That
 * decision has to be made over every drawn structure at once: Step 7 moved the
 * volume controls out of the rows into the rocker, and a rows-only comparison
 * then read a device's `false` as "nothing changed", cached it, and left the
 * rocker on screen for the life of the process (observed 2026-09-20 against a
 * stick reporting `isVolumeControlsSupported:false`). [allDrawnControls] is the
 * one place every structure is assembled, so a control moved between them
 * cannot escape this check.
 */
fun capabilitiesTakeSomethingAway(capabilities: Capabilities): Boolean =
    allDrawnControls(capabilities) != allDrawnControls(null)

private fun RemoteControl.isDrawn(capabilities: Capabilities?): Boolean = when {
    !present -> false
    gate == Gate.NONE || capabilities == null -> true
    gate == Gate.VOLUME -> capabilities.volume
    else -> capabilities.power
}

/**
 * The remote: a top-bar with the TV identifier and overflow, a donut D-pad
 * flanked by the Options icon on the left and a vertical volume rocker on the
 * right, and two rows of three buttons beneath.
 *
 * Platform views, like every other screen here — no XML, no dependencies. The
 * donut is the one control drawn rather than laid out, because a ring of four
 * arc quadrants has no rows to sit in; it is drawn by [DpadView] below, and its
 * geometry and hit-test live in [DpadGeometry], where the JVM suite reaches them.
 *
 * This class knows nothing about the network or the protocol. It reports a
 * press by handing the whole [RemoteControl] back and the activity decides what
 * that means, the same split [PairingFlow] uses for the scan-and-pair flow.
 * The overflow menu is anchored on its own icon and its items are wired by the
 * activity — this screen only reports "the user tapped the overflow".
 *
 * The controls are disabled while a press is in flight. Without that, presses
 * queue on the activity's single worker thread, and because a wake can cost
 * seconds a burst of taps would land as a burst of tiles long after the user
 * stopped — see [PairingFlow.press].
 */
class RemoteScreen(
    private val context: Context,
    private val deviceName: String,
    rows: List<List<RemoteControl>>,
    rocker: List<RemoteControl>,
    private val wordLabels: Boolean,
    private val accent: Int = 0,
    gradient: Boolean = false,
    private val onPress: (RemoteControl) -> Unit,
    private val onOverflow: (anchor: View) -> Unit,
    hold: HoldRepeat,
    onRepeat: (RemoteControl) -> Unit
) {

    /**
     * Every clickable view the enable-guard has to reach.
     *
     * The donut is not in here — it is not a [View] with `isEnabled` semantics
     * we hand off, so it answers to the guard on its own in [setControlsEnabled].
     * A control drawn on screen but not tracked here would look enabled mid-press
     * and accept taps that queue up behind a wake, which is the one thing this
     * guard exists to prevent.
     */
    private val controls = mutableListOf<View>()

    private val columnWidth = if (wordLabels) {
        val textPaint = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }.paint
        val widest = rocker.maxOfOrNull { textPaint.measureText(context.getString(it.labelRes)) } ?: 0f
        maxOf(context.dp(ROCKER_WIDTH_DP), kotlin.math.ceil(widest).toInt() + context.dp(12))
    } else context.dp(ROCKER_WIDTH_DP)

    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = PAGE_BODY_SP
        setTextColor(context.skinColor(R.color.on_surface))
        visibility = View.GONE
        // Politely, not assertively: a wake can run 40s, and the line changing
        // is information, not an interruption. Without this the whole wake is
        // silent to anything that cannot read the screen.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    // The centre draws its mark in symbol mode, the same as every other
    // control; word mode keeps the label it has always had.
    private val dpad = DpadView(
        context,
        hold,
        onPress,
        onRepeat,
        if (wordLabels) null else ControlMarks.forAction("select"),
        accent
    )

    /** Leaves a wake; shown only while one runs. Its action is set by [showWaking]. */
    private val cancelWake = context.actionButton(context.getString(R.string.cancel_wake_button)) {}.apply {
        visibility = View.GONE
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // The viewport owns the background so one gradient covers its insets
        // and the space between the header and bottom rows as well as controls.
        if (gradient && context.skin == null) {
            val mode = if ((context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) SkinMode.DARK else SkinMode.LIGHT
            background = SkinContext(context,
                Skin.paletteFor(Skin.defaultPreset, mode, SkinDirection.GRADIENT), SkinDirection.GRADIENT
            ).skinBackground()
        }
        addView(topBar())
        addView(divider(R.color.outline))
        addView(controlZone(rocker), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))
        rows.forEach { cells -> addView(rowView(cells)) }
        addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(12) }
        )
        addView(
            cancelWake,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = context.dp(PAGE_GAP_DP)
            }
        )
    }

    /**
     * Enable or disable every control, for the duration of a wake.
     *
     * The donut is not a [View] with a `Button`'s `isEnabled` semantics, so it is
     * disabled directly. A drawn control that missed this would look enabled
     * mid-press and accept taps that queue up behind a wake.
     */
    fun setControlsEnabled(enabled: Boolean) {
        controls.forEach { it.isEnabled = enabled }
        dpad.isEnabled = enabled
    }

    /** Show a failure under the donut. `null` clears it, and any Cancel with it. */
    fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        cancelWake.visibility = View.GONE
    }

    /**
     * Say a wake is running, with a way out of it. The controls are off for a
     * wake, so Cancel is the one thing on the screen that answers.
     */
    fun showWaking(text: String, onCancel: () -> Unit) {
        showStatus(text)
        cancelWake.setOnClickListener { onCancel() }
        cancelWake.visibility = View.VISIBLE
    }

    // --- top bar ---------------------------------------------------------

    private fun topBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(8), 0, context.dp(8))

        addView(
            TextView(context).apply {
                text = deviceName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.NORMAL)
                setTextColor(context.skinColor(R.color.on_surface))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        addView(iconGlyph(OVERFLOW_LABEL, contentDescRes = R.string.remote_overflow_button) { anchor ->
            onOverflow(anchor)
        })
    }

    /**
     * A 48dp circular icon-button rendered as a text glyph, so the app carries
     * no bitmap or vector-drawable asset. The state layer draws a primary-tinted
     * overlay from the selected palette on press; an [ImageButton]
     * would not display text, and a [Button] would inherit theme padding and
     * bring the tier background with it — a plain [TextView] made clickable is
     * the smallest surface that does what this asks for.
     *
     * @param label the glyph text (Unicode character).
     * @param contentDescRes accessibility description a screen reader speaks.
     * @param onClick called with the button as its argument so the caller can
     *   anchor a popup on it.
     */
    private fun iconGlyph(
        label: String,
        contentDescRes: Int,
        onClick: (View) -> Unit
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(context.skinColor(R.color.on_surface))
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        background = if (contentDescRes == R.string.remote_menu_button) {
            // Keep the full 48dp touch target around a shorter outlined key.
            InsetDrawable(context.tierBackground(), 0, context.dp(8), 0, context.dp(8))
        } else context.skinStateLayer()
        contentDescription = context.getString(contentDescRes)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick(it) }
        // 48dp, not 40: the minimum a touch target may be. Both icons
        // that route through here — Options and the overflow — were the only
        // controls in the app under it.
        //
        // No start margin. The column Options sits in is exactly one target
        // wide — the rocker's width, for symmetry — so 4dp of margin came
        // straight out of the glyph and it measured 44dp on the paired screen.
        // The top bar does not need it either: its weighted title already
        // holds the gap open.
        val size = context.dp(48)
        layoutParams = LinearLayout.LayoutParams(size, size)
        controls += this
    }

    // --- control zone ----------------------------------------------------

    /**
     * The D-pad flanked by the Options icon on the left and the volume rocker
     * on the right, centered horizontally.
     *
     * When [rocker] is empty (the TV said `volume=false`), the rocker is omitted
     * AND a matching-width spacer is drawn on the right so the D-pad stays
     * centered between Options-left and empty-right, per the same "the donut
     * sits on its own" rule the rows follow — extended to preserve horizontal
     * symmetry now that the left column always renders.
     */
    private fun controlZone(rocker: List<RemoteControl>): View = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = View.MeasureSpec.getSize(widthMeasureSpec)
            val size = minOf(context.dp(200), available - 2 * columnWidth - context.dp(24))
                .coerceAtLeast(context.dp(120))
            dpad.layoutParams.width = size
            dpad.layoutParams.height = size
            val columnHeight = maxOf(size, context.dp(ROCKER_HEIGHT_DP))
            getChildAt(0).layoutParams.height = columnHeight
            getChildAt(2).layoutParams.height = columnHeight
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val vPad = context.dp(16)
        setPadding(0, vPad, 0, vPad)

        addView(optionsColumn(), LinearLayout.LayoutParams(columnWidth, context.dp(ROCKER_HEIGHT_DP)).apply {
            marginEnd = context.dp(12)
        })

        addView(dpad, LinearLayout.LayoutParams(context.dp(DpadGeometry.DONUT_SIZE_DP), context.dp(DpadGeometry.DONUT_SIZE_DP)))

        val right: View = if (rocker.isNotEmpty()) rockerView(rocker) else View(context)
        addView(right, LinearLayout.LayoutParams(columnWidth, context.dp(ROCKER_HEIGHT_DP)).apply {
            marginStart = context.dp(12)
        })
    }

    /**
     * The Options icon in its column, vertically centered so it sits at the
     * D-pad's midline regardless of the rocker's height.
     */
    private fun optionsColumn(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(iconGlyph(OPTIONS_LABEL, contentDescRes = R.string.remote_menu_button) {
            onPress(OPTIONS_CONTROL)
        })
    }

    /**
     * The volume rocker: a rounded pill with three touch zones stacked vertically.
     *
     * Rounded corners come from a [GradientDrawable] so the pill has no bitmap
     * asset behind it; each zone's state layer uses the selected palette.
     */
    private fun rockerView(zones: List<RemoteControl>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(ROCKER_CORNER_DP).toFloat()
            setColor(context.skinColor(R.color.surface_container_high))
            // The same hairline every key carries, so the rocker reads as one of
            // the remote's keys rather than as a panel laid over them.
            setStroke(context.dp(1), context.skinColor(R.color.outline))
        }
        clipToOutline = true
        setPadding(0, context.dp(10), 0, context.dp(10))

        zones.forEachIndexed { index, zone ->
            // A fainter rule than the key edge, so the zones read as parts of
            // one control rather than as three keys stacked.
            if (index > 0) addView(divider(R.color.rocker_divider))
            addView(rockerZone(zone), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
    }

    private fun rockerZone(zone: RemoteControl): View {
        // Mute draws its mark in symbol mode. `＋` and `−` stay typed in both
        // modes: unambiguous, universally covered, and carrying no weight worth
        // reproducing — as do the device name and every word-mode label.
        val mark = if (wordLabels) null else ControlMarks.forAction(zone.action)
        if (mark != null) {
            return ControlMarkView(
                mark,
                R.color.on_surface,
                context.skinStateLayer()
            ).apply {
                contentDescription = context.getString(zone.labelRes)
                setOnClickListener { onPress(zone) }
                controls += this
            }
        }
        return rockerWord(zone)
    }

    private fun rockerWord(zone: RemoteControl): TextView = TextView(context).apply {
        text = when {
            wordLabels -> context.getString(zone.labelRes)
            zone.action == "volume_up" -> ROCKER_PLUS
            zone.action == "volume_down" -> ROCKER_MINUS
            else -> context.getString(zone.labelRes)
        }
        gravity = Gravity.CENTER
        setTextColor(context.skinColor(R.color.on_surface))
        // The cell is 48dp wide, so a word needs the smaller size to sit in it.
        val sp = when {
            wordLabels -> 11f
            zone.action == "mute" -> 11f
            else -> 18f
        }
        setTextSize(if (wordLabels) TypedValue.COMPLEX_UNIT_SP else TypedValue.COMPLEX_UNIT_DIP, sp)
        setSingleLine()
        contentDescription = context.getString(zone.labelRes)
        background = context.skinStateLayer()
        isClickable = true
        isFocusable = true
        setOnClickListener { onPress(zone) }
        controls += this
    }

    // --- rows ------------------------------------------------------------

    /** One horizontal band: equal-weight buttons stretched across the width. */
    private fun rowView(cells: List<RemoteControl>): LinearLayout = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val gaps = context.dp(8) * (cells.size - 1)
            val cellWidth = ((available - gaps) / cells.size).coerceAtLeast(0)
            var rowHeight = context.dp(56)
            // Measure the actual wrapped text at its final width first. All
            // buttons then share the tallest label's height, without baseline
            // offsets or MATCH_PARENT depending on an as-yet unmeasured row.
            for (index in 0 until childCount step 2) {
                val child = getChildAt(index)
                child.measure(View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            for (index in 0 until childCount step 2) getChildAt(index).layoutParams.height = rowHeight
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        val gap = context.dp(8)
        setPadding(0, 0, 0, gap)
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                addView(View(context), LinearLayout.LayoutParams(gap, 1))
            }
            // The row measures the labels before assigning a shared height;
            // large text can grow the row while retaining the touch floor.
            addView(
                button(cell).apply { minimumHeight = context.dp(56) },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }
    }

    /**
     * One of the remote's keys, in the app's one button style.
     *
     * The style itself lives in `Widgets.kt`. This screen is where it came
     * from, not where it belongs — the start, scan and PIN screens draw buttons
     * too, and a second copy of the style would drift from this one. What stays
     * local is the tier this key sits on and the control bookkeeping.
     */
    private fun button(spec: RemoteControl): View {
        val (bgColor, fgColor) = tierColors(spec.action)
        // Symbol mode draws every control it has a mark for; word mode types
        // every one. A control with no mark — none in the rows today — keeps its
        // word in both modes.
        val mark = if (wordLabels) null else ControlMarks.forAction(spec.action)
        val view: View = if (mark != null) {
            ControlMarkView(mark, fgColor, context.tierBackground(bgColor)).apply {
                setOnClickListener { onPress(spec) }
            }
        } else {
            context.actionButton(context.getString(spec.labelRes), bgColor, fgColor) { onPress(spec) }
        }
        // Spoken from the word resource whichever way it is drawn: the drawn
        // path must never become the only source of the name.
        view.contentDescription = context.getString(spec.labelRes)
        controls += view
        return view
    }

    /**
     * The M3 role pair for a row-button, keyed by wire action.
     *
     * Navigation + device-state controls land on the secondary container;
     * media-transport controls land on the tertiary container. The mapping is
     * one small set here rather than a `role` field on [RemoteControl] because
     * the role-vs-action link only matters at draw time, and adding a field
     * would ripple through every test that constructs a [RemoteControl].
     */
    private fun tierColors(action: String): Pair<Int, Int> = when (action) {
        in SECONDARY_ACTIONS ->
            R.color.secondary_container to R.color.on_secondary_container
        else ->
            R.color.tertiary_container to R.color.on_tertiary_container
    }

    // --- helpers ---------------------------------------------------------

    /** A 1dp horizontal rule painted in [colorRes]. Used for the top-bar bottom border AND for the rocker's inter-zone separators. */
    private fun divider(colorRes: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)
        )
        setBackgroundColor(context.skinColor(colorRes))
    }

    /**
     * One control mark, drawn.
     *
     * The geometry is data — [ControlMarks] holds it, the same way
     * [DpadGeometry] holds the donut's — so nothing here is on the JVM test's
     * blind side except the drawing itself. Scaling is from the mark's authored
     * [ControlMarks.VIEWBOX] grid onto the square the view is given, so a mark
     * draws the same at any size and any density.
     */
    private inner class ControlMarkView(
        private val mark: ControlMarks.Mark,
        fgColorRes: Int,
        background: Drawable
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.skinColor(fgColorRes)
            strokeWidth = ControlMarks.STROKE_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val path = Path()

        init {
            this.background = background
            isClickable = true
            isFocusable = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // A plain View takes the entire AT_MOST height. These controls
            // need their minimum height in a row and the exact zone height
            // when the rocker gives them one.
            setMeasuredDimension(
                resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(suggestedMinimumHeight, heightMeasureSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            // Ink size is independent of the touch target's dimensions.
            val side = minOf(width.toFloat(), height.toFloat(),
                ControlMarks.GLYPH_DP * context.resources.displayMetrics.density)
            if (side <= 0f) return
            canvas.save()
            canvas.translate((width - side) / 2f, (height - side) / 2f)
            canvas.scale(side / ControlMarks.VIEWBOX, side / ControlMarks.VIEWBOX)
            drawMarkShapes(canvas, mark, paint, path)
            canvas.restore()
        }
    }

    private companion object {
        const val OPTIONS_LABEL = "≡"        // ≡ triple bar
        const val OVERFLOW_LABEL = "⋯"       // ⋯ midline horizontal ellipsis
        const val ROCKER_PLUS = "＋"          // ＋ fullwidth plus
        const val ROCKER_MINUS = "−"         // − minus sign
        const val ROCKER_WIDTH_DP = 48
        const val ROCKER_HEIGHT_DP = 168
        const val ROCKER_CORNER_DP = 24

        /** Wire actions that render on the secondary-container tier. */
        val SECONDARY_ACTIONS = setOf("home", "back", "sleep")
    }
}

/**
 * The D-pad as a ring of four arc quadrants around a centre circle.
 *
 * Drawn rather than laid out, and computed rather than assembled: one pass
 * derives every arc from the same bounds, so the four cannot drift apart the
 * way four hand-built segment views would.
 *
 * [DPAD_DIRECTIONS] is indexed by quadrant, so the hit-test's quadrant *is* the
 * list index — there is no second mapping to keep in step with the first. All
 * four are `present` and ungated by construction, so there is no
 * suppressed-direction case to draw.
 *
 * Colours come from the M3 tokens defined in `values/colors.xml` and
 * `values-night/colors.xml`: quadrant fills use `@color/surface_container_high`,
 * the centre circle uses `@color/primary`, and its "OK" text uses
 * `@color/on_primary`. Night-mode resolution swaps each `@color/…` reference
 * automatically, so the palette lives in one place per theme rather than
 * threaded through this file. The geometry — sizes, arcs and hit-test — lives
 * in [DpadGeometry]; this class paints it and routes touches through it.
 *
 * `@SuppressLint("ViewConstructor")`: the check exists so the layout editor can
 * inflate a custom view from XML. This app has no XML layouts — every view is
 * built in code — so an `AttributeSet` constructor would be dead weight that
 * nothing could ever call.
 */
/** The play/pause bar's corner, in viewBox units, matching the reference. */
private const val MARK_BAR_CORNER = 1f

/** The dial's rim thickness — the reference's 2px border, in dp. */
private const val DIAL_EDGE_DP = 2

/** The reference's small Select symbol, independent of the disc's size. */
private const val SELECT_MARK_DP = 20f

/**
 * Draws [mark]'s shapes onto [canvas], in [ControlMarks.VIEWBOX] units.
 *
 * One renderer for every drawn mark — the row keys, the rocker's mute zone and
 * the D-pad centre all land here — so a primitive added to [ControlMarks.Shape]
 * has one place to be handled rather than three that drift apart.
 *
 * [paint] and [path] are the caller's, so a view holds one of each across draws
 * instead of allocating per frame.
 */
private fun drawMarkShapes(
    canvas: Canvas,
    mark: ControlMarks.Mark,
    paint: Paint,
    path: Path
) {
    fun trace(points: List<ControlMarks.Pt>, close: Boolean) {
        path.reset()
        points.forEachIndexed { index, pt ->
            if (index == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        if (close) path.close()
    }

    for (shape in mark.shapes) {
        when (shape) {
            is ControlMarks.Stroke -> {
                trace(shape.points, close = false)
                paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint)
            }
            is ControlMarks.Polygon -> {
                trace(shape.points, close = true)
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            is ControlMarks.Disc -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(shape.cx, shape.cy, shape.radius, paint)
            }
            is ControlMarks.Ring -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(shape.cx, shape.cy, shape.radius, paint)
            }
            is ControlMarks.Bar -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    RectF(shape.left, shape.top, shape.right, shape.bottom),
                    MARK_BAR_CORNER,
                    MARK_BAR_CORNER,
                    paint
                )
            }
        }
    }
}

@SuppressLint("ViewConstructor")
private class DpadView(
    context: Context,
    private val hold: HoldRepeat,
    private val onPress: (RemoteControl) -> Unit,
    private val onRepeat: (RemoteControl) -> Unit,
    private val selectMark: ControlMarks.Mark? = null,
    accent: Int = 0
) : View(context) {

    // The donut's geometry, read off the live bounds rather than cached, and
    // shared with the hit-test through [DpadGeometry]: a band drawn in one
    // place and hit in another is a control that lies about where it is.
    private val centreX: Float get() = width / 2f
    private val centreY: Float get() = height / 2f
    private val outerRadius: Float get() = DpadGeometry.outerRadius(width, height)
    private val discRadius: Float get() = DpadGeometry.discRadius(width, height)

    // The dial's fill is the same panel the keys use, so the D-pad reads as one
    // of the remote's surfaces rather than as a thing laid over them.
    private val dialFill: Int = context.skin?.dialFill ?: context.skinColor(R.color.surface_container_high)
    private val dialEdge: Int = context.skinColor(R.color.dpad_ring)
    private val arrowColor: Int = context.skinColor(R.color.on_surface_variant)
    private val discColor: Int = if (accent != 0) accent else context.skinColor(R.color.primary)
    private val labelColor: Int = context.skinColor(R.color.on_primary)
    private val discArmedColor: Int = Skin.blend(discColor, labelColor, 0.12f)

    private val edgeWidth: Float = context.dp(DIAL_EDGE_DP).toFloat()

    private val dial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = edgeWidth
    }

    private val arrows = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        // SP, not dp — the label has to grow with the font scale like every
        // other label, which is what the constant's name always claimed.
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LABEL_SP.toFloat(),
            context.resources.displayMetrics
        )
        isFakeBoldText = true
    }

    /** Paints the drawn Select mark. Unused when [selectMark] is null. */
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        strokeWidth = ControlMarks.STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val markPath = Path()

    /** The control under the finger, held so a drag off it cancels the press. */
    private var armed: RemoteControl? = null

    init {
        // Handling touches directly leaves this not clickable by default. The
        // flag no longer carries the accessibility tree — that moved to the
        // virtual node per zone — but the touch path still expects a clickable
        // view to receive the events it resolves by hand.
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        // A stroke straddles its path, so the rim is drawn on a circle inset by
        // half the edge width. That lands its outer edge exactly on
        // `outerRadius` rather than half an edge past it.
        val rim = outerRadius - edgeWidth / 2f

        dial.color = dialFill
        canvas.drawCircle(centreX, centreY, rim, dial)

        edge.color = dialEdge
        canvas.drawCircle(centreX, centreY, rim, edge)

        // The directions are told by four arrow marks inside the dial, not by
        // gaps in a band — so the whole circle is live and the marks only show
        // which way each quadrant goes.
        DPAD_DIRECTIONS.forEachIndexed { quadrant, control ->
            arrows.color = if (armed === control) dialEdge else arrowColor
            drawArrow(canvas, quadrant)
        }

        disc.color = if (armed === DPAD_CENTRE) discArmedColor else discColor
        canvas.drawCircle(centreX, centreY, discRadius, disc)

        // The centre reads as its word in word mode and as the drawn
        // ring-and-dot in symbol mode — the same substitution every other
        // control makes, so `Word labels` switches between two coherent
        // rendering systems rather than two paths inside one.
        val mark = selectMark
        if (mark != null) {
            drawSelectMark(canvas, mark)
        } else {
            // Baseline is derived from the ascent/descent so any label height
            // still centres, and the label is painted last so it lands on top
            // of the filled circle.
            label.color = labelColor
            val baseline = centreY - (label.descent() + label.ascent()) / 2f
            canvas.drawText(SELECT_LABEL, centreX, baseline, label)
        }
    }

    /**
     * One of the four arrow marks, pointing outward from the centre.
     *
     * Drawn rather than typed: the dial's whole point is that no control mark
     * depends on the system font, and an arrow the device font lacked would draw
     * as a tofu box on the app's primary navigation control.
     */
    private fun drawArrow(canvas: Canvas, quadrant: Int) {
        val radians = Math.toRadians(90.0 * quadrant)
        val outX = Math.cos(radians).toFloat()
        val outY = Math.sin(radians).toFloat()
        val sideX = -outY
        val sideY = outX

        val radius = outerRadius
        val distance = radius * DpadGeometry.ARROW_DISTANCE_FRACTION
        val halfHeight = radius * DpadGeometry.ARROW_HEIGHT_FRACTION / 2f
        val halfBase = radius * DpadGeometry.ARROW_HALF_BASE_FRACTION

        val tipX = centreX + outX * (distance + halfHeight)
        val tipY = centreY + outY * (distance + halfHeight)
        val baseX = centreX + outX * (distance - halfHeight)
        val baseY = centreY + outY * (distance - halfHeight)

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(baseX + sideX * halfBase, baseY + sideY * halfBase)
        arrowPath.lineTo(baseX - sideX * halfBase, baseY - sideY * halfBase)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrows)
    }

    /**
     * The Select mark, centered at the reference's fixed ink size.
     */
    private fun drawSelectMark(canvas: Canvas, mark: ControlMarks.Mark) {
        val scale = minOf(SELECT_MARK_DP * context.resources.displayMetrics.density, discRadius * 2f) / ControlMarks.VIEWBOX
        canvas.save()
        canvas.translate(centreX, centreY)
        canvas.scale(scale, scale)
        canvas.translate(-ControlMarks.VIEWBOX / 2f, -ControlMarks.VIEWBOX / 2f)
        markPaint.color = labelColor
        drawMarkShapes(canvas, mark, markPaint, markPath)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                val target = controlAt(event.x, event.y) ?: return false
                armed = target
                // A direction held past the long-press timeout starts repeating
                // here; released sooner, it is the tap below.
                hold.down(target, onRepeat)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off the control ends the press there — mid-hold, the
                // repeats stop with the finger's arrival somewhere else, not
                // at release.
                val target = armed ?: return true
                if (controlAt(event.x, event.y) !== target) {
                    hold.stop()
                    armed = null
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val target = armed ?: return false
                armed = null
                invalidate()
                // Re-check on the way out, so a drag that ends away from the
                // arc cancels rather than firing — the same "release on the
                // control you are on" rule a Button applies. A hold has already
                // sent its presses, so its release sends nothing.
                if (controlAt(event.x, event.y) === target) {
                    val tap = hold.up() ?: return true
                    performClick()
                    onPress(tap)
                } else {
                    hold.stop()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                hold.stop()
                armed = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // --- accessibility ---------------------------------------------------
    //
    // The donut is one View holding five controls. For a finger that is fine —
    // `controlAt` resolves the touch to a zone. For anything reading the
    // accessibility tree it was fatal: one node, no description, and a
    // `performClick` that fired nothing, so the four directions and OK could
    // not be reached at all. The fix is a virtual view hierarchy: this
    // view becomes a container and each zone a node of its own.
    //
    // `ExploreByTouchHelper` is the usual way to build one. It lives in
    // AndroidX and this app takes no runtime dependency by design, so the
    // platform provider it wraps is written out directly.

    /** The five zones, in the order their virtual view ids run. */
    private val zoneOrder: List<RemoteControl> = DPAD_DIRECTIONS + DPAD_CENTRE

    /** The zone the finger is over during explore-by-touch, if any. */
    private var hovered: RemoteControl? = null
    private var accessibilityFocusedId = AccessibilityNodeProvider.HOST_VIEW_ID

    private val nodeProvider = object : AccessibilityNodeProvider() {

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
            if (virtualViewId == HOST_VIEW_ID) {
                hostNode()
            } else {
                controlFor(virtualViewId)?.let { zoneNode(it, virtualViewId) }
            }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val control = controlFor(virtualViewId)
                ?: return super.performAction(virtualViewId, action, arguments)

            return when (action) {
                // What the accessibility layer sends for a double-tap. The
                // touch path never gets here — that is precisely the bug — so
                // this is a zone's only route when it is driven without sight.
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!isEnabled) return false
                    onPress(control)
                    true
                }

                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId == virtualViewId) return false
                    controlFor(accessibilityFocusedId)?.let { previous ->
                        accessibilityFocusedId = HOST_VIEW_ID
                        sendFocusEvent(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    }
                    accessibilityFocusedId = virtualViewId
                    sendFocusEvent(control, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    invalidate()
                    true
                }

                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId != virtualViewId) return false
                    accessibilityFocusedId = HOST_VIEW_ID
                    sendFocusEvent(control, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    invalidate()
                    true
                }

                else -> super.performAction(virtualViewId, action, arguments)
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                controlFor(accessibilityFocusedId)?.let { zoneNode(it, accessibilityFocusedId) }
            else super.findFocus(focus)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = nodeProvider

    private fun controlFor(virtualViewId: Int): RemoteControl? =
        zoneOrder.getOrNull(virtualViewId - 1)

    private fun virtualIdOf(control: RemoteControl): Int = zoneOrder.indexOf(control) + 1

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // A container, not a control. Leaving this node clickable is what let
        // the old tree offer a double-tap that pressed nothing.
        info.className = View::class.java.name
        info.isClickable = false
        info.isFocusable = false
        info.contentDescription = null
        zoneOrder.indices.forEach { info.addChild(this, it + 1) }
    }

    private fun hostNode(): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain(this)
        onInitializeAccessibilityNodeInfo(node)
        return node
    }

    private fun zoneNode(control: RemoteControl, virtualViewId: Int): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain(this, virtualViewId)
        // Field by field rather than a construction-time setter: these are the
        // properties the accessibility tree reads, and setting them all here
        // is what makes each zone addressable and actionable.
        node.packageName = context.packageName
        node.className = Button::class.java.name
        node.contentDescription = context.getString(control.labelRes)
        node.setParent(this)
        node.isEnabled = isEnabled
        node.isVisibleToUser = isShown
        node.isFocusable = true
        node.isClickable = true
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.isAccessibilityFocused = virtualViewId == accessibilityFocusedId
        node.addAction(if (node.isAccessibilityFocused) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        // The boxes overlap over the centre and on the diagonals. That costs
        // nothing: which node a touch belongs to is decided by `controlAt`,
        // and these are only where the focus highlight is drawn.
        val bounds = DpadGeometry.boundsOf(control, width, height)
        val inParent = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        node.setBoundsInParent(inParent)

        // Copied, not offset in place: the screen box is the parent box moved
        // by the view's origin, and the parent box is already set above.
        val onScreen = Rect(inParent)
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        onScreen.offset(origin[0], origin[1])
        node.setBoundsInScreen(onScreen)

        return node
    }

    /**
     * Explore-by-touch reports virtual hover transitions. The screen reader
     * chooses focus in response, the same way it does for ordinary Buttons.
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val control = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                controlAt(event.x, event.y)

            MotionEvent.ACTION_HOVER_EXIT -> null

            else -> return super.dispatchHoverEvent(event)
        }

        val handled = control != null || hovered != null
        if (control !== hovered) {
            hovered?.let {
                sendFocusEvent(it, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            }
            hovered = control
            control?.let {
                sendFocusEvent(it, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            }
        }
        return handled || super.dispatchHoverEvent(event)
    }

    private fun sendFocusEvent(control: RemoteControl, eventType: Int) {
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = context.packageName
        event.className = Button::class.java.name
        event.contentDescription = context.getString(control.labelRes)
        event.setSource(this, virtualIdOf(control))
        parent?.requestSendAccessibilityEvent(this, event)
    }

    /**
     * Repaint when the enabled state moves.
     *
     * Nothing else would tell the framework that the resolved colours have
     * changed, so the drawable's own state hook is the only place this can be
     * caught.
     */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    /** The control under a touch, from the same geometry the draw uses. */
    private fun controlAt(x: Float, y: Float): RemoteControl? =
        DpadGeometry.controlAt(x, y, width, height)

    companion object {
        const val LABEL_SP = 14

        const val SELECT_LABEL = "OK"
    }
}
