package com.smartpesa.student.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartpesa.student.domain.*
import com.smartpesa.student.services.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        FinanceDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    private val now = Instant.parse("2026-10-05T06:30:00Z").toEpochMilli()
    private fun row(amount: Long, code: String? = null) = Transaction(amountMinor = amount, income = false, category = "Transport", description = "Bus", timestamp = now, mpesaCode = code)
    private suspend fun memory(block: suspend (FinanceDao) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java).build()
        try { block(db.financeDao()) } finally { db.close() }
    }
    @After fun cleanup() {
        context.deleteDatabase("smartpesa.db"); context.deleteDatabase("smartpesa_secure.db")
        File(context.getDatabasePath("smartpesa_secure.db").path + ".migrating").delete()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
    }
    @Test fun concurrentReplayCommitsOnceAndDeletedPaymentStaysDeleted() = runBlocking { memory { dao ->
        val results = coroutineScope { List(12) { async(Dispatchers.IO) { dao.commitRows(listOf(row(10000, "ABC1234567")), "ABC1234567", true, now) } }.awaitAll() }
        assertEquals(1, results.count { it }); assertEquals(1, dao.allTransactions().size)
        assertEquals(1, dao.pendingAlerts().size)
        dao.deleteRow(dao.allTransactions().single(), now)
        assertFalse(dao.commitRows(listOf(row(10000, "ABC1234567")), "ABC1234567", true, now))
        assertTrue(dao.allTransactions().isEmpty())
    } }
    @Test fun failedCommitRollsBackRowsCodesAndNotifications() = runBlocking { memory { dao ->
        try { dao.commitRows(listOf(row(10000), row(-1)), "BAD1234567", true, now); fail("Invalid row committed") }
        catch (_: IllegalArgumentException) { }
        assertTrue(dao.allTransactions().isEmpty()); assertTrue(dao.pendingAlerts().isEmpty())
        assertTrue(dao.commitRows(listOf(row(10000)), "BAD1234567", true, now))
    } }
    @Test fun thresholdsConsumePendingEightyAndResetInANewMonth() = runBlocking { memory { dao ->
        dao.changeBudget(Budget("Transport", 10000), now)
        dao.commitRows(listOf(row(8000)), null, false, now)
        assertTrue(dao.pendingAlerts().single().eventKey.endsWith(":80"))
        dao.commitRows(listOf(row(2000)), null, false, now)
        assertEquals(1, dao.pendingAlerts().size); assertTrue(dao.pendingAlerts().single().eventKey.endsWith(":100"))
        dao.decideBudgets(now); assertEquals(1, dao.pendingAlerts().size)
        val november = Instant.parse("2026-11-05T06:30:00Z").toEpochMilli()
        dao.commitRows(listOf(row(12000).copy(timestamp = november)), null, false, november)
        assertEquals(2, dao.pendingAlerts().size)
    } }
    @Test fun correctionsFeeDeletionAndManualRequestReplayAreDurable() = runBlocking { memory { dao ->
        dao.commitRows(listOf(row(10000, "ABC1234567").copy(merchant = "Test Bus", source = "sms"), row(100, "ABC1234567:FEE")), "ABC1234567", false, now)
        val parent = dao.byCode("ABC1234567")!!
        dao.editRow(parent.copy(category = "Food & Dining", reviewState = "confirmed"), now)
        assertEquals("Food & Dining", dao.rule("test bus")!!.category)
        dao.deleteRow(dao.byCode("ABC1234567")!!, now); assertTrue(dao.allTransactions().isEmpty())
        val manual = row(5000).copy(entryKey = "survives-process-death")
        assertTrue(dao.commitRows(listOf(manual), null, true, now))
        assertFalse(dao.commitRows(listOf(manual), null, true, now)); assertEquals(1, dao.allTransactions().size)
    } }
    @Test fun catchupWinningRaceStillAllowsOneLiveReceipt() = runBlocking { memory { dao ->
        dao.commitRows(listOf(row(10000, "ABC1234567")), "ABC1234567", false, now, false)
        assertTrue(dao.pendingAlerts().isEmpty())
        repeat(3) { assertFalse(dao.commitRows(listOf(row(10000, "ABC1234567")), "ABC1234567", true, now)) }
        assertEquals(1, dao.pendingAlerts().size)
    } }
    @Test fun oldImportGenerationCannotRestoreErasedDataAfterReenable() = runBlocking { memory { dao ->
        val service = LedgerService(context, dao, background = false)
        service.setMessageAccess(true)
        val oldGeneration = context.getSharedPreferences("settings", 0).getLong("generation", 0)
        val payment = Payment("ABC1234567", 10000, false, "KFC", now)
        assertTrue(service.ingest(payment, true, oldGeneration))
        service.erase(); service.setMessageAccess(true)
        assertFalse(service.ingest(payment, true, oldGeneration)); assertTrue(dao.allTransactions().isEmpty())
        assertTrue(service.ingest(payment, true))
    } }
    @Test fun encryptedConversionAndReopenPreserveLegacyValues() = runBlocking {
        migration.createDatabase("smartpesa.db", 2).apply {
            execSQL("INSERT INTO transactions (id,amountMinor,income,category,description,timestamp,mpesaCode,merchant,source,classificationSource,confidence,reviewState) VALUES (7,25000,0,'Transport','Bus fare',1234,'ABC1234567','Bus','sms','rule',0.85,'suggested')")
            execSQL("INSERT INTO budgets VALUES ('Transport',100000)"); close()
        }
        fun open(): FinanceDatabase = Room.databaseBuilder(context, FinanceDatabase::class.java, "smartpesa_secure.db")
            .openHelperFactory(EncryptedStore.factory(context)).addMigrations(FinanceDatabase.MIGRATION_1_2, FinanceDatabase.MIGRATION_2_3).build()
        File(context.getDatabasePath("smartpesa_secure.db").path + ".migrating").writeText("incomplete interrupted export")
        val db = open()
        try {
            val saved = db.financeDao().allTransactions().single()
            assertEquals(7L, saved.id); assertEquals(25000L, saved.amountMinor); assertEquals("Bus fare", saved.description)
            assertEquals("ABC1234567", saved.mpesaCode); assertEquals(0.85, saved.confidence!!, 0.0)
            assertEquals(100000L, db.financeDao().allBudgets().single().limitMinor)
            assertFalse(db.financeDao().commitRows(listOf(row(10, "ABC1234567")), "ABC1234567", false, now))
        } finally { db.close() }
        assertFalse(context.getDatabasePath("smartpesa.db").exists())
        assertFalse(File(context.getDatabasePath("smartpesa_secure.db").path + ".migrating").exists())
        val header = context.getDatabasePath("smartpesa_secure.db").inputStream().use { stream -> ByteArray(16).also { assertEquals(16, stream.read(it)) } }
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
        val keyPreferences = context.getSharedPreferences("database_key", 0)
        val wrapped = keyPreferences.getString("wrapped", null); val iv = keyPreferences.getString("iv", null)
        keyPreferences.edit().clear().commit()
        try {
            try { EncryptedStore.secret(context); fail("Missing protected password replaced an existing ledger") }
            catch (_: IllegalStateException) { }
            assertTrue(context.getDatabasePath("smartpesa_secure.db").exists())
        } finally { keyPreferences.edit().putString("wrapped", wrapped).putString("iv", iv).commit() }
        val reopened = open()
        try { assertEquals(25000L, reopened.financeDao().allTransactions().single().amountMinor) } finally { reopened.close() }
    }
    @Test fun eraseClearsEveryLedgerTableAndProfileAndDisablesMessageAccess() = runBlocking { memory { dao ->
        val service = LedgerService(context, dao, background = false)
        service.setMessageAccess(true)
        val generation = context.getSharedPreferences("settings", 0).getLong("generation", 0)
        dao.commitRows(listOf(row(10000, "ABC1234567")), "ABC1234567", true, now)
        dao.saveRule(MerchantRule("test", "Transport")); dao.saveBudget(Budget("Transport", 100000))
        dao.stagePayment(PendingPayment("DEF1234567", 10000, false, "Test", now, 0, generation))
        context.getSharedPreferences("profile", 0).edit().putString("name", "Test student").commit()
        service.erase()
        assertTrue(dao.allTransactions().isEmpty()); assertTrue(dao.allBudgets().isEmpty())
        assertNull(dao.rule("test")); assertTrue(dao.pendingAlerts().isEmpty()); assertTrue(dao.pendingPayments(generation).isEmpty())
        assertFalse(context.getSharedPreferences("settings", 0).getBoolean("messages", true))
        assertNull(context.getSharedPreferences("profile", 0).getString("name", null))
        service.setMessageAccess(true)
        assertTrue(service.ingest(Payment("ABC1234567", 10000, false, "KFC", now), true))
    } }
    @Test fun malformedModelFallsBackInsteadOfBreakingIngestion() {
        assertNull(ModelLoader.decodeWeights("{}"))
        val model = JSONObject(context.assets.open("category_model.json").bufferedReader().use { it.readText() })
        model.put("priors", JSONArray())
        assertNull(ModelLoader.decodeWeights(model.toString()))
        val wrongLabels = JSONObject(context.assets.open("category_model.json").bufferedReader().use { it.readText() })
        wrongLabels.put("labels", JSONArray().put("Income"))
        assertNull(ModelLoader.decodeWeights(wrongLabels.toString()))
    }
    @Test fun stagedLivePaymentRecoversAfterProcessLossWithItsReceipt() = runBlocking { memory { dao ->
        val service = LedgerService(context, dao, background = false)
        service.setMessageAccess(true)
        val generation = context.getSharedPreferences("settings", 0).getLong("generation", 0)
        dao.stagePayment(PendingPayment("ABC1234567", 25000, false, "KFC", now, 600, generation))
        // A fresh service instance represents restarting after staging but before ledger commit.
        LedgerService(context, dao, background = false).recoverLive(generation)
        assertEquals(2, dao.allTransactions().size)
        assertEquals(1, dao.pendingAlerts().size)
        assertTrue(dao.pendingPayments(generation).isEmpty())
        LedgerService(context, dao, background = false).recoverLive(generation)
        assertEquals(2, dao.allTransactions().size); assertEquals(1, dao.pendingAlerts().size)
    } }
    @Test fun migrationRecoveryRejectsEqualCountsWithDifferentValues() {
        migration.createDatabase("smartpesa.db", 2).apply {
            execSQL("INSERT INTO transactions (id,amountMinor,income,category,description,timestamp,source,reviewState) VALUES (1,10000,0,'Transport','Bus',1234,'manual','confirmed')")
            close()
        }
        System.loadLibrary("sqlcipher")
        val password = EncryptedStore.secret(context)
        val original = context.getDatabasePath("smartpesa.db")
        val target = context.getDatabasePath("smartpesa_secure.db")
        val plain = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(original.path, "", null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE or net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY, null)
        try {
            plain.execSQL("ATTACH DATABASE '${target.path}' AS encrypted KEY '${password.toString(Charsets.UTF_8)}'")
            plain.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { check(it.moveToFirst()) }
            plain.execSQL("PRAGMA encrypted.user_version=2")
            plain.execSQL("DETACH DATABASE encrypted")
        } finally { plain.close() }
        // Simulate a different same-sized encrypted file during crash recovery.
        net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(target.path, password, null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null).use {
            it.execSQL("UPDATE transactions SET amountMinor=99999 WHERE id=1")
        }
        try { EncryptedStore.migratePlaintext(context, password); fail("Unequal contents accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(original.exists())
        // Matching recovered contents permit cleanup; a leftover incomplete temporary file is harmless.
        net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(target.path, password, null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null).use {
            it.execSQL("UPDATE transactions SET amountMinor=10000 WHERE id=1")
        }
        EncryptedStore.migratePlaintext(context, password)
        assertFalse(original.exists()); assertTrue(target.exists())
    }
    @Test fun notificationsArePrivateIdempotentAndDisabledAlertsKeepTheLedger() = runBlocking { memory { dao ->
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        dao.commitRows(listOf(row(10000)), null, true, now)
        val event = dao.pendingAlerts().single()
        NotificationDelivery.dispatch(context, dao)
        assertTrue(dao.pendingAlerts().isEmpty())
        val delivered = manager.activeNotifications.single { it.id == event.id.toInt() }
        assertEquals("Your transaction is ready to view.", delivered.notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertFalse(delivered.notification.extras.toString().contains("Bus"))
        NotificationDelivery.dispatch(context, dao)
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        context.getSharedPreferences("settings", 0).edit().putBoolean("receipts", false).commit()
        dao.commitRows(listOf(row(5000)), null, true, now)
        NotificationDelivery.dispatch(context, dao)
        assertEquals(2, dao.allTransactions().size); assertTrue(dao.pendingAlerts().isEmpty())
        assertEquals(0, manager.activeNotifications.size)
        context.getSharedPreferences("settings", 0).edit().putBoolean("receipts", true).commit()
        val denied = object : android.content.ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = if (permission == android.Manifest.permission.POST_NOTIFICATIONS)
                android.content.pm.PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        dao.commitRows(listOf(row(1000)), null, true, now)
        NotificationDelivery.dispatch(denied, dao)
        assertEquals(3, dao.allTransactions().size); assertEquals(0, manager.activeNotifications.size)
        assertTrue(dao.pendingAlerts().isEmpty())
    } }
    @Test fun kotlinAndPythonFeaturesAndPredictionsAgree() {
        val model = JSONObject(context.assets.open("category_model.json").bufferedReader().use { it.readText() })
        fun strings(name: String) = model.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
        fun values(name: String) = model.getJSONArray(name).let { a -> List(a.length()) { a.getDouble(it) } }
        val matrix = model.getJSONArray("likelihoods")
        val weights = ModelWeights(strings("labels"), strings("vocabulary"), values("idf"), values("priors"),
            List(matrix.length()) { i -> matrix.getJSONArray(i).let { a -> List(a.length()) { a.getDouble(it) } } })
        val classifier = ModelLoader.load(context)
        val fixtures = JSONArray(context.assets.open("inference_fixtures.json").bufferedReader().use { it.readText() })
        repeat(fixtures.length()) { index ->
            val fixture = fixtures.getJSONObject(index)
            val actual = Categorizer.features(fixture.getString("merchant"), weights)
            val expected = fixture.getJSONArray("features")
            actual.indices.forEach { assertEquals(expected.getDouble(it), actual[it], 1e-10) }
            val prediction = classifier.predict(fixture.getString("merchant"), false)
            assertEquals(fixture.getString("prediction"), if (prediction.review == "required") "Other" else prediction.category)
            assertEquals(fixture.getDouble("score"), prediction.score, 1e-10)
        }
    }
}
