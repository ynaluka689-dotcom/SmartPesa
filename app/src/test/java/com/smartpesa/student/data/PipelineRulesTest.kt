package com.smartpesa.student.data

import com.smartpesa.student.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

/** Financial edge cases are regression tests, not research accuracy measurements. */
class PipelineRulesTest {
    private fun sms(code: String, detail: String, date: String = "5/10/26 at 9:30 AM") =
        "$code Confirmed. $detail on $date. New M-PESA balance is Ksh1,000.00. Transaction cost, Ksh6.00."
    @Test fun supportedPaymentsPreserveAmountsDirectionFeesAndDates() {
        for (detail in listOf("Ksh250.50 paid to KFC", "Ksh250.50 sent to Super Metro for account BUS", "Ksh250.50 paid to KPLC PREPAID", "Ksh250.50 sent to Jane Doe 0712345678")) {
            val row = MpesaParser.parse(sms("ABC1234567", detail))!!
            assertEquals(25050L, row.amount); assertFalse(row.income); assertEquals(600L, row.fee)
            assertEquals(Instant.parse("2026-10-05T06:30:00Z").toEpochMilli(), row.timestamp)
            assertFalse(row.merchant.contains("0712345678"))
        }
        val income = MpesaParser.parse(sms("INC1234567", "You have received Ksh2,000.00 from HELB"))!!
        assertTrue(income.income); assertEquals(200000L, income.amount); assertEquals(0L, income.fee)
        assertEquals("Safaricom Airtime", MpesaParser.parse(sms("AIR1234567", "You bought Ksh20.00 of airtime"))!!.merchant)
    }
    @Test fun multipartMessagesAreJoinedBeforeParsingAndUntrustedPartsAreRejected() {
        val body = sms("ABC1234567", "Ksh250.50 paid to KFC")
        val parts = listOf("MPESA" to body.take(40), "MPESA" to body.drop(40))
        assertEquals(25050L, MpesaParser.parseParts(parts)!!.amount)
        assertNull(MpesaParser.parseParts(listOf("MPESA" to body.take(40), "SPAM" to body.drop(40))))
    }
    @Test fun spamWithdrawalsReversalsAndInvalidDatesAreNotGuessed() {
        for (body in listOf("Win Ksh500 now", sms("ABC1234567", "Ksh10.00 withdrawn from agent"),
            sms("ABC1234567", "Ksh10.00 reversed from KFC"), sms("ABC1234567", "Ksh10.001 paid to KFC"),
            sms("ABC1234567", "Ksh10.00 paid to KFC", "31/2/26 at 9:30 AM"))) assertNull(body, MpesaParser.parse(body))
        assertTrue(MpesaParser.trustedSender("M-PESA")); assertFalse(MpesaParser.trustedSender("0712345678"))
    }
    @Test fun classificationBoundariesDirectionAndWordMatchingAreExplicit() {
        assertEquals("confirmed", Prediction("Other", 0.900001, "test").review)
        assertEquals("suggested", Prediction("Other", 0.9, "test").review)
        assertEquals("suggested", Prediction("Other", 0.7, "test").review)
        assertEquals("required", Prediction("Other", 0.699999, "test").review)
        val classifier = Categorizer(mapOf("rent" to "Accommodation", "kfc" to "Food & Dining"), null)
        assertEquals("Income", classifier.predict("rent HELB", true, "Shopping").category)
        assertEquals("Other", classifier.predict("parent", false).category)
        assertEquals("Health", classifier.predict("KFC", false, "Health").category)
    }
    @Test fun thresholdsAndMonthsUseExactMoneyAndNairobiBoundaries() {
        assertEquals(0, Reporting.crossed(7999, 10000)); assertEquals(80, Reporting.crossed(8000, 10000))
        assertEquals(100, Reporting.crossed(10000, 10000)); assertEquals(100, Reporting.crossed(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals("2026-11", Reporting.month(Instant.parse("2026-10-31T21:00:00Z").toEpochMilli()))
    }
    @Test fun exportedCsvQuotesAndNeutralizesSpreadsheetFormulas() {
        val output = StringWriter()
        Reporting.csv(listOf(Transaction(amountMinor = 1250, income = false, category = "Other", description = "=SUM(1,2)\n\"quoted\"", timestamp = 0)), output)
        assertTrue(output.toString().contains("\"'=SUM(1,2)\n\"\"quoted\"\"\""))
        assertTrue(output.toString().endsWith("\"12.50\"\r\n"))
    }
}
