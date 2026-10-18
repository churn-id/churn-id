package id.churn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** The app's only screen, which draws whatever [Controller] shows. */
class MainActivity : Activity() {

    private val controller get() = (application as ChurnApp).controller

    /** Whether the user confirmed in step 1 that "Power On with Charger" is off. */
    private var chargerChecked = false

    override fun onStart() {
        super.onStart()
        controller.onScreen = ::render
        render(controller.screen)
    }

    override fun onStop() {
        controller.onScreen = null
        super.onStop()
    }

    private fun render(screen: Screen) = when (screen) {
        Screen.Intro -> intro()
        is Screen.Connect -> connect(screen)
        is Screen.Working -> working(screen)
        is Screen.TrustKey -> trustKey(screen)
        is Screen.SetUp -> setUp(screen)
        is Screen.Home -> home(screen)
        is Screen.Swapping -> swapping(screen)
    }

    private fun intro() = page {
        heading(getString(R.string.intro_title))
        paragraph(getString(R.string.legal_notice))
        paragraph(getString(R.string.intro_rules))
        paragraph(rules())
        button(R.string.intro_accept) { controller.acceptIntro() }
    }

    private fun connect(screen: Screen.Connect) = page {
        heading(getString(R.string.connect_title))
        paragraph(getString(R.string.connect_text))
        val address = field(getString(R.string.address), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        address.setText(controller.address)
        val password = field(
            getString(if (controller.hasPassword) R.string.password_saved else R.string.password),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        screen.error?.let { warning(it) }
        button(R.string.connect) { controller.connect(address.text.toString().trim(), password.text.toString()) }
        button(R.string.show_rules) { showRules() }
    }

    private fun working(screen: Screen.Working) = page(keepScreenOn = true) {
        heading(screen.title)
        addView(ProgressBar(context))
    }

    private fun trustKey(screen: Screen.TrustKey) = page {
        heading(getString(if (screen.changed) R.string.key_changed_title else R.string.key_new_title))
        val text = if (screen.changed) R.string.key_changed_text else R.string.key_new_text
        paragraph(getString(text, screen.fingerprint))
        button(R.string.trust) { controller.trust(screen.key) }
        button(R.string.cancel) { controller.cancel() }
    }

    private fun setUp(screen: Screen.SetUp) = page {
        heading(getString(R.string.setup_title))
        paragraph(getString(R.string.setup_text, screen.factory.imei1, screen.factory.imei2))
        screen.warnings.forEach { warning(it) }
        button(R.string.setup_start) { controller.setUp(screen.factory) }
        button(R.string.cancel) { controller.cancel() }
    }

    private fun home(screen: Screen.Home) = page {
        val status = screen.status
        heading(getString(R.string.router_title))
        paragraph(getString(R.string.status_imeis, status.imeis.imei1, status.imeis.imei2), monospace = true)
        if (status.imeis == status.factory) paragraph(getString(R.string.status_factory))
        paragraph(
            listOfNotNull(
                getString(if (status.airplaneMode) R.string.status_airplane_on else R.string.status_airplane_off),
                when (status.radio) {
                    1 -> getString(R.string.status_radio_on)
                    4 -> getString(R.string.status_radio_off)
                    else -> null
                },
                status.activeSlot?.let { getString(R.string.status_slot, it) },
                when (status.slot2) {
                    Slot2.SIM2 -> getString(R.string.status_slot2_sim2)
                    Slot2.ESIM -> getString(R.string.status_slot2_esim)
                    null -> null
                },
            ).joinToString("\n"),
        )
        screen.notes.forEach { warning(it) }
        if (!status.enhancedKillSwitch) {
            warning(getString(R.string.note_kill_switch))
            button(R.string.kill_switch_on) {
                confirm(getString(R.string.kill_switch_on_text)) { controller.turnKillSwitchOn() }
            }
        }
        if (!status.firewall) {
            warning(getString(R.string.note_firewall))
            button(R.string.firewall_on) {
                confirm(getString(R.string.firewall_on_text)) { controller.installFirewall() }
            }
        }
        if (!status.flashLogOff) {
            warning(getString(R.string.note_flash_log))
            button(R.string.clear_history) {
                confirm(getString(R.string.clear_history_text)) { controller.clearHistory() }
            }
        }
        button(R.string.swap) { controller.beginSwap() }
        button(R.string.refresh) { controller.refresh() }
        button(R.string.show_rules) { showRules() }
        button(R.string.restore) {
            val factory = status.factory
            confirm(getString(R.string.restore_text, factory.imei1, factory.imei2)) { controller.restore() }
        }
        button(R.string.forget) { confirm(getString(R.string.forget_text)) { controller.forget() } }
    }

    private fun swapping(screen: Screen.Swapping) = page(keepScreenOn = screen.running) {
        val (number, title, text) = when (screen.step) {
            Step.PREPARE -> Triple(1, R.string.step1_title, R.string.step1_text)
            Step.SWAP_CARD -> Triple(2, R.string.step2_title, R.string.step2_text)
            Step.NEW_IMEIS -> Triple(3, R.string.step3_title, R.string.step3_text)
            Step.GO_ONLINE -> Triple(4, R.string.step4_title, R.string.step4_text)
        }
        paragraph(getString(R.string.step_number, number))
        heading(getString(title))
        paragraph(getString(text))
        screen.log.forEach { paragraph("✓ $it") }
        if (screen.running) {
            addView(ProgressBar(context))
            return@page
        }
        screen.error?.let { warning(it) }
        when (screen.step) {
            Step.PREPARE -> {
                val charger = CheckBox(context).apply {
                    setText(R.string.step1_charger)
                    isChecked = chargerChecked
                }
                addView(charger)
                val start = button(if (screen.error == null) R.string.step1_start else R.string.try_again) {
                    askForNotifications()
                    controller.advance(Step.PREPARE)
                }
                start.isEnabled = chargerChecked
                charger.setOnCheckedChangeListener { _, checked ->
                    chargerChecked = checked
                    start.isEnabled = checked
                }
            }
            Step.SWAP_CARD -> button(R.string.step2_continue) {
                askForNotifications()
                controller.advance(Step.SWAP_CARD)
            }
            Step.NEW_IMEIS -> button(R.string.try_again) { controller.advance(Step.NEW_IMEIS) }
            Step.GO_ONLINE -> button(R.string.step4_done) { controller.advance(Step.GO_ONLINE) }
        }
        if (screen.step != Step.GO_ONLINE) button(R.string.cancel_swap) { controller.cancelSwap() }
    }

    private fun rules(): String {
        val always = resources.getStringArray(R.array.rules_always)
        val whereYouCan = resources.getStringArray(R.array.rules_where_you_can)
        return buildString {
            append(getString(R.string.rules_always_heading))
            always.forEachIndexed { i, rule -> append("\n\n${i + 1}. $rule") }
            append("\n\n").append(getString(R.string.rules_where_you_can_heading))
            whereYouCan.forEachIndexed { i, rule -> append("\n\n${always.size + i + 1}. $rule") }
        }
    }

    private fun showRules() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rules_title)
            .setMessage(rules())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The foreground service's notification needs this permission from Android 13 on; the swap works without. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun page(keepScreenOn: Boolean = false, build: LinearLayout.() -> Unit) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
            build()
        }
        setContentView(
            ScrollView(this).apply {
                // Keeps the content clear of the status and navigation bars, which the app draws behind.
                fitsSystemWindows = true
                // While the app works on the router, the screen stays on, as it does while a video plays, so that the
                // phone doesn't lock. GrapheneOS by default blocks new USB connections on a locked phone, and in
                // step 3 the router, just switched on, connects anew.
                this.keepScreenOn = keepScreenOn
                addView(column)
            },
        )
    }

    /**
     * A text view in the theme's primary text color, which buttons use too. A plain one takes the
     * theme's small text appearance, whose color is the one Material uses for outlines.
     */
    private fun textView() = TextView(this).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault) }

    private fun LinearLayout.heading(text: String) = addView(
        textView().apply {
            this.text = text
            textSize = 22f
            setPadding(0, 0, 0, dp(16))
        },
    )

    private fun LinearLayout.paragraph(text: String, monospace: Boolean = false) = addView(
        textView().apply {
            this.text = text
            textSize = 16f
            if (monospace) typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(12))
        },
    )

    private fun LinearLayout.warning(text: String) = addView(
        textView().apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(0xFF, 0xB3, 0x00))
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(12))
        },
    )

    private fun LinearLayout.field(hint: String, inputType: Int) = EditText(context).apply {
        this.hint = hint
        this.inputType = inputType
        isSingleLine = true
    }.also { addView(it) }

    private fun LinearLayout.button(text: Int, onClick: () -> Unit) = Button(context).apply {
        setText(text)
        setOnClickListener { onClick() }
    }.also { addView(it) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
