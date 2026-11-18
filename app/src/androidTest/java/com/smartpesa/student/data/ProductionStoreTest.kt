package com.smartpesa.student.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Exercise the actual application factory/callback, not only an injected in-memory DAO. */
@RunWith(AndroidJUnit4::class)
class ProductionStoreTest {
    @Test fun productionFactoryOpensEncryptedStoreWithSecureDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = FinanceDatabase.get(context)
        try {
            val dao = db.financeDao()
            dao.commitRows(listOf(Transaction(amountMinor = 1234, income = false, category = "Other", description = "Factory fixture", entryKey = "factory-test")), null, false, System.currentTimeMillis())
            assertEquals(1234L, dao.byEntryKey("factory-test")!!.amountMinor)
            db.openHelper.writableDatabase.query("PRAGMA secure_delete").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            val header = context.getDatabasePath("smartpesa_secure.db").inputStream().use { stream -> ByteArray(16).also { assertEquals(16, stream.read(it)) } }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
        } finally {
            db.close(); context.deleteDatabase("smartpesa_secure.db")
        }
    }
}
