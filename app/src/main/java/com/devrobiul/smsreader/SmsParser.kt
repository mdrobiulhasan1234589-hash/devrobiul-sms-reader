package com.devrobiul.smsreader

object SmsParser {

    data class SmsData(
        val method: String,
        val amount: Double,
        val senderPhone: String,
        val trxId: String,
        val originalMessage: String
    )

    private val BKASH_KEYWORDS = listOf("bkash")
    private val NAGAD_KEYWORDS = listOf("nagad")
    private val ROCKET_KEYWORDS = listOf("rocket", "dbbl")

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

    private val AMOUNT_REGEX = Regex(
        """(?:Tk\.?|BDT\.?|৳)\s*([0-9]+(?:[.,][0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val SENDER_PHONE_REGEX = Regex(
        """(?:from|sender|num(?:ber)?|phone)?\s*(01[3-9][0-9]{8})""",
        RegexOption.IGNORE_CASE
    )

    private val TRXID_REGEX = Regex(
        """(?:trx\s*id|trxid|txn\s*id|txnid|transaction\s*id|transactionid)\s*[:\-]?\s*([A-Za-z0-9]{6,20})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse SMS. Returns SmsData or null.
     * The optional [logFn] callback logs each step for debugging.
     */
    fun parse(smsBody: String?, logFn: ((String) -> Unit)? = null): SmsData? {
        return try {
            val combined = smsBody?.trim() ?: return null
            if (combined.isBlank()) return null

            val lower = combined.lowercase()

            // 1. Detect provider
            val method = detectProvider(combined)
            if (method == null) {
                logFn?.invoke("Parser: no provider keyword found")
                return null
            }
            logFn?.invoke("Parser: provider=$method")

            // 2. Confirm incoming payment
            val looksLikeReceive = RECEIVE_PHRASES.any { lower.contains(it.lowercase()) }
            if (!looksLikeReceive) {
                logFn?.invoke("Parser: no receive phrase found")
                return null
            }
            logFn?.invoke("Parser: receive phrase OK")

            // 3. Extract amount
            val amountMatch = AMOUNT_REGEX.find(combined)
            if (amountMatch == null) {
                logFn?.invoke("Parser: amount regex no match")
                return null
            }
            val amountRaw = amountMatch.groupValues[1].replace(",", ".")
            val amount = amountRaw.toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                logFn?.invoke("Parser: amount parse failed — '$amountRaw'")
                return null
            }
            logFn?.invoke("Parser: amount=$amount")

            // 4. Extract sender phone
            val senderMatch = SENDER_PHONE_REGEX.find(combined)
            if (senderMatch == null) {
                logFn?.invoke("Parser: sender regex no match")
                return null
            }
            val senderPhone = senderMatch.groupValues[1]
            if (senderPhone.length != 11) {
                logFn?.invoke("Parser: sender length wrong — '$senderPhone'")
                return null
            }
            logFn?.invoke("Parser: sender=$senderPhone")

            // 5. Extract TrxID
            val trxMatch = TRXID_REGEX.find(combined)
            if (trxMatch == null) {
                logFn?.invoke("Parser: TrxID regex no match")
                return null
            }
            val trxId = trxMatch.groupValues[1].uppercase()
            if (trxId.length < 6) {
                logFn?.invoke("Parser: TrxID too short — '$trxId'")
                return null
            }
            logFn?.invoke("Parser: TrxID=$trxId")

            SmsData(
                method = method,
                amount = amount,
                senderPhone = senderPhone,
                trxId = trxId,
                originalMessage = combined
            )
        } catch (e: Exception) {
            logFn?.invoke("Parser exception: ${e.message}")
            null
        }
    }

    private fun detectProvider(text: String): String? {
        val lower = text.lowercase()
        if (BKASH_KEYWORDS.any { lower.contains(it) }) return "bkash"
        if (NAGAD_KEYWORDS.any { lower.contains(it) }) return "nagad"
        if (ROCKET_KEYWORDS.any { lower.contains(it) }) return "rocket"
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
