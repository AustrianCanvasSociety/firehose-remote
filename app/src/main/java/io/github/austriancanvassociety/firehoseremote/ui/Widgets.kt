package io.github.austriancanvassociety.firehoseremote.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.RectShape
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.view.Gravity
import io.github.austriancanvassociety.firehoseremote.R

/**
 * Shared view helpers, used by every screen.
 *
 * A file of its own rather than a home inside `MainActivity` or
 * `RemoteScreen`, because all of them use these and none should import
 * another's file for a three-line function.
 */

internal fun Context.dp(value: Int): Int = dpToPx(value, resources.displayMetrics.density)

/** [value] dp in whole pixels at [density]; free of [Context] so [DpadGeometry] runs on the JVM. */
internal fun dpToPx(value: Int, density: Float): Int = (value * density).toInt()

/** The corner radius every button in the app shares. */
internal const val BUTTON_CORNER_DP = 12
internal const val PAGE_GAP_DP = 12
internal const val PAGE_HEADING_SP = 20f
internal const val PAGE_BODY_SP = 14f
internal const val FIELD_TEXT_SP = 18f
internal const val ACTION_TEXT_SP = 12f

/** Every flow page and form uses the same space between visible children. */
internal fun Context.pageColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    dividerDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setSize(1, context.dp(PAGE_GAP_DP))
    }
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

/** Public cursor/handle tint setters are available from Android 10 onward. */
internal fun EditText.skinSelection() {
    val accent = context.skinColor(R.color.primary)
    highlightColor = context.skinColor(R.color.primary_state_layer)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(accent) }
        textSelectHandle?.mutate()?.apply { setTint(accent) }?.let { setTextSelectHandle(it) }
        textSelectHandleLeft?.mutate()?.apply { setTint(accent) }?.let { setTextSelectHandleLeft(it) }
        textSelectHandleRight?.mutate()?.apply { setTint(accent) }?.let { setTextSelectHandleRight(it) }
    }
}

/** A resolved skin stays with the context used to construct its views. */
internal class SkinContext(base: Context, val palette: SkinPalette, val direction: SkinDirection) : ContextWrapper(base)

internal val Context.skin: SkinPalette?
    get() = when (this) {
        is SkinContext -> palette
        is ContextWrapper -> baseContext.skin
        else -> null
    }

internal fun Context.skinColor(resource: Int): Int {
    val palette = skin ?: return getColor(resource)
    return when (resource) {
        R.color.surface -> palette.ground
        R.color.primary -> palette.select
        R.color.on_primary -> palette.onSelect
        R.color.primary_container -> palette.panel
        R.color.secondary_container, R.color.tertiary_container -> palette.keyFill
        R.color.surface_container_high -> palette.rockerFill
        R.color.on_surface, R.color.on_secondary_container, R.color.on_tertiary_container -> palette.ink
        R.color.on_surface_variant -> palette.dim
        R.color.outline -> palette.edge
        R.color.dpad_ring -> palette.ring
        R.color.rocker_divider -> palette.divider
        R.color.primary_state_layer -> palette.stateLayer
        else -> getColor(resource)
    }
}

internal fun Context.skinBackground(): android.graphics.drawable.Drawable {
    val palette = skin ?: return ColorDrawable(getColor(R.color.surface))
    if ((this as? SkinContext)?.direction != SkinDirection.GRADIENT) return ColorDrawable(palette.ground)
    return ShapeDrawable(RectShape()).apply {
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader = LinearGradient(
                0f, 0f, width * 0.035f, height.toFloat().coerceAtLeast(1f),
                palette.gradientColors.toIntArray(), palette.gradientPositions.toFloatArray(), Shader.TileMode.CLAMP
            )
        }
    }
}

internal fun Context.skinStateLayer(): StateListDrawable = StateListDrawable().apply {
    val pressed = skinColor(R.color.primary_state_layer)
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressed))
    addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(pressed))
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
}

/**
 * A button in the app's one button style.
 *
 * The style began inside `RemoteScreen`, which was where the app's only styled
 * buttons lived. It sits here so the start, scan and PIN screens take it by
 * calling the same builder rather than by copying it — one copy, not one per
 * screen.
 *
 * [bgColorRes] and [fgColorRes] default to the secondary-container pair, which
 * is the tier the remote's own navigation keys use; a screen with no tiers of
 * its own gets that pair rather than a second, nearly-identical look. The
 * remote passes the pair its `tierColors` chose for the key.
 */
internal fun Context.actionButton(
    label: CharSequence,
    bgColorRes: Int = R.color.secondary_container,
    fgColorRes: Int = R.color.on_secondary_container,
    onClick: () -> Unit
): Button = Button(this).apply {
    text = label
    val ink = context.skinColor(fgColorRes)
    setTextColor(android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(ink, Skin.alpha(ink, 0.38f))
    ))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, ACTION_TEXT_SP)
    background = tierBackground(bgColorRes)
    elevation = 0f
    stateListAnimator = null
    minHeight = context.dp(48)
    minimumHeight = context.dp(48)
    setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
    // Buttons default to ALL CAPS on some themes; keep the label as-typed.
    isAllCaps = false
    setOnClickListener { onClick() }
}

/**
 * The tier background a key sits on: a rounded solid colour, with the
 * pressed-state selector layered over it.
 *
 * Extracted so a key that draws a mark instead of a word wears the same
 * surface. A drawn control on a different background from the text control
 * beside it would read as a different weight of button, which is the drift the
 * one-button-style rule exists to prevent.
 */
internal fun Context.tierBackground(
    bgColorRes: Int = R.color.secondary_container,
    borderColorRes: Int = R.color.outline
): LayerDrawable {
    val corner = this@tierBackground.dp(BUTTON_CORNER_DP).toFloat()
    val fill = this@tierBackground.skinColor(bgColorRes)
    val border = if (borderColorRes == R.color.outline) skin?.keyEdge ?: skinColor(borderColorRes) else skinColor(borderColorRes)
    val hairline = this@tierBackground.dp(1)
    val stateLayer = this@tierBackground.skinStateLayer()
    return LayerDrawable(
        arrayOf(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(fill)
                // The hairline edge the design reference gives every key. It is
                // what separates a key from the ground it sits on when the fill
                // is only a shade away from it — without it the tiers read as
                // one panel.
                setStroke(hairline, border)
            },
            stateLayer
        )
    )
}
