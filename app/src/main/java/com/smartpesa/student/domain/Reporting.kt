package com.smartpesa.student.domain

import com.smartpesa.student.data.Transaction
import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object Reporting {
    fun month(timestamp: Long): String = YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Africa/Nairobi"))).toString()
    /** CSV quoting plus formula neutralization protects a spreadsheet opening student-entered text. */
    fun csv(transactions: List<Transaction>, out: Writer) {
        fun quote(text: String): String {
            val safe = if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$text" else text
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        out.write("Date,Type,Category,Description,Amount (KSh)\r\n")
        transactions.forEach { t -> out.write(listOf(Instant.ofEpochMilli(t.timestamp).toString(), if (t.income) "Income" else "Expense",
            t.category, t.description, BigDecimal.valueOf(t.amountMinor, 2).toPlainString()).joinToString(",") { quote(it) } + "\r\n") }
    }
    fun total(rows: List<Transaction>): Long = rows.fold(0L) { sum, t -> Math.addExact(sum, t.amountMinor) }
    /** A direct jump past 100% consumes both crossings and sends just the exceeded warning. */
    fun crossed(spent: Long, limit: Long): Int = if (limit <= 0) 0 else {
        val s = java.math.BigInteger.valueOf(spent).multiply(java.math.BigInteger.valueOf(100))
        val l = java.math.BigInteger.valueOf(limit)
        if (s >= l.multiply(java.math.BigInteger.valueOf(100))) 100 else if (s >= l.multiply(java.math.BigInteger.valueOf(80))) 80 else 0
    }
}
