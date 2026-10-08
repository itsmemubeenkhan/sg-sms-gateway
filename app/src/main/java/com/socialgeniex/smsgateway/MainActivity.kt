package com.socialgeniex.smsgateway

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.socialgeniex.smsgateway.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Glanceable main screen: one big status pill, today's sent count, and only
 * two buttons ("Scan QR", "Send test SMS"). Everything technical lives
 * behind the "Advanced / Logs" section.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val logListener: () -> Unit = { renderLogs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isLinked) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnScan.setOnClickListener {
            startActivity(
                Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_RELINK, true)
            )
        }
        b.btnTest.setOnClickListener { showTestDialog() }
        b.advancedHeader.setOnClickListener { toggleAdvanced() }
        b.btnUnlink.setOnClickListener { confirmUnlink() }
        b.btnBattery.setOnClickListener { promptBatteryOpt() }

        // Keep the gateway running whenever the app is opened.
        GatewayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        if (!prefs.isLinked) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        refresh()
        LogStore.addListener(logListener)
        renderLogs()
        if (!prefs.batteryPromptShown) {
            prefs.batteryPromptShown = true
            showBatteryDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        LogStore.removeListener(logListener)
    }

    // ---------------- UI ----------------

    private fun refresh() {
        // Status pill: green Connected, red Error (recent problem), amber Not connected.
        val err = prefs.lastError
        val recent = System.currentTimeMillis() - prefs.lastErrorAt < 10 * 60 * 1000
        if (err != null && recent) {
            b.statusPill.text = "Error"
            b.statusPill.setBackgroundResource(R.drawable.pill_red)
            b.statusSub.text = err
            b.statusSub.visibility = View.VISIBLE
        } else if (prefs.isLinked) {
            b.statusPill.text = "Connected"
            b.statusPill.setBackgroundResource(R.drawable.pill_green)
            b.statusSub.visibility = View.GONE
        } else {
            b.statusPill.text = "Not connected"
            b.statusPill.setBackgroundResource(R.drawable.pill_amber)
            b.statusSub.visibility = View.GONE
        }

        b.sentCount.text = prefs.sentToday.toString()
        b.failedCount.text = "Failed today: ${prefs.failedToday}"
        b.queueCount.text = "Waiting: ${JobTracker.pendingCount()}"

        // Advanced (hidden by default)
        b.tvServer.text = prefs.baseUrl ?: "—"
        b.tvToken.text = prefs.maskedToken()
        b.tvGateway.text = if (prefs.gatewayId > 0) "#${prefs.gatewayId}" else "—"
        val sims = SimHelper.getSims(this)
        b.tvSims.text = if (sims.isEmpty()) {
            "No SIM info — allow phone permission"
        } else {
            sims.joinToString("\n") { "SIM ${it.slot + 1}: ${it.label}${it.number?.let { n -> " ($n)" } ?: ""}" }
        }
    }

    private fun toggleAdvanced() {
        val open = b.advancedBody.visibility == View.VISIBLE
        b.advancedBody.visibility = if (open) View.GONE else View.VISIBLE
        b.advancedHeader.text = if (open) "Advanced / Logs ▸" else "Advanced / Logs ▾"
        if (!open) renderLogs()
    }

    private fun renderLogs() {
        if (!::b.isInitialized) return
        b.logText.text = LogStore.recent().joinToString("\n")
        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- test SMS ----------------

    private fun showTestDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etPhone = EditText(this).apply {
            hint = "Phone number"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val etMsg = EditText(this).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        layout.addView(etPhone)
        layout.addView(etMsg)

        MaterialAlertDialogBuilder(this)
            .setTitle("Send test SMS")
            .setMessage("This goes through SocialGeniex and your SIM, like a real message.")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val to = etPhone.text.toString().trim()
                val msg = etMsg.text.toString().trim()
                if (to.isEmpty() || msg.isEmpty()) {
                    toast("Enter a number and a message")
                } else {
                    sendTest(to, msg)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTest(to: String, msg: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ApiClient(prefs.baseUrl!!, prefs.deviceToken!!).sendTest(to, msg)
                LogStore.add("Test SMS queued to $to")
                withContext(Dispatchers.Main) { toast("Test sent — watch it go through") }
            } catch (t: Throwable) {
                val m = PlainErrors.msg(t)
                LogStore.add("Test failed: $m")
                withContext(Dispatchers.Main) { toast(m) }
            }
        }
    }

    // ---------------- unlink / battery ----------------

    private fun confirmUnlink() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unlink this phone?")
            .setMessage("This phone will stop sending messages for SocialGeniex. You can link it again anytime by scanning a new QR code.")
            .setPositiveButton("Unlink") { _, _ ->
                prefs.clearAll()
                GatewayService.stop(this)
                PollWorker.cancelAll(this)
                LogStore.add("Phone unlinked")
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatteryDialog() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Keep it running")
            .setMessage("To send messages reliably:\n\n1. Allow background running without battery limits (next screen).\n\n2. On Infinix/Xiaomi/Oppo/Vivo also do this: Phone Settings > Apps > SocialGeniex SMS > Battery > set to Unrestricted, and enable Autostart.\n\n3. Lock this app in your recent-apps screen so the system never swipes it away.")
            .setPositiveButton("Allow") { _, _ -> promptBatteryOpt() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptBatteryOpt() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t2: Exception) {
                toast("Couldn't open settings")
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
