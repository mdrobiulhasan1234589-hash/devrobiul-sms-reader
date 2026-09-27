package com.devrobiul.smsreader

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FirebaseHelper — Dynamically initializes Firebase from user-provided config
 * (stored in Prefs) and pushes SMS data to Realtime Database.
 *
 * No google-services.json required. Firebase is initialized at runtime.
 *
 * Handles re-initialization properly: if [DEFAULT] app already exists with the
 * same config, it reuses it. If config changed, it deletes and re-creates.
 */
object FirebaseHelper {

    private var initializedDbUrl: String = ""

    /**
     * Initialize Firebase using the config stored in Prefs.
     * Safe to call multiple times.
     */
    @Synchronized
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

            // Check if a [DEFAULT] app already exists
            val existingApps = FirebaseApp.getApps(context)
            val defaultApp = existingApps.firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

            if (defaultApp != null) {
                // If the DB URL matches the cached one, reuse it
                if (initializedDbUrl == dbUrl) {
                    return true
                }

                // Config changed — delete the old [DEFAULT] instance and recreate
                try {
                    defaultApp.delete()
                } catch (_: Exception) { }
            }

            // Delete any other named apps to avoid conflicts
            try {
                FirebaseApp.getApps(context).forEach { app ->
                    try { app.delete() } catch (_: Exception) { }
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
     */
    fun pushSmsRecord(context: Context, sms: SmsParser.SmsData): Boolean {
        return try {
            if (!initFirebase(context)) {
                LogManager.add(context, "ERROR", "Push skipped — Firebase not initialized")
                return false
            }

            val database = Firebase.database
            val dataPath = Prefs.getDataPath(context)

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

            val latch = java.util.concurrent.CountDownLatch(1)
            var success = false

            task.addOnSuccessListener {
                success = true
                latch.countDown()
            }.addOnFailureListener { e ->
                LogManager.add(context, "ERROR", "Push failed: ${e.message}")
                latch.countDown()
            }

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
    @Synchronized
    fun reset() {
        initializedDbUrl = ""
    }
}
