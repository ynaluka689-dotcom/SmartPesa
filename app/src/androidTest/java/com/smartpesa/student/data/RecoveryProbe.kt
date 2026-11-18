package com.smartpesa.student.data

import android.app.Instrumentation
import android.os.Bundle
import com.smartpesa.student.services.LedgerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/** Shell-driven acceptance probe: stage -> kill process -> reopen app -> verify encrypted recovery.
 * Separate instrumentation avoids putting a simulator or developer menu in the student app.
 */
class RecoveryProbe : Instrumentation() {
    private var mode = ""
    override fun onCreate(arguments: Bundle?) { mode = arguments?.getString("mode").orEmpty(); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName == "com.smartpesa.student.ledgertests")
            runBlocking {
                val dao = FinanceDatabase.get(targetContext).financeDao()
                when (mode) {
                    "stage" -> {
                        check(dao.allTransactions().isEmpty()) { "Probe requires an empty disposable ledger" }
                        LedgerService(targetContext, dao, background = false).setMessageAccess(true)
                        val generation = targetContext.getSharedPreferences("settings", 0).getLong("generation", 0)
                        dao.stagePayment(PendingPayment("ACC1234567", 25000, false, "Acceptance KFC", System.currentTimeMillis(), 600, generation))
                        check(dao.pendingPayments(generation).size == 1)
                        result.putString("result", "Parsed payment staged in encrypted storage")
                    }
                    "verify" -> {
                        var rows = dao.allTransactions()
                        var attempts = 0
                        while (rows.size != 2 && attempts++ < 50) { delay(100); rows = dao.allTransactions() }
                        check(rows.size == 2 && rows.sumOf { it.amountMinor } == 25600L)
                        check(rows.any { it.mpesaCode == "ACC1234567" && it.category == "Food & Dining" })
                        val generation = targetContext.getSharedPreferences("settings", 0).getLong("generation", 0)
                        check(dao.pendingPayments(generation).isEmpty())
                        val header = targetContext.getDatabasePath("smartpesa_secure.db").inputStream().use { stream -> ByteArray(16).also { check(stream.read(it) == 16) } }
                        check(!header.contentEquals("SQLite format 3\u0000".toByteArray()))
                        // Starting instrumentation restarts the target and may clear active notifications.
                        // Inspect the active notification key from the shell BEFORE this probe starts.
                        FinanceDatabase.get(targetContext).openHelper.writableDatabase.query("SELECT COUNT(*) FROM alerts WHERE eventKey LIKE 'receipt:%' AND delivered=1").use {
                            check(it.moveToFirst() && it.getInt(0) == 1)
                        }
                        result.putString("result", "Process restart recovered payment, fee and one receipt from encrypted staging without inbox permission")
                    }
                    else -> error("Expected stage or verify mode")
                }
            }
            finish(android.app.Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("error_type", failure.javaClass.simpleName)
            result.putString("error_location", failure.stackTrace.firstOrNull { it.className.startsWith("com.smartpesa") }?.toString())
            result.putString("result", "Acceptance probe failed; preserve the disposable ledger for investigation")
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }
}
