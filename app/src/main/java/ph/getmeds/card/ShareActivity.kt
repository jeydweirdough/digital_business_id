package ph.getmeds.card

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The app's home after sign-in: the QR, plus phone-to-phone NFC tap.
 * Online ID opens the holder's card on getmeds.ph, where the other person
 * chooses whether to save the contact; Offline ID hands over the contact
 * itself, no internet needed. The phone answers NFC taps only while this
 * screen is showing.
 *
 * Kept to what's needed while someone is scanning. The step-by-step, tap
 * details, card switching and sign-out are one tap away: the "How do they
 * scan it?" link, the tap chip and the ⋮ menu.
 */
class ShareActivity : Activity() {

    private lateinit var notice: TextView
    private lateinit var qr: ImageView
    private lateinit var scanLine: TextView
    private lateinit var tapChip: View
    private lateinit var tapDot: View
    private lateinit var tapText: TextView
    private lateinit var sent: TextView

    private var nfc: NfcAdapter? = null
    private var shownPayload = ""
    private val main = Handler(Looper.getMainLooper())
    private val hideSent = Runnable { sent.visibility = View.GONE }

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateTapChip()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val card = Session.card(this)
        if (Session.token(this) == null || card == null) {
            signedOut(null)
            return
        }
        setContentView(R.layout.activity_share)
        findViewById<View>(R.id.root).padForSystemBars()

        // Bright and awake, so the QR scans and the tap isn't cut off by the screen locking.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        notice = findViewById(R.id.notice)
        qr = findViewById(R.id.qr)
        scanLine = findViewById(R.id.scanLine)
        tapChip = findViewById(R.id.tapChip)
        tapDot = findViewById(R.id.tapDot)
        tapText = findViewById(R.id.tapText)
        sent = findViewById(R.id.sent)

        findViewById<View>(R.id.modeWebsite).setOnClickListener { setMode(ShareMode.WEBSITE) }
        findViewById<View>(R.id.modeDirect).setOnClickListener { setMode(ShareMode.DIRECT) }
        findViewById<View>(R.id.howToScan).setOnClickListener { showHowToScan() }
        findViewById<View>(R.id.menu).setOnClickListener(::showMenu)
        tapChip.setOnClickListener { showTapInfo() }
        findViewById<View>(R.id.contactUs).setOnClickListener {
            startActivity(Intent(this, SupportActivity::class.java))
        }

        nfc = NfcAdapter.getDefaultAdapter(this)
        render(card)
    }

    override fun onResume() {
        super.onResume()
        // The sign-in can run out while the app sits in the background.
        if (Session.token(this) == null) return signedOut(getString(R.string.session_expired))
        refresh()
        watchInternet()

        ShareState.active = true
        ShareState.onSent = ::showSent
        // Ahead of any other app that also answers as an NFC tag.
        if (canTap()) runCatching { CardEmulation.getInstance(nfc).setPreferredService(this, hceComponent()) }

        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(nfcStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(nfcStateReceiver, filter)
        updateTapChip()
    }

    override fun onPause() {
        super.onPause()
        stopWatchingInternet()
        ShareState.active = false
        ShareState.onSent = null
        if (canTap()) runCatching { CardEmulation.getInstance(nfc).unsetPreferredService(this) }
        runCatching { unregisterReceiver(nfcStateReceiver) }
        main.removeCallbacks(hideSent)
    }

    /** Picks up changes made in the Studio, and notices a card switched off. */
    private fun refresh() {
        val token = Session.token(this) ?: return
        Bg.run(this, { Api.me(token) }) { result ->
            result.onSuccess {
                Session.saveCards(this, it)
                Session.card(this)?.let(::render)
            }.onFailure { e ->
                if (e is Api.ApiException && (e.status == 401 || e.status == 403)) signedOut(e.message)
                // Offline: the saved QR still works, so carry on quietly.
            }
        }
    }

    private fun render(card: Card) {
        findViewById<TextView>(R.id.name).text = card.fullName
        findViewById<TextView>(R.id.jobTitle).apply {
            text = card.jobTitle
            visibility = if (card.jobTitle.isBlank()) View.GONE else View.VISIBLE
        }

        val mode = Session.shareMode(this)
        selectModeButton(mode)

        // Website: the card page link. Direct: the contact itself, built here
        // from the saved card, so it works with no internet on either phone.
        val payload = when (mode) {
            ShareMode.WEBSITE -> card.shareUrl
            ShareMode.DIRECT -> Contact.vcard(card)
        }
        val canShare = when (mode) {
            ShareMode.WEBSITE -> card.shareUrl.isNotBlank()
            ShareMode.DIRECT -> card.fullName.isNotBlank()
        }
        notice.visibility = if (canShare) View.GONE else View.VISIBLE
        notice.setText(R.string.no_contact)
        val shown = if (canShare) View.VISIBLE else View.GONE
        qr.visibility = shown
        scanLine.visibility = shown
        scanLine.setText(if (mode == ShareMode.WEBSITE) R.string.scan_line_website else R.string.scan_line_direct)
        findViewById<View>(R.id.howToScan).visibility = shown

        ShareState.ndef = when {
            !canShare -> null
            mode == ShareMode.WEBSITE -> NdefMessage(NdefRecord.createUri(payload)).toByteArray()
            // Android phones open their save-contact screen for a text/vcard tag.
            else -> NdefMessage(NdefRecord.createMime("text/vcard", payload.toByteArray(Charsets.UTF_8))).toByteArray()
        }
        if (canShare && payload != shownPayload) {
            val size = (300 * resources.displayMetrics.density).toInt()
            val logo = BitmapFactory.decodeResource(resources, R.drawable.getmeds_mark)
            // Strong error correction for the short link; light for the long
            // contact, which keeps it from getting too dense to scan.
            val level = if (mode == ShareMode.WEBSITE) ErrorCorrectionLevel.H else ErrorCorrectionLevel.L
            qr.setImageBitmap(Qr.branded(payload, size, logo, level))
            shownPayload = payload
        }
        updateTapChip()
    }

    private fun setMode(mode: ShareMode) {
        if (mode == Session.shareMode(this)) return
        Session.setShareMode(this, mode)
        Session.card(this)?.let(::render)
    }

    private fun selectModeButton(mode: ShareMode) {
        listOf(
            Triple(R.id.modeWebsite, R.id.modeWebsiteTitle, ShareMode.WEBSITE),
            Triple(R.id.modeDirect, R.id.modeDirectTitle, ShareMode.DIRECT),
        ).forEach { (box, title, m) ->
            val on = m == mode
            findViewById<View>(box).apply {
                setBackgroundResource(if (on) R.drawable.bg_segment_selected else 0)
                isSelected = on
            }
            findViewById<TextView>(title).apply {
                setTextColor(getColor(if (on) R.color.brand else R.color.muted))
                setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
    }

    // ── Guidance, one tap away ───────────────────────────────────────────────

    /** The step-by-step for the person scanning, for the mode on screen. */
    private fun showHowToScan() {
        val website = Session.shareMode(this) == ShareMode.WEBSITE
        val about = getString(if (website) R.string.mode_website_desc else R.string.mode_direct_desc)
        val steps = getString(if (website) R.string.steps_website else R.string.steps_direct)
        AlertDialog.Builder(this)
            .setTitle(if (website) R.string.mode_website else R.string.mode_direct)
            .setMessage("$about\n\n$steps")
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    /** What "Online ID" and "Offline ID" mean, and when the app picks each. */
    private fun showHowItWorks() {
        AlertDialog.Builder(this)
            .setTitle(R.string.how_it_works)
            .setMessage(R.string.how_it_works_body)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun showMenu(anchor: View) {
        val multiple = Session.cards(this).size > 1
        PopupMenu(this, anchor).apply {
            if (multiple) menu.add(0, MENU_SWITCH, 0, getString(R.string.switch_card, Session.cards(this@ShareActivity).size))
            menu.add(0, MENU_HOW, 1, R.string.how_it_works)
            menu.add(0, MENU_SIGN_OUT, 2, R.string.sign_out)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_SWITCH -> pickCard()
                    MENU_HOW -> showHowItWorks()
                    MENU_SIGN_OUT -> confirmSignOut()
                }
                true
            }
            show()
        }
    }

    // ── Picking the mode from this phone's internet ──────────────────────────
    // Online ID needs the other phone to have internet, which this app can't
    // see; this phone's connection is the best guess at whether there's
    // signal here. Each change in connection picks the mode again, and the
    // holder can still tap the other one in between.

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastOnline: Boolean? = null

    private fun watchInternet() {
        lastOnline = null
        applyInternet(hasInternet())
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Called off the main thread; re-checked there, since "lost" for
            // one network can come just before another takes over.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                main.post { applyInternet(hasInternet()) }
            }
            override fun onLost(network: Network) {
                main.post { applyInternet(hasInternet()) }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun stopWatchingInternet() {
        val callback = networkCallback ?: return
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    /** Online means a connection Android has confirmed reaches the internet. */
    private fun hasInternet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Switches only when the connection actually changes, not on every callback. */
    private fun applyInternet(online: Boolean) {
        if (online == lastOnline || isFinishing) return
        lastOnline = online
        setMode(if (online) ShareMode.WEBSITE else ShareMode.DIRECT)
    }

    /** One person, several cards: choose which one the QR and tap share. */
    private fun pickCard() {
        val all = Session.cards(this)
        val current = all.indexOfFirst { it.id == Session.card(this)?.id }
        val labels = all.map { c ->
            listOf(c.jobTitle, c.company).filter { it.isNotBlank() }.joinToString(" · ")
                .let { detail -> if (detail.isBlank()) c.fullName else "${c.fullName}\n$detail" }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_card)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Session.select(this, all[which].id)
                render(all[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Phone tap ────────────────────────────────────────────────────────────

    private fun canTap(): Boolean =
        nfc != null && packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    private fun hceComponent() = ComponentName(this, CardHceService::class.java)

    /** A small status chip: ready, or NFC off. Not shown on phones without NFC. */
    private fun updateTapChip() {
        val adapter = nfc
        if (!canTap() || adapter == null || qr.visibility != View.VISIBLE) {
            tapChip.visibility = View.GONE
            return
        }
        tapChip.visibility = View.VISIBLE
        if (adapter.isEnabled) {
            tapDot.setBackgroundResource(R.drawable.dot_on)
            tapText.setText(R.string.tap_chip_ready)
            // Coming back from NFC settings: claim the tap again.
            runCatching { CardEmulation.getInstance(adapter).setPreferredService(this, hceComponent()) }
        } else {
            tapDot.setBackgroundResource(R.drawable.dot_off)
            tapText.setText(R.string.tap_chip_off)
        }
    }

    private fun showTapInfo() {
        val on = nfc?.isEnabled == true
        val builder = AlertDialog.Builder(this).setTitle(R.string.tap_title)
        if (on) {
            val detail = if (Session.shareMode(this) == ShareMode.WEBSITE) R.string.tap_ready_detail
            else R.string.tap_ready_detail_direct
            builder.setMessage(detail).setPositiveButton(R.string.got_it, null)
        } else {
            builder.setMessage(R.string.nfc_off_detail)
                .setPositiveButton(R.string.turn_on_nfc) { _, _ -> startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null)
        }
        builder.show()
    }

    private fun showSent() {
        sent.visibility = View.VISIBLE
        main.removeCallbacks(hideSent)
        main.postDelayed(hideSent, 4_000)
        vibrate()
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(80)
    }

    // ── Signing out ──────────────────────────────────────────────────────────

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setMessage(R.string.sign_out_confirm)
            .setPositiveButton(R.string.sign_out) { _, _ -> signedOut(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun signedOut(message: String?) {
        Session.clear(this)
        ShareState.ndef = null
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_MESSAGE, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private companion object {
        const val MENU_SWITCH = 1
        const val MENU_HOW = 2
        const val MENU_SIGN_OUT = 3
    }
}
