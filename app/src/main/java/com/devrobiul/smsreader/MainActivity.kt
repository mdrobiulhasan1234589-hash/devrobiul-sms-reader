package com.devrobiul.smsreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvSmsPermissionStatus: TextView
    private lateinit var tvFirebaseStatus: TextView
    private lateinit var tvLastSms: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRequestSms: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnSettings: Button
    private lateinit var btnLogs: Button
    private lateinit var btnHistory: Button

    private val SMS_PERMISSION_CODE = 200
    private val NOTIF_PERMISSION_CODE = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestNotificationPermissionIfNeeded()
        requestSmsPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()

        if (Prefs.isMonitoringEnabled(this)) {
            PaymentMonitorService.start(this)
        }

        // Auto-test Firebase connection if config exists
        if (Prefs.isFirebaseConfigured(this)) {
            runFirebaseTestSilent()
        }
    }

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvSmsPermissionStatus = findViewById(R.id.tvSmsPermissionStatus)
        tvFirebaseStatus = findViewById(R.id.tvFirebaseStatus)
        tvLastSms = findViewById(R.id.tvLastSms)
        btnStart = findViewById(R.id.btnStartMonitoring)
        btnStop = findViewById(R.id.btnStopMonitoring)
        btnRequestSms = findViewById(R.id.btnRequestSmsPermission)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSettings = findViewById(R.id.btnSettings)
        btnLogs = findViewById(R.id.btnLogs)
        btnHistory = findViewById(R.id.btnHistory)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            if (!hasSmsPermission()) {
                showToast("Please grant SMS permission first")
                requestSmsPermissionIfNeeded()
                return@setOnClickListener
            }
            if (!Prefs.isFirebaseConfigured(this)) {
                showToast("Please configure Firebase in Settings")
                showSettingsDialog()
                return@setOnClickListener
            }
            Prefs.setMonitoringEnabled(this, true)
            PaymentMonitorService.start(this)
            LogManager.add(this, "INFO", "Monitoring started")
            refreshUiState()
        }

        btnStop.setOnClickListener {
            Prefs.setMonitoringEnabled(this, false)
            PaymentMonitorService.stop(this)
            LogManager.add(this, "INFO", "Monitoring stopped")
            refreshUiState()
        }

        btnRequestSms.setOnClickListener {
            requestSmsPermissionIfNeeded()
        }

        btnTestConnection.setOnClickListener { runFirebaseTest() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnLogs.setOnClickListener { showLogsDialog() }
        btnHistory.setOnClickListener { showHistoryDialog() }
    }

    private fun refreshUiState() {
        // SMS Permission
        val hasSms = hasSmsPermission()
        tvSmsPermissionStatus.text = if (hasSms) "GRANTED ✓" else "NOT GRANTED"
        tvSmsPermissionStatus.setTextColor(getColor(if (hasSms) R.color.status_ok else R.color.status_error))

        // Service Status
        val monitoring = Prefs.isMonitoringEnabled(this)
        val serviceRunning = hasSms && monitoring && Prefs.isFirebaseConfigured(this)
        tvServiceStatus.text = if (serviceRunning) "RUNNING ✓" else "STOPPED"
        tvServiceStatus.setTextColor(getColor(if (serviceRunning) R.color.status_ok else R.color.status_error))

        // Firebase Status
        if (Prefs.isFirebaseConfigured(this)) {
            if (tvFirebaseStatus.text.isNullOrBlank() ||
                tvFirebaseStatus.text == "NOT CONFIGURED") {
                tvFirebaseStatus.text = "CHECKING..."
                tvFirebaseStatus.setTextColor(getColor(R.color.status_warn))
            }
        } else {
            tvFirebaseStatus.text = "NOT CONFIGURED"
            tvFirebaseStatus.setTextColor(getColor(R.color.status_warn))
        }

        btnStart.isEnabled = !monitoring
        btnStop.isEnabled = monitoring

        refreshLastSms()
    }

    private fun refreshLastSms() {
        val summary = Prefs.getLastSmsSummary(this)
        if (summary.isBlank()) {
            tvLastSms.text = "No SMS received yet"
            return
        }
        try {
            val parts = summary.split("|")
            if (parts.size >= 6) {
                val status = parts[0]
                val method = parts[1].uppercase()
                val amount = parts[2]
                val phone = parts[3]
                val trx = parts[4]
                val time = parts[5]
                tvLastSms.text = buildString {
                    append("Method: $method\n")
                    append("Amount: ৳$amount\n")
                    append("Sender: $phone\n")
                    append("TrxID: $trx\n")
                    append("Time: $time\n")
                    append("Status: $status")
                }
            } else {
                tvLastSms.text = "No SMS received yet"
            }
        } catch (_: Exception) {
            tvLastSms.text = "No SMS received yet"
        }
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermissionIfNeeded() {
        if (!hasSmsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                ),
                SMS_PERMISSION_CODE
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            refreshUiState()
            if (hasSmsPermission()) {
                showToast("SMS permission granted ✓")
            } else {
                showToast("SMS permission is required to read payment messages")
            }
        }
    }

    private fun runFirebaseTest() {
        tvFirebaseStatus.text = "TESTING..."
        tvFirebaseStatus.setTextColor(getColor(R.color.status_warn))

        activityScope.launch {
            val (success, message) = withContext(Dispatchers.IO) {
                FirebaseHelper.testConnection(this@MainActivity)
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    tvFirebaseStatus.text = "CONNECTED ✓"
                    tvFirebaseStatus.setTextColor(getColor(R.color.status_ok))
                    showToast(message)
                } else {
                    tvFirebaseStatus.text = "FAILED"
                    tvFirebaseStatus.setTextColor(getColor(R.color.status_error))
                    showToast(message)
                }
            }
        }
    }

    private fun runFirebaseTestSilent() {
        tvFirebaseStatus.text = "CHECKING..."
        tvFirebaseStatus.setTextColor(getColor(R.color.status_warn))

        activityScope.launch {
            val (success, _) = withContext(Dispatchers.IO) {
                FirebaseHelper.testConnection(this@MainActivity)
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    tvFirebaseStatus.text = "CONNECTED ✓"
                    tvFirebaseStatus.setTextColor(getColor(R.color.status_ok))
                } else {
                    tvFirebaseStatus.text = "FAILED"
                    tvFirebaseStatus.setTextColor(getColor(R.color.status_error))
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        val etApiKey = view.findViewById<EditText>(R.id.etFirebaseApiKey)
        val etDbUrl = view.findViewById<EditText>(R.id.etFirebaseDbUrl)
        val etProjectId = view.findViewById<EditText>(R.id.etFirebaseProjectId)
        val etAppId = view.findViewById<EditText>(R.id.etFirebaseAppId)
        val etSenderId = view.findViewById<EditText>(R.id.etFirebaseSenderId)
        val etDataPath = view.findViewById<EditText>(R.id.etDataPath)
        val cbBkash = view.findViewById<CheckBox>(R.id.cbBkash)
        val cbNagad = view.findViewById<CheckBox>(R.id.cbNagad)
        val cbRocket = view.findViewById<CheckBox>(R.id.cbRocket)

        etApiKey.setText(Prefs.getFirebaseApiKey(this))
        etDbUrl.setText(Prefs.getFirebaseDbUrl(this))
        etProjectId.setText(Prefs.getFirebaseProjectId(this))
        etAppId.setText(Prefs.getFirebaseAppId(this))
        etSenderId.setText(Prefs.getFirebaseSenderId(this))
        etDataPath.setText(Prefs.getDataPath(this))
        cbBkash.isChecked = Prefs.isBkashEnabled(this)
        cbNagad.isChecked = Prefs.isNagadEnabled(this)
        cbRocket.isChecked = Prefs.isRocketEnabled(this)

        AlertDialog.Builder(this)
            .setTitle("Firebase Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val apiKey = etApiKey.text.toString().trim()
                val dbUrl = etDbUrl.text.toString().trim()
                val projectId = etProjectId.text.toString().trim()
                val appId = etAppId.text.toString().trim()
                val senderId = etSenderId.text.toString().trim()
                val dataPath = etDataPath.text.toString().trim().ifBlank { "XNXANIKPAY" }

                if (apiKey.isBlank() || dbUrl.isBlank() || appId.isBlank()) {
                    showToast("API Key, Database URL, App ID are required")
                    return@setPositiveButton
                }

                Prefs.setFirebaseApiKey(this, apiKey)
                Prefs.setFirebaseDbUrl(this, dbUrl)
                Prefs.setFirebaseProjectId(this, projectId)
                Prefs.setFirebaseAppId(this, appId)
                Prefs.setFirebaseSenderId(this, senderId)
                Prefs.setDataPath(this, dataPath)
                Prefs.setBkashEnabled(this, cbBkash.isChecked)
                Prefs.setNagadEnabled(this, cbNagad.isChecked)
                Prefs.setRocketEnabled(this, cbRocket.isChecked)

                FirebaseHelper.reset()

                LogManager.add(this, "INFO", "Firebase config updated")
                refreshUiState()
                runFirebaseTestSilent()
                showToast("Settings saved ✓")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                Prefs.clearFirebaseConfig(this)
                FirebaseHelper.reset()
                LogManager.add(this, "INFO", "Firebase config cleared")
                refreshUiState()
                showToast("Firebase config cleared")
            }
            .show()
    }

    private fun showLogsDialog() {
        val logs = LogManager.getList(this)
        val message = if (logs.isEmpty()) "No logs yet" else logs.joinToString("\n\n")

        val scrollView = ScrollView(this).apply {
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#0A0A1A"))
        }

        val textView = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 12f
            setLineSpacing(8f, 1f)
            setPadding(10, 10, 10, 10)
            setTextIsSelectable(true)
        }

        scrollView.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("📋 Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                LogManager.clear(this)
                showToast("Logs cleared")
            }
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.background_dark)
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                dialog.findViewById<TextView>(titleId)?.setTextColor(Color.WHITE)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#3B6BFF"))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#FF9800"))
        }

        dialog.show()
    }

    private fun showHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("History")
            .setMessage(
                "SMS history is available in the Logs screen.\n\n" +
                        "Every parsed SMS is logged with method, amount, sender, and TrxID."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
