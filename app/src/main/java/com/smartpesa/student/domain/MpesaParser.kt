package com.smartpesa.student.domain

import com.smartpesa.student.data.parseMoney
import java.time.LocalDateTime
import java.time.ZoneId

/** Strict supported formats: never infer a purchase from the first amount in an arbitrary SMS. */
data class Payment(val code: String, val amount: Long, val income: Boolean, val merchant: String,
    val timestamp: Long, val fee: Long = 0)
object MpesaParser {
    fun trustedSender(sender: String) = sender.trim().equals("MPESA", true) || sender.trim().equals("M-PESA", true)
    fun parseParts(parts: List<Pair<String, String>>): Payment? {
        if (parts.isEmpty() || parts.any { !trustedSender(it.first) } || parts.map { it.first.uppercase() }.distinct().size != 1) return null
        return parse(parts.joinToString("") { it.second })
    }
    fun parse(body: String): Payment? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        val code = Regex("^([A-Z0-9]{10}) Confirmed\\.", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase() ?: return null
        // Cash withdrawals, reversals and loan messages need a separate accounting flow; reject them.
        if (Regex("\\b(reversed|reversal|withdraw|withdrawn|fuliza|mshwari|m-shwari)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val date = Regex(" on (\\d{1,2})/(\\d{1,2})/(\\d{2}|\\d{4}) at (\\d{1,2}):(\\d{2}) ?(AM|PM)\\.", RegexOption.IGNORE_CASE).find(text) ?: return null
        val timestamp = runCatching {
            val g = date.groupValues
            val year = g[3].toInt().let { if (it < 100) 2000 + it else it }
            val hour = g[4].toInt(); require(hour in 1..12)
            LocalDateTime.of(year, g[2].toInt(), g[1].toInt(), hour % 12 + if (g[6].equals("PM", true)) 12 else 0, g[5].toInt())
                .atZone(ZoneId.of("Africa/Nairobi")).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val prefix = text.substring(0, date.range.first)
        val incomeMatch = Regex("You have received Ksh\\s*([\\d,]+(?:\\.\\d{1,2})?) from (.+)$", RegexOption.IGNORE_CASE).find(prefix)
        val airtimeMatch = Regex("You bought Ksh\\s*([\\d,]+(?:\\.\\d{1,2})?) of airtime(?: for .+)?$", RegexOption.IGNORE_CASE).find(prefix)
        val sentMatch = Regex("Ksh\\s*([\\d,]+(?:\\.\\d{1,2})?) (?:paid to|sent to) (.+)$", RegexOption.IGNORE_CASE).find(prefix)
        val match = incomeMatch ?: airtimeMatch ?: sentMatch ?: return null
        val amount = parseMoney(match.groupValues[1].replace(",", "")) ?: return null
        val merchant = if (airtimeMatch != null) "Safaricom Airtime" else match.groupValues[2]
            .replace(Regex(" for account .+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?:\\+?254|0)[17]\\d{8}"), "")
            .replace(Regex("^(?:M-PESA )?Till\\s+\\d*\\s*[-–]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd('.', ',').ifBlank { return null }
        val feeRaw = Regex("Transaction cost[, :] +Ksh\\s*([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val fee = if (feeRaw == null) 0L else if (feeRaw.replace(",", "").toBigDecimalOrNull()?.signum() == 0) 0L
            else parseMoney(feeRaw.replace(",", "")) ?: return null
        return Payment(code, amount, incomeMatch != null, merchant, timestamp, if (incomeMatch != null) 0 else fee)
    }
}
