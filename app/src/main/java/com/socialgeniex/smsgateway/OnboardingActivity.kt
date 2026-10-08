package com.socialgeniex.smsgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.socialgeniex.smsgateway.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * First-launch guided flow, max 3 steps:
 *  1. Allow permissions (one button, one-line plain-language why)
 *  2. Scan QR code (big camera button)
 *  3. Done — big green "Connected ✓" with the phone name
 *
 * Launched with EXTRA_RELINK=true to re-scan (jumps straight to step 2).
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RELINK = "relink"
    }

    private lateinit var b: ActivityOnboardingBinding
    private var relink = false

    private val wantedPerms: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.CAMERA
            )
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            return list.toTypedArray()
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermStatus()
        }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQr(result.contents)
        } else {
            b.scanError.text = "Scan cancelled — try again"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)
        relink = intent.getBooleanExtra(EXTRA_RELINK, false)

        b.btnAllow.setOnClickListener { permLauncher.launch(wantedPerms) }
        b.btnNext1.setOnClickListener { showStep(2) }
        b.btnScanQr.setOnClickListener { startScan() }
        b.btnBack2.setOnClickListener { showStep(1) }
        b.btnStart.setOnClickListener {
            if (relink) {
                finish()
            } else {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        showStep(if (relink) 2 else 1)
        updatePermStatus()
    }

    // ---------------- steps ----------------

    private fun showStep(n: Int) {
        b.stepIndicator.text = "Step $n of 3"
        b.step1.visibility = if (n == 1) View.VISIBLE else View.GONE
        b.step2.visibility = if (n == 2) View.VISIBLE else View.GONE
        b.step3.visibility = if (n == 3) View.VISIBLE else View.GONE
    }

    private fun updatePermStatus() {
        b.permStatus.text = if (granted(Manifest.permission.SEND_SMS)) {
            "✓ Ready — all set"
        } else {
            "⚠ SMS permission is off — the app can't send messages until you allow it"
        }
    }

    private fun granted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ---------------- QR scan + link ----------------

    private fun startScan() {
        b.scanError.text = ""
        if (!granted(Manifest.permission.CAMERA)) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the QR code shown in SocialGeniex")
                .setBeepEnabled(true)
                .setOrientationLocked(true)
        )
    }

    /** QR format: {"u": "<server base url>", "t": "<device_token>"} */
    private fun handleQr(contents: String) {
        val (base, token) = try {
            val o = JSONObject(contents)
            o.optString("u", "").trim().trimEnd('/') to o.optString("t", "").trim()
        } catch (e: Exception) {
            "" to ""
        }
        if (base.isEmpty() || token.isEmpty()) {
            b.scanError.text = "That QR code isn't a SocialGeniex gateway code"
            return
        }
        doRegister(base, token)
    }

    private fun doRegister(base: String, token: String) {
        b.btnScanQr.isEnabled = false
        b.scanError.text = "Linking…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sims = SimHelper.getSims(this@OnboardingActivity)
                val simsJson = JSONArray()
                for (s in sims) {
                    simsJson.put(
                        JSONObject()
                            .put("slot", s.slot)
                            .put("label", s.label)
                            .put("number", s.number ?: JSONObject.NULL)
                    )
                }
                val fcm = fetchFcmToken()
                val api = ApiClient(base, token)
                val res = api.register(fcm, deviceName(), simsJson, appVersion())

                val prefs = Prefs(this@OnboardingActivity)
                prefs.baseUrl = base
                prefs.deviceToken = token
                prefs.gatewayId = res.optLong("gateway_id", 0L)
                prefs.deviceName = deviceName()
                res.optJSONObject("settings")?.let { s ->
                    prefs.pollIntervalSec = s.optInt("poll_interval", 20).coerceAtLeast(10)
                    prefs.delayMs = s.optLong("delay_ms", 2000L).coerceAtLeast(0L)
                    prefs.perSimQuota = s.optInt("per_sim_daily_quota", 0).coerceAtLeast(0)
                }
                prefs.onboarded = true
                prefs.lastError = null

                LogStore.add("Linked ✓ — ${sims.size} SIM(s) found")
                withContext(Dispatchers.Main) {
                    b.doneName.text = deviceName()
                    showStep(3)
                    GatewayService.start(this@OnboardingActivity)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    b.btnScanQr.isEnabled = true
                    b.scanError.text = PlainErrors.msg(t)
                }
            }
        }
    }

    /**
     * FCM is optional — without google-services.json this throws and we
     * simply register with no push token.
     */
    private suspend fun fetchFcmToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } catch (t: Throwable) {
            cont.resume(null)
        }
    }

    private fun deviceName(): String {
        val m = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return m.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (t: Throwable) {
        "1.0.0"
    }
}
