package ph.getmeds.card

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import java.io.IOException

/**
 * Sign in with the email on your business card and a password.
 * "First time / Forgot password" sets the password with a 6-digit code
 * emailed to that address.
 * Opens straight to the card when already signed in.
 */
class LoginActivity : Activity() {

    private enum class Mode { SIGN_IN, RESET_NUMBER, RESET_CODE }

    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private lateinit var stepMobile: View
    private lateinit var passwordGroup: View
    private lateinit var stepCode: View
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var code: EditText
    private lateinit var newPassword: EditText
    private lateinit var confirmPassword: EditText
    private lateinit var primary: Button
    private lateinit var savePassword: Button
    private lateinit var switchMode: Button
    private lateinit var remember: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    private var mode = Mode.SIGN_IN
    private var ticket: String? = null
    private var sentTo = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.token(this) != null && Session.card(this) != null) {
            openCard()
            return
        }
        setContentView(R.layout.activity_login)
        findViewById<View>(R.id.root).padForSystemBars()

        heading = findViewById(R.id.heading)
        subtitle = findViewById(R.id.subtitle)
        stepMobile = findViewById(R.id.stepMobile)
        passwordGroup = findViewById(R.id.passwordGroup)
        stepCode = findViewById(R.id.stepCode)
        email = findViewById(R.id.email)
        password = findViewById(R.id.password)
        code = findViewById(R.id.code)
        newPassword = findViewById(R.id.newPassword)
        confirmPassword = findViewById(R.id.confirmPassword)
        password.withShowHide(findViewById(R.id.passwordToggle))
        newPassword.withShowHide(findViewById(R.id.newPasswordToggle))
        confirmPassword.withShowHide(findViewById(R.id.confirmPasswordToggle))
        primary = findViewById(R.id.primary)
        savePassword = findViewById(R.id.savePassword)
        switchMode = findViewById(R.id.switchMode)
        remember = findViewById(R.id.remember)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        primary.setOnClickListener { if (mode == Mode.SIGN_IN) signIn() else requestCode() }
        savePassword.setOnClickListener { resetPassword() }
        switchMode.setOnClickListener { show(if (mode == Mode.SIGN_IN) Mode.RESET_NUMBER else Mode.SIGN_IN) }
        password.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_DONE).also { if (it) signIn() } }
        confirmPassword.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_DONE).also { if (it) resetPassword() } }

        val saved = savedInstanceState?.getString("mode")?.let { Mode.valueOf(it) }
        ticket = savedInstanceState?.getString("ticket")
        sentTo = savedInstanceState?.getString("masked").orEmpty()
        show(if (saved == Mode.RESET_CODE && ticket == null) Mode.RESET_NUMBER else saved ?: Mode.SIGN_IN)

        // Why we were sent back here, e.g. the card was switched off.
        intent.getStringExtra(EXTRA_MESSAGE)?.let(::showError)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", mode.name)
        outState.putString("ticket", ticket)
        outState.putString("masked", sentTo)
    }

    private fun show(next: Mode) {
        mode = next
        error.visibility = View.GONE
        stepMobile.visibility = if (next == Mode.RESET_CODE) View.GONE else View.VISIBLE
        passwordGroup.visibility = if (next == Mode.SIGN_IN) View.VISIBLE else View.GONE
        stepCode.visibility = if (next == Mode.RESET_CODE) View.VISIBLE else View.GONE
        // Asked wherever the next tap signs in.
        remember.visibility = if (next == Mode.RESET_NUMBER) View.GONE else View.VISIBLE
        when (next) {
            Mode.SIGN_IN -> {
                ticket = null
                heading.setText(R.string.login_title)
                subtitle.setText(R.string.login_subtitle)
                primary.setText(R.string.sign_in)
                switchMode.setText(R.string.forgot_password)
            }
            Mode.RESET_NUMBER -> {
                ticket = null
                heading.setText(R.string.reset_title)
                subtitle.setText(R.string.reset_subtitle)
                primary.setText(R.string.send_code)
                switchMode.setText(R.string.back_to_sign_in)
            }
            Mode.RESET_CODE -> {
                heading.setText(R.string.reset_title)
                subtitle.text = getString(R.string.code_sent, sentTo)
                switchMode.setText(R.string.back_to_sign_in)
                code.requestFocus()
            }
        }
    }

    private fun signIn() {
        val address = email.text.toString().trim()
        val pass = password.text.toString()
        if (address.isEmpty() || pass.isEmpty()) {
            showError("Enter your email and password.")
            return
        }
        busy(true)
        Bg.run(this, { Api.login(address, pass, remember.isChecked) }) { result ->
            busy(false)
            result.onSuccess {
                Session.save(this, it)
                openCard()
            }.onFailure { e ->
                // No password yet: go straight to setting one.
                if (e is Api.ApiException && e.status == 409) {
                    show(Mode.RESET_NUMBER)
                }
                showFailure(e)
            }
        }
    }

    private fun requestCode() {
        val address = email.text.toString().trim()
        if (address.isEmpty()) {
            showError("Enter the email on your business card.")
            return
        }
        busy(true)
        Bg.run(this, { Api.requestCode(address) }) { result ->
            busy(false)
            result.onSuccess {
                ticket = it.ticket
                sentTo = it.sentTo
                code.setText("")
                newPassword.setText("")
                confirmPassword.setText("")
                show(Mode.RESET_CODE)
            }.onFailure(::showFailure)
        }
    }

    private fun resetPassword() {
        val t = ticket ?: return show(Mode.RESET_NUMBER)
        val entered = code.text.toString().trim()
        val pass = newPassword.text.toString()
        when {
            entered.length < 4 -> return showError("Enter the 6-digit code from the email.")
            pass.length < MIN_PASSWORD -> return showError("Use at least $MIN_PASSWORD characters for your password.")
            pass != confirmPassword.text.toString() -> return showError("The two passwords don't match.")
        }
        busy(true)
        Bg.run(this, { Api.resetPassword(t, entered, pass, remember.isChecked) }) { result ->
            busy(false)
            result.onSuccess {
                Session.save(this, it)
                openCard()
            }.onFailure(::showFailure)
        }
    }

    private fun busy(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        primary.isEnabled = !on
        savePassword.isEnabled = !on
        switchMode.isEnabled = !on
        if (on) error.visibility = View.GONE
    }

    private fun showFailure(e: Throwable) {
        showError(
            when (e) {
                is Api.ApiException -> e.message.orEmpty()
                is IOException -> "No internet connection. Check your data or Wi-Fi and try again."
                else -> "Something went wrong. Please try again."
            }
        )
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun openCard() {
        startActivity(Intent(this, ShareActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        private const val MIN_PASSWORD = 8
    }
}
