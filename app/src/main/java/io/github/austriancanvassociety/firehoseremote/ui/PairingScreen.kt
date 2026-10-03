package io.github.austriancanvassociety.firehoseremote.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import io.github.austriancanvassociety.firehoseremote.R

/**
 * The two things the app asks a person to type: the PIN shown on a TV, and —
 * when a scan cannot find it — the TV's own address. Everything is built in
 * code: no XML layouts, no AndroidX, no Compose (see README).
 *
 * Neither builder holds pairing logic. They collect a string and hand it back;
 * the decisions live in [PairingFlow], including whether a typed address is
 * usable at all.
 */
class PairingScreen(private val context: Context) {

    /**
     * [initialPin] and [onPinChanged] carry the digits typed so far across a
     * re-draw — a dark-mode or font-size change rebuilds this screen, and the
     * user should not have to read the PIN off the TV twice.
     */
    fun build(
        deviceLabel: String,
        initialPin: String = "",
        onPinChanged: (String) -> Unit = {},
        onSubmit: (String) -> Unit,
        onBack: () -> Unit
    ): View {
        val column = context.pageColumn()

        column.addView(
            TextView(context).apply {
                text = deviceLabel
                setTextColor(context.skinColor(R.color.on_surface))
                gravity = Gravity.CENTER
                textSize = PAGE_HEADING_SP
            }
        )
        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.pin_prompt)
                setTextColor(context.skinColor(R.color.on_surface))
                gravity = Gravity.CENTER
                textSize = PAGE_BODY_SP
            }
        )

        val pinField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.pin_hint)
            setTextColor(context.skinColor(R.color.on_surface))
            setHintTextColor(context.skinColor(R.color.on_surface))
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.skinColor(R.color.primary))
            skinSelection()
            gravity = Gravity.CENTER
            textSize = FIELD_TEXT_SP
            minimumHeight = context.dp(48)
            setSingleLine()
            setText(initialPin)
            setSelection(initialPin.length)
        }
        column.addView(pinField)

        // The submit control stays disabled until something is typed. This is
        // the ergonomic half of a two-layer guard — the flow refuses a blank
        // PIN as well — and it exists so the button does not invite an attempt
        // that would spend one of the TV's pairing attempts to learn nothing.
        val pairButton = context.actionButton(context.getString(R.string.pair_button)) {
            onSubmit(pinField.text.toString())
        }.apply {
            isEnabled = pinField.text.toString().trim().isNotEmpty()
        }
        pinField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pairButton.isEnabled = !s.isNullOrBlank()
                onPinChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        column.addView(pairButton)

        column.addView(context.actionButton(context.getString(R.string.back_button)) { onBack() })

        pinField.requestFocus()
        return column
    }

    /**
     * The manual route: an offer, a field for the address, and Connect.
     *
     * A sibling to [build] rather than a parameter on it, because the two ask
     * for different things and the difference is the point — [build] continues
     * a pairing the flow has already started, while this one starts it. Folding
     * them into one builder would need a flag to choose between two sets of
     * labels, and that flag would be the only thing keeping them apart.
     *
     * The field deliberately does **not** take focus. It renders underneath a
     * scan's own result, and stealing the caret there would pop a keyboard over
     * the message the user is meant to read first.
     *
     * [draft] and [onChanged] carry half-typed text across a re-draw. Unlike
     * [prefill] the draft is not selected: it is text the user is still
     * writing, and the next keypress should add to it, not replace it.
     */
    fun buildAddressEntry(
        prefill: String? = null,
        draft: String? = null,
        onChanged: (String) -> Unit = {},
        onSubmit: (String) -> Unit
    ): View {
        val column = context.pageColumn()

        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.address_prompt)
                setTextColor(context.skinColor(R.color.on_surface))
                gravity = Gravity.CENTER
                textSize = PAGE_BODY_SP
            }
        )

        val addressField = EditText(context).apply {
            // Not TYPE_CLASS_NUMBER: an address is mostly dots, and a numeric
            // keypad hides them — the field would be unusable. Checking the
            // shape is [PairingFlow]'s job and needs no help from the keypad.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = context.getString(R.string.address_hint)
            setTextColor(context.skinColor(R.color.on_surface))
            setHintTextColor(context.skinColor(R.color.on_surface))
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.skinColor(R.color.primary))
            skinSelection()
            gravity = Gravity.CENTER
            textSize = FIELD_TEXT_SP
            minimumHeight = context.dp(48)
            setSingleLine()
            // On the retry lane the user is correcting a typo they just made;
            // pre-filling and select-all means one keypress overwrites the
            // wrong character rather than the user tapping to position a
            // caret in a field that already has the wrong text. On the fresh
            // lane [prefill] is null and the field renders empty as before.
            if (prefill != null) {
                setText(prefill)
                setSelection(0, prefill.length)
            } else if (draft != null) {
                setText(draft)
                setSelection(draft.length)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onChanged(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        column.addView(addressField)

        column.addView(
            context.actionButton(context.getString(R.string.address_button)) {
                onSubmit(addressField.text.toString())
            }
        )

        return column
    }
}
