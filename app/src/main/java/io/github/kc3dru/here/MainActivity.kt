package io.github.kc3dru.here

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.github.kc3dru.here.databinding.ActivityMainBinding
import io.github.kc3dru.here.databinding.DialogSendBinding
import io.github.kc3dru.here.databinding.DialogSetupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host activity for two dialogs:
 *
 * 1. Setup dialog  ([DialogSetupBinding]) — recipients + permissions.
 *    Shown when the app is launched from the launcher icon.
 * 2. Send dialog   ([DialogSendBinding]) — heading, recipients, exact message
 *    body and live status for a widget-triggered send.
 *
 * The activity itself is only a host: it receives widget launch intents and
 * runs the location + SMS workflow, routing every update into the send dialog.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationHelper: LocationHelper

    private var setupBinding: DialogSetupBinding? = null
    private var sendBinding: DialogSendBinding? = null
    private var setupDialog: AlertDialog? = null
    private var sendDialog: AlertDialog? = null

    // Radio acknowledgements for the current batch of sends (G-7)
    private var sendTotal = 0
    private var sendAcked = 0
    private var sendRejected = 0

    /**
     * Receives the per-send acknowledgement broadcast from [SmsSender] and
     * keeps the send dialog's status line honest: acknowledged vs rejected
     * by the radio.
     */
    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SmsSender.ACTION_SMS_SENT) return
            if (resultCode == RESULT_OK) {
                sendAcked++
            } else {
                sendRejected++
            }
            val done = sendAcked + sendRejected
            setSendStatus(
                when {
                    done < sendTotal -> "📤 Handed to radio: $done/$sendTotal"
                    sendRejected == 0 -> "✅ Sent"
                    else -> "❌ Radio rejected $sendRejected"
                }
            )
        }
    }

    companion object {
        private const val TAG = "HereMain"

        private const val EXTRA_WIDGET_TYPE = "widget_type"
        private const val WIDGET_ARRIVED = "arrived"
        private const val WIDGET_HEADING_HOME = "heading_home"
        private const val WIDGET_MOVING_ON = "moving_on"
    }

    // Activity result launcher for permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissions()
        } else {
            Toast.makeText(this, "Permission denied — tap the button to try again", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationHelper = LocationHelper(this)

        // Private receiver for real radio send acknowledgements (G-7)
        ContextCompat.registerReceiver(
            this,
            smsSentReceiver,
            IntentFilter(SmsSender.ACTION_SMS_SENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Show the send dialog or the setup dialog depending on the intent
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        setupDialog?.dismiss()
        sendDialog?.dismiss()
        setupDialog = null
        sendDialog = null
        setupBinding = null
        sendBinding = null
        unregisterReceiver(smsSentReceiver)
    }

    /**
     * Called when MainActivity already exists (singleTop launch mode)
     * and receives a new intent — e.g. widget tap while app is in background.
     * Without this override, the widget extra is lost and the settings screen
     * is shown instead of the workflow.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Update the intent so handleIntent sees the new extras
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Central handler for widget launch intents.
     */
    private fun handleIntent(intent: Intent?) {
        val widgetType = intent?.getStringExtra(EXTRA_WIDGET_TYPE)

        // Diagnostic, deliberately free of personal data. This is what settles
        // whether a widget tap delivered its extra at all — the difference
        // between "the send flow never started" and "it started and bailed".
        // Read with: adb logcat -s HereMain
        Log.i(
            TAG,
            "launch action=${intent?.action} widgetType=${widgetType ?: "<none>"} " +
                "recipients=${Settings.getRecipients(this).size} smsGranted=${hasSmsPermission()}"
        )

        if (widgetType != null && widgetType in listOf(WIDGET_ARRIVED, WIDGET_HEADING_HOME, WIDGET_MOVING_ON)) {
            showSendDialog(widgetType)
            startLocationWorkflow(widgetType)
        } else {
            showSetupDialog()
        }
    }

    /** Re-check permission rows when returning from the system permission screen. */
    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    // ─── Send dialog (widget flow) ─────────────────────────────────────

    private fun showSendDialog(widgetType: String) {
        val b = DialogSendBinding.inflate(layoutInflater)
        sendBinding = b

        b.tvSendHeading.text = when (widgetType) {
            WIDGET_ARRIVED -> "📍 I have arrived"
            WIDGET_HEADING_HOME -> "🏠 Heading Home"
            else -> "🚗 Moving On"
        }

        b.tvSendRecipients.text = recipientSummary(Settings.getRecipients(this))
        b.btnOpenSetup.setOnClickListener { showSetupDialog() }

        sendDialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setOnDismissListener { finishIfNothingToShow() }
            .show()
    }

    private fun recipientSummary(recipients: List<String>): String =
        if (recipients.isEmpty()) "(none configured)" else recipients.joinToString("\n")

    /** Write a status line into the send dialog, if it is on screen. */
    private fun setSendStatus(text: CharSequence) {
        sendBinding?.takeIf { sendDialog?.isShowing == true }?.tvSendStatus?.text = text
    }

    private fun setSendProgressIndicator(active: Boolean) {
        sendBinding?.takeIf { sendDialog?.isShowing == true }?.progressBar?.visibility =
            if (active) View.VISIBLE else View.GONE
    }

    /** The flow stopped before sending — offer a way into setup. */
    private fun offerSetup(text: CharSequence) {
        setSendProgressIndicator(active = false)
        setSendStatus(text)
        sendBinding?.takeIf { sendDialog?.isShowing == true }?.btnOpenSetup?.visibility = View.VISIBLE
    }

    private fun startLocationWorkflow(widgetType: String) {
        // Guard up front so the user is not left watching a spinner that
        // cannot possibly succeed.
        val configured = Settings.getRecipients(this)
        if (configured.isEmpty()) {
            offerSetup("❌ No recipients configured. Add a number, then tap the widget again.")
            return
        }
        if (!hasSmsPermission()) {
            offerSetup("❌ SMS permission required. Grant it below, then tap the widget again.")
            return
        }

        sendBinding?.tvSendRecipients?.text = recipientSummary(configured)
        setSendProgressIndicator(active = true)
        setSendStatus("Acquiring GPS fix (up to 30s)…")

        lifecycleScope.launch {
            try {
                // Step 1: Get current location
                val location = locationHelper.getCurrentLocation()

                withContext(Dispatchers.Main) {
                    setSendStatus("GPS locked. Preparing to send…")
                }

                // Step 2: Get recipients (may have changed while acquiring the fix)
                val recipients = Settings.getRecipients(this@MainActivity)
                if (recipients.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        sendBinding?.tvSendRecipients?.text = recipientSummary(recipients)
                        offerSetup("❌ No recipients configured. Add a number, then tap the widget again.")
                    }
                    return@launch
                }

                // Step 3: Create message based on widget type
                val message = when (widgetType) {
                    WIDGET_ARRIVED ->
                        SmsSender.createArrivedMessage(location.latitude, location.longitude)
                    WIDGET_MOVING_ON ->
                        SmsSender.createMovingOnMessage(location.latitude, location.longitude)
                    else ->
                        SmsSender.createHeadingHomeMessage(location.latitude, location.longitude)
                }

                withContext(Dispatchers.Main) {
                    sendBinding?.tvSendRecipients?.text = recipientSummary(recipients)
                    sendBinding?.tvSendMessage?.text = message
                }

                // Step 4: Send SMS to all recipients
                var successCount = 0
                var failCount = 0
                val failures = mutableListOf<String>()

                // Reset radio-acknowledgement counters for this batch (G-7)
                sendTotal = recipients.size
                sendAcked = 0
                sendRejected = 0

                for (recipient in recipients) {
                    val result = SmsSender.sendSms(this@MainActivity, recipient, message)
                    if (result.isSuccess) {
                        successCount++
                    } else {
                        failCount++
                        failures.add("$recipient: ${result.exceptionOrNull()?.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    setSendProgressIndicator(active = false)
                    if (successCount > 0 && failCount == 0) {
                        // Handed to the framework — the SMS_SENT receiver updates
                        // the real outcome as the radio acknowledges each send
                        setSendStatus(
                            "📤 Queued with radio for $successCount recipient${if (successCount > 1) "s" else ""}"
                        )
                    } else if (successCount > 0 && failCount > 0) {
                        // Partial success
                        setSendStatus(
                            "⚠️ Sent to $successCount, failed $failCount\n" +
                                    failures.joinToString("\n")
                        )
                        offerSetupButtonOnly()
                    } else {
                        // All failed
                        setSendStatus(
                            "❌ Failed to send to all $failCount recipients\n" +
                                    failures.joinToString("\n")
                        )
                        offerSetupButtonOnly()
                    }
                }

            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    offerSetup("❌ Permission denied — SMS permission required.")
                }
            } catch (e: LocationUnavailableException) {
                withContext(Dispatchers.Main) {
                    setSendProgressIndicator(active = false)
                    setSendStatus("❌ GPS unavailable: ${e.message}")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("GPS unavailable")
                        .setMessage("${e.message}\n\nMove to an area with a clear view of the sky, or enable GPS in Settings.")
                        .setPositiveButton("Enable GPS") { _, _ ->
                            locationHelper.openLocationSettings(this@MainActivity)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setSendProgressIndicator(active = false)
                    setSendStatus("❌ Error: ${e.message}")
                }
            }
        }
    }

    /** Show the setup button without touching the status line. */
    private fun offerSetupButtonOnly() {
        sendBinding?.takeIf { sendDialog?.isShowing == true }?.btnOpenSetup?.visibility = View.VISIBLE
    }

    // ─── Setup dialog ─────────────────────────────────────────────────

    private fun showSetupDialog() {
        if (setupDialog?.isShowing == true) return

        val b = DialogSetupBinding.inflate(layoutInflater)
        setupBinding = b

        b.btnAddRecipient.setOnClickListener { addRecipientFromInput() }
        b.btnRequestSmsPermission.setOnClickListener { requestSmsPermission() }
        b.btnRequestLocationPermission.setOnClickListener { requestLocationPermission() }

        loadRecipients()
        checkPermissions()

        setupDialog = AlertDialog.Builder(this)
            .setTitle("⚙️ Here — setup")
            .setView(b.root)
            .setOnDismissListener {
                setupBinding = null
                setupDialog = null
                // Must not finish unconditionally: setup is also opened on top of
                // the send dialog, and closing setup must not tear that away.
                finishIfNothingToShow()
            }
            .show()
    }

    /** Once the send dialog is gone there is nothing left on screen. */
    private fun finishIfNothingToShow() {
        if (setupDialog?.isShowing != true && sendDialog?.isShowing != true && !isFinishing) {
            finish()
        }
    }

    /** Convert dp to pixels for programmatically built rows. */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun saveRecipients() {
        val b = setupBinding ?: return
        val input = b.etNewRecipient.text?.toString()?.trim()
        if (input.isNullOrBlank()) {
            Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = PhoneNumber.normalize(input)
        if (normalized == null) {
            Toast.makeText(
                this,
                "Not a usable number — try 212 555 0100 or +1 212 555 0100",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (Settings.addRecipient(this, normalized)) {
            loadRecipients()
            b.etNewRecipient.text?.clear()
            Toast.makeText(this, "Added: $normalized", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Number already in list", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addRecipientFromInput() {
        saveRecipients()
    }

    /**
     * Rebuild the recipient list in the setup dialog: one row per number with
     * a small "✕ Remove" button wired to [removeRecipient]. Empty state is a
     * single grey row.
     */
    private fun loadRecipients() {
        val container = setupBinding?.recipientListContainer ?: return
        container.removeAllViews()

        val recipients = Settings.getRecipients(this)
        if (recipients.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No recipients added yet."
                textSize = 13f
                setTextColor(0xFF757575.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            })
            return
        }

        for (number in recipients) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(TextView(this).apply {
                text = number
                textSize = 13f
                setTextColor(0xFF212121.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            row.addView(
                MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "✕ Remove"
                    textSize = 11f
                    insetTop = dp(4)
                    insetBottom = dp(4)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = dp(8) }
                    setOnClickListener { removeRecipient(number) }
                }
            )

            container.addView(row)
        }
    }

    private fun removeRecipient(phoneNumber: String) {
        Settings.removeRecipient(this, phoneNumber)
        loadRecipients()
        // Keep the send dialog (if open behind setup) honest about who is left
        sendBinding?.takeIf { sendDialog?.isShowing == true }?.tvSendRecipients?.text =
            recipientSummary(Settings.getRecipients(this))
        Toast.makeText(this, "Removed: $phoneNumber", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        val b = setupBinding?.takeIf { setupDialog?.isShowing == true } ?: return

        val smsGranted = hasSmsPermission()
        val fineLocationGranted = locationHelper.hasFineLocationPermission(this)
        val anyLocationGranted = locationHelper.hasLocationPermission(this)

        if (smsGranted) {
            b.tvSmsPermissionStatus.text = "✅ SMS permission granted"
            b.tvSmsPermissionStatus.setTextColor(0xFF2E7D32.toInt())
            b.btnRequestSmsPermission.visibility = View.GONE
        } else {
            b.tvSmsPermissionStatus.text = "❌ SMS permission required"
            b.tvSmsPermissionStatus.setTextColor(0xFFD32F2F.toInt())
            b.btnRequestSmsPermission.visibility = View.VISIBLE
        }

        if (fineLocationGranted) {
            b.tvLocationPermissionStatus.text = "✅ GPS permission granted"
            b.tvLocationPermissionStatus.setTextColor(0xFF2E7D32.toInt())
            b.btnRequestLocationPermission.visibility = View.GONE
        } else if (anyLocationGranted) {
            // GrapheneOS may grant approximate-only on its own; keep the
            // upgrade button visible so the user can grant exact location
            b.tvLocationPermissionStatus.text = "⚠️ GPS permission: approximate only"
            b.tvLocationPermissionStatus.setTextColor(0xFFF9A825.toInt())
            b.btnRequestLocationPermission.visibility = View.VISIBLE
        } else {
            b.tvLocationPermissionStatus.text = "❌ GPS permission required"
            b.tvLocationPermissionStatus.setTextColor(0xFFD32F2F.toInt())
            b.btnRequestLocationPermission.visibility = View.VISIBLE
        }
    }

    private fun requestSmsPermission() {
        if (hasSmsPermission()) {
            Toast.makeText(this, "SMS permission already granted", Toast.LENGTH_SHORT).show()
            return
        }
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (tm.phoneType == TelephonyManager.PHONE_TYPE_NONE) {
            AlertDialog.Builder(this)
                .setTitle("SMS not supported")
                .setMessage("This device has no cellular capability.\n\n" +
                        "You'll need a phone with SMS to use this app.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
    }

    private fun requestLocationPermission() {
        if (locationHelper.hasLocationPermission(this)) {
            Toast.makeText(this, "GPS permission already granted", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
