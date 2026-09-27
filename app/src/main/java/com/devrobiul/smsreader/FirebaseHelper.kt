package com.devrobiul.smsreader

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FirebaseHelper — Dynamically initializes Firebase from user-provided config
 * (stored in Prefs) and pushes SMS data to Realtime Database.
 *
 * No google-services.json required. Firebase is initialized at runtime.
 */
object FirebaseHelper {

    private var isInitialized = false
    private var initializedDbUrl: String = ""

    /**
     * Initialize Firebase using the config stored in Prefs.
     * Safe to call multiple times — will re-initialize if the config changed.
     */
    fun initFirebase(context: Context): Boolean {
        try {
            val apiKey = Prefs.getFirebaseApiKey(context)
            val dbUrl = Prefs.getFirebaseDbUrl(context)
            val projectId = Prefs.getFirebaseProjectId(context)
            val appId = Prefs.getFirebaseAppId(context)

            if (apiKey.isBlank() || dbUrl.isBlank() || appId.isBlank()) {
                LogManager.add(context, "ERROR", "Firebase config incomplete")
                return false
            }

            // If already initialized with same DB URL, skip
            if (isInitialized && initializedDbUrl == dbUrl && FirebaseApp.getApps(context).isNotEmpty()) {
                return true
            }

            // Delete previous instances if config changed
            try {
                FirebaseApp.getApps(context).forEach { app ->
                    if (app.name != "[DEFAULT]") {
                        app.delete()
                    }
                }
            } catch (_: Exception) { }

            val options = FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setProjectId(projectId)
                .setDatabaseUrl(dbUrl)
                .build()

            FirebaseApp.initializeApp(context, options)

            initializedDbUrl = dbUrl
            isInitialized = true

            LogManager.add(context, "INFO", "Firebase initialized — $projectId")
            return true
        } catch (e: Exception) {
            LogManager.add(context, "ERROR", "Firebase init failed: ${e.message}")
            return false
        }
    }

    /**
     * Push a parsed SMS record to the user's Firebase Realtime Database.
     * Path: /{dataPath}/{trxId}
     *
     * Returns true if the push succeeded.
     */
    fun pushSmsRecord(context: Context, sms: SmsParser.SmsData): Boolean {
        return try {
            if (!initFirebase(context)) {
                LogManager.add(context, "ERROR", "Push skipped — Firebase not initialized")
                return false
            }

            val database = Firebase.database
            val dataPath = Prefs.getDataPath(context)

            // Build record
            val timeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            val now = Date()

            val record = mapOf(
                "txid" to sms.trxId,
                "amount" to sms.amount,
                "sender" to sms.senderPhone,
                "method" to sms.method,
                "message" to sms.originalMessage,
                "timestamp" to System.currentTimeMillis(),
                "time" to timeFormat.format(now),
                "date" to dateFormat.format(now),
                "device" to "DevRobiulSmsReader"
            )

            val ref = database.getReference(dataPath).child(sms.trxId)
            val task = ref.setValue(record)

            // Wait for completion (with timeout behavior handled by Firebase)
            val latch = java.util.concurrent.CountDownLatch(1)
            var success = false

            task.addOnSuccessListener {
                success = true
                latch.countDown()
            }.addOnFailureListener { e ->
                LogManager.add(context, "ERROR", "Push failed: ${e.message}")
                latch.countDown()
            }

            // Wait up to 8 seconds
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)

            if (success) {
                LogManager.add(context, "SENT", "Pushed to Firebase: ${SmsParser.maskTrxId(sms.trxId)}")
            }

            success
        } catch (e: Exception) {
            LogManager.add(context, "ERROR", "Push exception: ${e.message}")
            false
        }
    }

    /**
     * Test Firebase connection — writes a small test record, then removes it.
     */
    fun testConnection(context: Context): Pair<Boolean, String> {
        return try {
            if (!initFirebase(context)) {
                return Pair(false, "Firebase config incomplete or invalid")
            }

            val database = Firebase.database
            val testRef = database.getReference(".info/connected")

            val latch = java.util.concurrent.CountDownLatch(1)
            var connected = false

            testRef.get()
                .addOnSuccessListener { snapshot ->
                    connected = snapshot.getValue(Boolean::class.java) ?: false
                    latch.countDown()
                }
                .addOnFailureListener {
                    latch.countDown()
                }

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

            if (connected) {
                Pair(true, "Firebase reachable ✓")
            } else {
                Pair(false, "Firebase not reachable — check Database URL and rules")
            }
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * Reset cached initialization state (used when user changes config).
     */
    fun reset() {
        isInitialized = false
        initializedDbUrl = ""
    }
}
