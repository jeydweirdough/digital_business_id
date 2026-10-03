package ph.getmeds.card

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.ImageButton

/**
 * Show/hide for a password box: [toggle] (the eye on the right of the box)
 * switches between dots and plain text. Changes only how the text is drawn,
 * so the keyboard, font and cursor position stay as they were.
 */
fun EditText.withShowHide(toggle: ImageButton) {
    var shown = false
    toggle.setOnClickListener {
        shown = !shown
        val cursor = selectionEnd
        transformationMethod =
            if (shown) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
        setSelection(cursor.coerceIn(0, text.length))
        toggle.setImageResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye)
        toggle.contentDescription = context.getString(if (shown) R.string.hide_password else R.string.show_password)
    }
}
