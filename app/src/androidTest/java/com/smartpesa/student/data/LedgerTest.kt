package com.smartpesa.student.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** These tests use isolated databases: no sample rows are written into smartpesa.db. */
@RunWith(AndroidJUnit4::class)
class LedgerTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        FinanceDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())

    @org.junit.After fun removeTestFile() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("ledger-migration-test")
    }

    @Test fun upgradeKeepsExistingTransactionsAndBudgets() {
        migration.createDatabase("ledger-migration-test", 1).apply {
            execSQL("INSERT INTO transactions (id,amountMinor,income,category,description,timestamp) VALUES (7,25000,0,'Transport','Bus fare',1234)")
            execSQL("INSERT INTO budgets (category,limitMinor) VALUES ('Transport',100000)")
            close()
        }
        migration.runMigrationsAndValidate("ledger-migration-test", 2, true, FinanceDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT id,amountMinor,timestamp,source,reviewState,mpesaCode FROM transactions").use {
                assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0)); assertEquals(25000L, it.getLong(1))
                assertEquals(1234L, it.getLong(2)); assertEquals("manual", it.getString(3))
                assertEquals("confirmed", it.getString(4)); assertTrue(it.isNull(5))
            }
            db.query("SELECT limitMinor FROM budgets WHERE category='Transport'").use { assertTrue(it.moveToFirst()); assertEquals(100000L, it.getLong(0)) }
        }
    }

    @Test fun editsAndDeletionUpdateObservedLedgerWithoutDuplicatingRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java).build()
        try {
            val dao = db.financeDao()
            dao.insert(Transaction(amountMinor = 100000, income = true, category = "Income", description = "Allowance"))
            dao.insert(Transaction(amountMinor = 20000, income = false, category = "Transport", description = "Bus", mpesaCode = "DEF456", source = "sms", merchant = "Bus company", confidence = 0.85, classificationSource = "rule"))
            val expense = dao.observeTransactions().first().first { !it.income }
            assertEquals(1, dao.update(expense.copy(amountMinor = 35000, category = "Food & Dining")))
            val updated = dao.observeTransactions().first()
            assertEquals(2, updated.size)
            val revised = updated.first { !it.income }
            assertEquals(expense.id, revised.id); assertEquals(expense.timestamp, revised.timestamp)
            assertEquals("DEF456", revised.mpesaCode); assertEquals("sms", revised.source)
            assertEquals("Bus company", revised.merchant); assertEquals(expense.confidence, revised.confidence)
            assertEquals(expense.classificationSource, revised.classificationSource)
            assertEquals(65000L, updated.sumOf { if (it.income) it.amountMinor else -it.amountMinor })
            assertEquals(35000L, updated.filter { it.category == "Food & Dining" }.sumOf { it.amountMinor })
            assertEquals(0L, updated.filter { it.category == "Transport" }.sumOf { it.amountMinor })
            assertEquals(1, dao.delete(updated.first { !it.income }))
            assertEquals(1, dao.observeTransactions().first().size)
        } finally { db.close() }
    }

    @Test fun messageCodesAreUniqueWhileManualEntriesRemainIndependent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FinanceDatabase::class.java).build()
        try {
            val dao = db.financeDao()
            val row = Transaction(amountMinor = 10000, income = false, category = "Other", description = "Expense")
            dao.insert(row); dao.insert(row)
            dao.insert(row.copy(mpesaCode = "ABC123", source = "sms"))
            try { dao.insert(row.copy(mpesaCode = "ABC123", source = "sms")); fail("Duplicate code was accepted") }
            catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(3, dao.observeTransactions().first().size)
        } finally { db.close() }
    }
}
