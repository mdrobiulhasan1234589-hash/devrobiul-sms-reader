package com.devrobiul.smsreader

/**
 * SmsParser — Parses bKash / Nagad / Rocket SMS messages.
 * Extracts: method, amount, sender phone, TrxID.
 *
 * Safe against malformed input — returns null on any failure.
 */
object SmsParser {

    data class SmsData(
        val method: String,
        val amount: Double,
        val senderPhone: String,
        val trxId: String,
        val originalMessage: String
    )

    // Amount: "Tk 10.00" / "Tk. 10" / "BDT 10.00" / "৳10" / "10.00 Tk"
    private val AMOUNT_REGEX = Regex(
        """(?:Tk\.?|BDT\.?|৳)\s*([0-9]+(?:[.,][0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // Sender phone: Bangladeshi number 01XXXXXXXXX (11 digits)
    private val SENDER_PHONE_REGEX = Regex(
        """(?:from|sender|num(?:ber)?|phone)?\s*(01[3-9][0-9]{8})""",
        RegexOption.IGNORE_CASE
    )

    // TrxID: "TrxID XXXXXXXXX" / "Txn ID: XXXX" / "Transaction ID XXXXX"
    private val TRXID_REGEX = Regex(
        """(?:trx\s*id|trxid|txn\s*id|txnid|transaction\s*id|transactionid)\s*[:\-]?\s*([A-Za-z0-9]{6,20})""",
        RegexOption.IGNORE_CASE
    )

    // Phrases indicating received money (must contain at least one)
    private val RECEIVE_PHRASES = listOf(
        "you have received",
        "you've received",
        "you have got",
        "money received",
        "received tk",
        "received taka",
        "cash in",
        "cash-in",
        "payment received",
        "পেয়েছেন",
        "পেয়েছি"
    )

    fun parse(smsBody: String?): SmsData? {
        return try {
            val combined = smsBody?.trim() ?: return null
            if (combined.isBlank()) return null

            val lower = combined.lowercase()

            // 1. Detect provider
            val method = detectProvider(combined) ?: return null

            // 2. Confirm incoming payment
            val looksLikeReceive = RECEIVE_PHRASES.any { lower.contains(it.lowercase()) }
            if (!looksLikeReceive) return null

            // 3. Extract amount
            val amountMatch = AMOUNT_REGEX.find(combined) ?: return null
            val amountRaw = amountMatch.groupValues[1].replace(",", ".")
            val amount = amountRaw.toDoubleOrNull() ?: return null
            if (amount <= 0.0) return null

            // 4. Extract sender phone
            val senderMatch = SENDER_PHONE_REGEX.find(combined) ?: return null
            val senderPhone = senderMatch.groupValues[1]
            if (senderPhone.length != 11) return null

            // 5. Extract TrxID
            val trxMatch = TRXID_REGEX.find(combined) ?: return null
            val trxId = trxMatch.groupValues[1].uppercase()
            if (trxId.length < 6) return null

            SmsData(
                method = method,
                amount = amount,
                senderPhone = senderPhone,
                trxId = trxId,
                originalMessage = combined
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun detectProvider(text: String): String? {
        val lower = text.lowercase()
        if (lower.contains("bkash")) return "bkash"
        if (lower.contains("nagad")) return "nagad"
        if (lower.contains("rocket") || lower.contains("dbbl")) return "rocket"
        return null
    }

    fun maskPhone(phone: String): String {
        return try {
            if (phone.length != 11) phone
            else phone.substring(0, 4) + "*****" + phone.substring(9)
        } catch (_: Exception) {
            phone
        }
    }

    fun maskTrxId(trxId: String): String {
        return try {
            if (trxId.length <= 6) trxId
            else trxId.substring(0, 3) + "******" + trxId.substring(trxId.length - 2)
        } catch (_: Exception) {
            trxId
        }
    }
}
