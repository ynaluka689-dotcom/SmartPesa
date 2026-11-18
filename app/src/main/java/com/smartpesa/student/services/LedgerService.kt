package com.smartpesa.student.services

import android.content.Context
import com.smartpesa.student.data.*
import com.smartpesa.student.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One application-owned write path; the Room transactions provide rollback and unique-code safety. */
class LedgerService(private val context: Context, private val dao: FinanceDao, private val background: Boolean = true) {
    companion object { internal val mutex = Mutex() }
    private val settings get() = context.getSharedPreferences("settings", 0)
    private val categorizer by lazy { ModelLoader.load(context) }
    /** Ledger success is independent of notification dispatch; the durable outbox recovers failures. */
    private suspend fun afterCommit() {
        if (!background) return
        try { NotificationDelivery.dispatch(context, dao) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        runCatching { BackgroundJobs.alerts(context) }
    }
    suspend fun setMessageAccess(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { check(settings.edit().putBoolean("messages", enabled)
            .putLong("generation", settings.getLong("generation", 0) + 1).commit()); dao.clearPending() }
        if (enabled && background) runCatching { BackgroundJobs.messages(context) }
    }
    suspend fun add(row: Transaction) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.commitRows(listOf(row), null, true, System.currentTimeMillis()) }
        afterCommit()
    }
    suspend fun edit(row: Transaction) = withContext(Dispatchers.IO) {
        val revised = if (row.source == "sms") row.copy(reviewState = "confirmed", classificationSource = "correction", confidence = 1.0) else row
        mutex.withLock { dao.editRow(revised, System.currentTimeMillis()) }
        afterCommit()
    }
    suspend fun delete(row: Transaction) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.deleteRow(row, System.currentTimeMillis()) }; afterCommit()
    }
    suspend fun budget(category: String, limit: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { dao.changeBudget(Budget(category, limit), System.currentTimeMillis()) }; afterCommit()
    }
    suspend fun removeBudget(category: String) = withContext(Dispatchers.IO) { mutex.withLock { dao.removeBudget(category) } }
    suspend fun receive(payment: Payment, generation: Long) = withContext(Dispatchers.IO) {
        val accepted = mutex.withLock {
            if (!settings.getBoolean("messages", false) || generation != settings.getLong("generation", 0)) false
            else { dao.stagePayment(PendingPayment(payment.code, payment.amount, payment.income, payment.merchant, payment.timestamp, payment.fee, generation)); true }
        }
        if (accepted) {
            runCatching { BackgroundJobs.messages(context) }
            ingest(payment, historical = false, generation = generation)
        }
    }
    suspend fun recoverLive(generation: Long) = withContext(Dispatchers.IO) {
        dao.pendingPayments(generation).forEach { row ->
            ingest(Payment(row.code, row.amount, row.income, row.merchant, row.timestamp, row.fee), false, generation)
        }
    }
    /** Only known formats reach this method. No raw SMS body is stored or logged. */
    suspend fun ingest(payment: Payment, historical: Boolean, generation: Long = settings.getLong("generation", 0)): Boolean = withContext(Dispatchers.IO) {
        val inserted = mutex.withLock {
            if (!settings.getBoolean("messages", false) || generation != settings.getLong("generation", 0)) return@withLock false
            val correction = dao.rule(normalize(payment.merchant))?.category
            val prediction = categorizer.predict(payment.merchant, payment.income, correction)
            val row = Transaction(amountMinor = payment.amount, income = payment.income,
                category = if (prediction.review == "required") "Other" else prediction.category,
                description = payment.merchant, timestamp = payment.timestamp, mpesaCode = payment.code,
                merchant = payment.merchant, source = "sms", classificationSource = prediction.source,
                confidence = prediction.score, reviewState = prediction.review)
            // Fee is a separate expense in the same atomic transaction. It never creates a second receipt.
            val rows = if (payment.fee > 0) listOf(row, Transaction(amountMinor = payment.fee, income = false,
                category = "Other", description = "M-PESA transaction fee", timestamp = payment.timestamp,
                mpesaCode = payment.code + ":FEE", source = "sms")) else listOf(row)
            dao.commitRows(rows, payment.code, !historical, System.currentTimeMillis(), checkBudgets = !historical)
        }
        if (!historical) afterCommit()
        inserted
    }
    suspend fun erase() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Disable first: queued workers and live receivers cannot repopulate a wiped ledger.
            check(settings.edit().putBoolean("messages", false).putLong("generation", settings.getLong("generation", 0) + 1).commit())
            dao.erase()
            check(context.getSharedPreferences("profile", 0).edit().clear().commit())
            android.app.NotificationManager::class.java.let { context.getSystemService(it).cancelAll() }
        }
        // Stale imports exit using the generation check. Avoid cancelling freshly enqueued new work.
    }
}
