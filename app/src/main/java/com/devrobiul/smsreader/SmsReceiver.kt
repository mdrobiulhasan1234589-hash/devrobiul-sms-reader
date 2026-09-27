package com.devrobiul.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val recentTrxIds = LinkedHashMap<String, Long>()
        private const val TRX_MEMORY_WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_RECENT = 100
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

            LogManager.add(context, "DEBUG", "📩 SmsReceiver fired")

            if (!Prefs.isMonitoringEnabled(context)) {
                LogManager.add(context, "DEBUG", "⚠️ Monitoring disabled — ignoring SMS")
                return
            }

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (messages.isEmpty()) {
                LogManager.add(context, "DEBUG", "Empty message list")
                return
            }

            val fullBodyBuilder = StringBuilder()
            var sender = ""
            for (msg in messages) {
                fullBodyBuilder.append(msg.displayMessageBody ?: "")
                if (sender.isEmpty()) sender = msg.displayOriginatingAddress ?: ""
            }

            val fullBody = fullBodyBuilder.toString().trim()
            if (fullBody.isBlank()) {
                LogManager.add(context, "DEBUG", "Blank SMS body")
                return
            }

            LogManager.add(
                context,
                "DEBUG",
                "📨 SMS from $sender: ${fullBody.take(200)}"
            )

            val parsed = SmsParser.parse(fullBody) { logMsg ->
                LogManager.add(context, "DEBUG", logMsg)
            }

            if (parsed == null) {
                LogManager.add(context, "DEBUG", "❌ Parser returned NULL")
                return
            }

            // Provider filter
            when (parsed.method) {
                "bkash" -> if (!Prefs.isBkashEnabled(context)) return
                "nagad" -> if (!Prefs.isNagadEnabled(context)) return
                "rocket" -> if (!Prefs.isRocketEnabled(context)) return
            }

            // Duplicate check
            val now = System.currentTimeMillis()
            pruneOldTrxIds(now)

            synchronized(recentTrxIds) {
                val lastSeen = recentTrxIds[parsed.trxId]
                if (lastSeen != null && (now - lastSeen) < TRX_MEMORY_WINDOW_MS) {
                    LogManager.add(
                        context,
                        "DUPLICATE",
                        "Ignored duplicate TrxID ${SmsParser.maskTrxId(parsed.trxId)}"
                    )
                    return
                }
                recentTrxIds[parsed.trxId] = now
            }

            LogManager.add(
                context,
                "PARSED",
                "${parsed.method.uppercase()} ৳${parsed.amount} from " +
                        "${SmsParser.maskPhone(parsed.senderPhone)} " +
                        "TrxID ${SmsParser.maskTrxId(parsed.trxId)}"
            )

            scope.launch {
                val success = FirebaseHelper.pushSmsRecord(context, parsed)

                if (success) {
                    val summary = buildSummary(parsed)
                    Prefs.setLastSmsSummary(context, summary)
                    LogManager.add(
                        context,
                        "SENT",
                        "Firebase push OK — ${SmsParser.maskTrxId(parsed.trxId)}"
                    )
                } else {
                    LogManager.add(
                        context,
                        "ERROR",
                        "Firebase push FAILED — ${SmsParser.maskTrxId(parsed.trxId)}"
                    )
                }
            }

        } catch (e: Exception) {
            try {
                LogManager.add(context, "ERROR", "SmsReceiver error: ${e.message}")
            } catch (_: Exception) { }
        }
    }

    private fun pruneOldTrxIds(now: Long) {
        synchronized(recentTrxIds) {
            val iterator = recentTrxIds.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if ((now - entry.value) > TRX_MEMORY_WINDOW_MS) {
                    iterator.remove()
                }
            }
            while (recentTrxIds.size > MAX_RECENT) {
                val firstKey = recentTrxIds.keys.firstOrNull() ?: break
                recentTrxIds.remove(firstKey)
            }
        }
    }

    private fun buildSummary(sms: SmsParser.SmsData): String {
        return try {
            val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
            val maskedPhone = SmsParser.maskPhone(sms.senderPhone)
            val maskedTrx = SmsParser.maskTrxId(sms.trxId)
            "PUSHED|${sms.method}|${sms.amount}|$maskedPhone|$maskedTrx|${timeFormat.format(Date())}"
        } catch (_: Exception) {
            ""
        }
    }
}
