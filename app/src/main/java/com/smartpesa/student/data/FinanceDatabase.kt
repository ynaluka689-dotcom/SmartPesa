package com.smartpesa.student.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import com.smartpesa.student.domain.Reporting
import com.smartpesa.student.domain.normalize
import com.smartpesa.student.domain.expenseCategories

/** Store cents (KSh 10.50 = 1050), never floating-point financial totals. */
@Entity(tableName = "transactions", indices = [Index(value = ["mpesaCode"], unique = true), Index(value = ["entryKey"], unique = true)])
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long, val income: Boolean, val category: String,
    val description: String, val timestamp: Long = System.currentTimeMillis(),
    // Import metadata supports review and deduplication; technical scores never appear in the UI.
    val mpesaCode: String? = null,
    val entryKey: String? = null,
    val merchant: String? = null,
    @ColumnInfo(defaultValue = "'manual'") val source: String = "manual",
    val classificationSource: String? = null,
    val confidence: Double? = null,
    @ColumnInfo(defaultValue = "'confirmed'") val reviewState: String = "confirmed",
)

/** Each category has one monthly limit; replace it when the student edits it. */
@Entity(tableName = "budgets")
data class Budget(@PrimaryKey val category: String, val limitMinor: Long)

/** Keeping seen message codes after a ledger deletion prevents inbox replay from restoring deleted rows. */
@Entity(tableName = "seen_messages")
data class SeenMessage(@PrimaryKey val code: String)
@Entity(tableName = "merchant_rules")
data class MerchantRule(@PrimaryKey val merchant: String, val category: String)
/** An event key makes retrying a delivery update the same notification rather than create another one. */
@Entity(tableName = "alerts", indices = [Index(value = ["eventKey"], unique = true)])
data class AlertEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val eventKey: String,
    val title: String, val body: String, val destination: String, val delivered: Boolean = false)

/** Parsed live messages are staged in the encrypted ledger, never WorkManager's plaintext payload. */
@Entity(tableName = "pending_payments")
data class PendingPayment(@PrimaryKey val code: String, val amount: Long, val income: Boolean,
    val merchant: String, val timestamp: Long, val fee: Long, val generation: Long)

@Dao
abstract class FinanceDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    abstract fun observeTransactions(): Flow<List<Transaction>>
    @Query("SELECT * FROM budgets ORDER BY category")
    abstract fun observeBudgets(): Flow<List<Budget>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun stagePayment(payment: PendingPayment): Long
    @Query("SELECT * FROM pending_payments WHERE generation=:generation") abstract suspend fun pendingPayments(generation: Long): List<PendingPayment>
    @Query("DELETE FROM pending_payments WHERE code=:code") abstract suspend fun removePending(code: String)
    @Query("DELETE FROM pending_payments") abstract suspend fun clearPending()
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    abstract suspend fun allTransactions(): List<Transaction>
    @Query("SELECT * FROM budgets") abstract suspend fun allBudgets(): List<Budget>
    @Query("SELECT * FROM merchant_rules WHERE merchant=:merchant") abstract suspend fun rule(merchant: String): MerchantRule?
    @Query("SELECT * FROM transactions WHERE entryKey=:key LIMIT 1") abstract suspend fun byEntryKey(key: String): Transaction?
    @Query("SELECT * FROM transactions WHERE mpesaCode=:code LIMIT 1") abstract suspend fun byCode(code: String): Transaction?
    @Query("DELETE FROM transactions WHERE mpesaCode=:code") abstract suspend fun deleteCode(code: String)
    @Query("UPDATE transactions SET timestamp=:timestamp WHERE mpesaCode=:code") abstract suspend fun changeFeeDate(code: String, timestamp: Long)
    @Insert abstract suspend fun insert(transaction: Transaction): Long
    @Update abstract suspend fun update(transaction: Transaction): Int
    @Delete abstract suspend fun delete(transaction: Transaction): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun saveBudget(budget: Budget)
    @Query("DELETE FROM budgets WHERE category=:category") abstract suspend fun removeBudget(category: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun rememberCode(row: SeenMessage): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun saveRule(row: MerchantRule)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun enqueue(event: AlertEvent): Long
    @Query("SELECT * FROM alerts WHERE delivered=0 ORDER BY id") abstract suspend fun pendingAlerts(): List<AlertEvent>
    @Query("UPDATE alerts SET delivered=1 WHERE id=:id") abstract suspend fun delivered(id: Long)
    @Query("UPDATE alerts SET delivered=1 WHERE eventKey=:key") abstract suspend fun consumeEvent(key: String)
    @Query("DELETE FROM transactions") abstract suspend fun clearTransactions()
    @Query("DELETE FROM budgets") abstract suspend fun clearBudgets()
    @Query("DELETE FROM merchant_rules") abstract suspend fun clearRules()
    @Query("DELETE FROM alerts") abstract suspend fun clearAlerts()
    @Query("DELETE FROM seen_messages") abstract suspend fun clearSeen()

    /** Save and the resulting notification decisions commit or roll back together. */
    @androidx.room.Transaction
    open suspend fun commitRows(rows: List<Transaction>, code: String?, receipt: Boolean, now: Long, checkBudgets: Boolean = true): Boolean {
        if (rows.firstOrNull()?.entryKey?.let { byEntryKey(it) } != null) return false
        if (code != null && rememberCode(SeenMessage(code)) == -1L) {
            // Automatic catch-up may win a race with the live receiver. It must not lose the live receipt.
            if (receipt) { byCode(code)?.let { enqueueReceipt(it.id) }; removePending(code) }
            return false
        }
        rows.forEach { row ->
            require(row.amountMinor > 0)
            val id = insert(row)
            if (receipt && !row.mpesaCode.orEmpty().endsWith(":FEE")) enqueueReceipt(id)
        }
        if (receipt && code != null) removePending(code)
        validateTotals()
        if (checkBudgets) decideBudgets(now)
        return true
    }
    private suspend fun enqueueReceipt(id: Long) { enqueue(AlertEvent(eventKey = "receipt:$id", title = "Transaction added", body = "Your transaction is ready to view.", destination = "History")) }
    @androidx.room.Transaction
    open suspend fun editRow(row: Transaction, now: Long) {
        require(row.amountMinor > 0)
        check(update(row) == 1)
        row.mpesaCode?.takeUnless { it.endsWith(":FEE") }?.let { changeFeeDate(it + ":FEE", row.timestamp) }
        validateTotals()
        row.merchant?.takeIf { !row.income && row.reviewState == "confirmed" }?.let {
            require(row.category in expenseCategories)
            saveRule(MerchantRule(normalize(it), row.category))
        }
        decideBudgets(now)
    }
    @androidx.room.Transaction open suspend fun deleteRow(row: Transaction, now: Long) {
        check(delete(row) == 1)
        row.mpesaCode?.takeUnless { it.endsWith(":FEE") }?.let { deleteCode(it + ":FEE") }
        decideBudgets(now)
    }
    @androidx.room.Transaction open suspend fun changeBudget(row: Budget, now: Long) {
        require(row.limitMinor > 0 && row.category in expenseCategories)
        saveBudget(row)
        allBudgets().fold(0L) { sum, budget -> Math.addExact(sum, budget.limitMinor) }
        decideBudgets(now)
    }
    /** Historical imports do not flood receipts; only current-month limits are checked. */
    private suspend fun validateTotals() {
        val rows = allTransactions(); Reporting.total(rows.filter { it.income }); Reporting.total(rows.filter { !it.income })
    }
    @androidx.room.Transaction
    open suspend fun decideBudgets(now: Long) {
        val month = Reporting.month(now)
        val current = allTransactions().filter { !it.income && Reporting.month(it.timestamp) == month }
        allBudgets().forEach { budget ->
            val spent = Reporting.total(current.filter { it.category == budget.category })
            val crossed = Reporting.crossed(spent, budget.limitMinor)
            if (crossed > 0) {
                if (crossed == 100) { consumeEvent("budget:$month:${budget.category}:80"); enqueue(AlertEvent(eventKey = "budget:$month:${budget.category}:80", title = "", body = "", destination = "Budget", delivered = true)) }
                enqueue(AlertEvent(eventKey = "budget:$month:${budget.category}:$crossed", title = if (crossed == 100) "Monthly limit reached" else "Close to your monthly limit",
                    body = "Check your ${budget.category.lowercase()} spending.", destination = "Budget"))
            }
        }
    }
    @androidx.room.Transaction open suspend fun erase() { clearTransactions(); clearBudgets(); clearRules(); clearAlerts(); clearSeen(); clearPending() }
}

/** A fresh database is empty. Demo data never enters a student's ledger. */
@Database(entities = [Transaction::class, Budget::class, SeenMessage::class, MerchantRule::class, AlertEvent::class, PendingPayment::class], version = 3, exportSchema = true)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao
    companion object {
        /** Nullable unique codes allow multiple manual entries while preventing repeated SMS imports. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN mpesaCode TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN merchant TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN classificationSource TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN confidence REAL")
                db.execSQL("ALTER TABLE transactions ADD COLUMN reviewState TEXT NOT NULL DEFAULT 'confirmed'")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_mpesaCode ON transactions(mpesaCode)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN entryKey TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_entryKey ON transactions(entryKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_payments (code TEXT NOT NULL PRIMARY KEY, amount INTEGER NOT NULL, income INTEGER NOT NULL, merchant TEXT NOT NULL, timestamp INTEGER NOT NULL, fee INTEGER NOT NULL, generation INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS seen_messages (code TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT OR IGNORE INTO seen_messages SELECT mpesaCode FROM transactions WHERE mpesaCode IS NOT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS merchant_rules (merchant TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS alerts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventKey TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, destination TEXT NOT NULL, delivered INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_alerts_eventKey ON alerts(eventKey)")
            }
        }
        @Volatile private var instance: FinanceDatabase? = null
        fun get(context: Context): FinanceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinanceDatabase::class.java,
                "smartpesa_secure.db").openHelperFactory(EncryptedStore.factory(context.applicationContext))
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("PRAGMA secure_delete=ON").use { check(it.moveToFirst() && it.getInt(0) == 1) }
                    }
                }).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
