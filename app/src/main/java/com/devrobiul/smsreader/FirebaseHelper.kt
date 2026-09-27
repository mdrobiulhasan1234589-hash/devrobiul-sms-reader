package com.devrobiul.smsreader

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FirebaseHelper {

    private var initializedDbUrl: String = ""

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

            val existingApps = FirebaseApp.getApps(context)
            val defaultApp = existingApps.firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

            if (defaultApp != null) {
                if (initializedDbUrl == dbUrl) {
                    return true
                }
                try { defaultApp.delete() } catch (_: Exception) { }
            }

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

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

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
     * RELIABLE connection test.
     * Writes a test value under user's data path, reads it back, then deletes it.
     * This avoids the .info/connected path which often fails in Android Firebase SDK.
     */
    fun testConnection(context: Context): Pair<Boolean, String> {
        return try {
            if (!initFirebase(context)) {
                return Pair(false, "Firebase config incomplete or invalid")
            }

            val database = Firebase.database
            val dataPath = Prefs.getDataPath(context)
            val testRef = database.getReference(dataPath).child("_devrobiul_test")

            val writeLatch = java.util.concurrent.CountDownLatch(1)
            var writeError: String? = null

            val testData = mapOf(
                "test" to true,
                "timestamp" to System.currentTimeMillis(),
                "source" to "DevRobiulSmsReader"
            )

            testRef.setValue(testData)
                .addOnSuccessListener { writeLatch.countDown() }
                .addOnFailureListener { e ->
                    writeError = e.message
                    writeLatch.countDown()
                }

            writeLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)

            if (writeError != null) {
                val msg = when {
                    writeError!!.contains("Permission denied", ignoreCase = true) ->
                        "Permission denied. Check Firebase Rules (allow .read/.write)"
                    writeError!!.contains("not exist", ignoreCase = true) ->
                        "Database not found. Check Database URL"
                    else -> "Write failed: $writeError"
                }
                LogManager.add(context, "ERROR", "Test write failed: $writeError")
                return Pair(false, msg)
            }

            // Success — delete the test value
            try { testRef.removeValue() } catch (_: Exception) { }

            LogManager.add(context, "INFO", "Firebase test connection SUCCESS")
            Pair(true, "Firebase reachable ✓")
        } catch (e: Exception) {
            LogManager.add(context, "ERROR", "Test exception: ${e.message}")
            Pair(false, "Error: ${e.message}")
        }
    }

    @Synchronized
    fun reset() {
        initializedDbUrl = ""
    }
}
