package com.smartpesa.student.data

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartpesa.student.ui.SmartPesaApp
import com.smartpesa.student.ui.theme.SmartPesaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Fixtures use an in-memory ledger; connected tests must also use the disposable app ID. */
@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: FinanceDatabase
    private lateinit var model: FinanceViewModel

    @Before fun prepareLedger() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        app.getSharedPreferences("profile", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, FinanceDatabase::class.java).build()
        model = FinanceViewModel(app, db.financeDao(), background = false)
    }
    @After fun closeLedger() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.viewModelScope.cancel() }
        db.close()
    }

    @Test fun editKeepsCategoryAndHistoryFilterThenDeleteUpdatesLedger() {
        runBlocking { db.financeDao().insert(Transaction(amountMinor = 25000, income = false, category = "Transport", description = "Test bus fare")) }
        compose.setContent { SmartPesaTheme { SmartPesaApp(model) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Test bus fare").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View all").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("All Transactions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Test bus")
        compose.onNodeWithText("Test bus fare").performScrollTo().performClick()
        compose.onNodeWithText("Edit Transaction").performClick()
        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Save Changes").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("All Transactions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Test bus").assertExists()
        val saved = runBlocking { db.financeDao().observeTransactions().first().single() }
        assertEquals("Transport", saved.category)
        assertEquals(25000L, saved.amountMinor)
        compose.onNodeWithText("Test bus fare").performScrollTo().performClick()
        compose.onNodeWithText("Delete Transaction").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("No transactions match. Try another search or filter.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { db.financeDao().observeTransactions().first().isEmpty() })
    }

    @Test fun budgetCreateAndRemovalKeepTransactions() {
        runBlocking { db.financeDao().insert(Transaction(amountMinor = 25000, income = false, category = "Food & Dining", description = "Lunch")) }
        compose.setContent { SmartPesaTheme { SmartPesaApp(model) } }
        compose.onNodeWithText("Budget").performClick()
        compose.onNodeWithText("Set budget").performClick()
        compose.onNodeWithText("Limit (KSh)").performTextInput("1000")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { runBlocking { db.financeDao().allBudgets().size == 1 } }
        compose.onNodeWithText("Remove limit").performScrollTo().performClick()
        compose.onNodeWithText("Remove", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { runBlocking { db.financeDao().allBudgets().isEmpty() } }
        assertEquals(25000L, runBlocking { db.financeDao().allTransactions().single().amountMinor })
    }
    @Test fun formRestorationKeepsDraftAndCreatesOneRecord() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SmartPesaTheme { SmartPesaApp(model) } }
        compose.onNodeWithContentDescription("Add transaction").performClick()
        compose.onNodeWithText("Amount").performTextInput("12.50")
        compose.onNodeWithText("Description (optional)").performScrollTo().performTextInput("Restored draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("12.50").assertExists()
        compose.onNodeWithText("Restored draft").assertExists()
        compose.onNodeWithText("Save Transaction").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { db.financeDao().allTransactions().size == 1 } }
        assertEquals(1250L, runBlocking { db.financeDao().allTransactions().single().amountMinor })
    }
    @Test fun correctionUsesStudentLanguageAndPersistsMerchantRule() {
        runBlocking { db.financeDao().insert(Transaction(amountMinor = 25000, income = false, category = "Other", description = "Unknown Cafe",
            merchant = "Unknown Cafe", source = "sms", mpesaCode = "UNK1234567", reviewState = "required")) }
        compose.setContent { SmartPesaTheme { SmartPesaApp(model) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Unknown Cafe").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unknown Cafe").performScrollTo().performClick()
        compose.onNodeWithText("Edit Transaction").performClick()
        compose.onNodeWithText("Food & Dining").performScrollTo().performClick()
        compose.onNodeWithText("Save Changes").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { db.financeDao().allTransactions().single().reviewState == "confirmed" } }
        assertEquals("Food & Dining", runBlocking { db.financeDao().rule("unknown cafe")!!.category })
        compose.onAllNodesWithText("Naive Bayes", substring = true).assertCountEquals(0)
    }
    @Test fun largeTextEntryRemainsScrollableAndUsable() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                SmartPesaTheme { SmartPesaApp(model) }
            }
        }
        compose.onNodeWithContentDescription("Add transaction").performClick()
        compose.onNodeWithText("Amount").performTextInput("25.00")
        compose.onNodeWithText("Transport").performScrollTo().performClick()
        compose.onNodeWithText("Save Transaction").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { db.financeDao().allTransactions().size == 1 } }
        assertEquals("Transport", runBlocking { db.financeDao().allTransactions().single().category })
    }
    @Test fun profileNameAndExportReadPersistedValues() {
        compose.setContent { SmartPesaTheme { SmartPesaApp(model) } }
        compose.onNodeWithText("Profile").performClick()
        compose.onNodeWithText("Personal Info").performScrollTo().performClick()
        compose.onNodeWithText("Your name").performTextClearance()
        compose.onNodeWithText("Your name").performTextInput("Test student")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        assertEquals("Test student", model.initialName)
        runBlocking {
            db.financeDao().insert(Transaction(amountMinor = 1250, income = false, category = "Other", description = "Export test", timestamp = 0))
            val writer = java.io.StringWriter(); model.export(writer)
            assertTrue(writer.toString().contains("Export test")); assertTrue(writer.toString().contains("12.50"))
        }
    }
    @Test fun repeatedSubmitCannotInsertTwiceBeforeResultIsConsumed() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.add(10000, false, "Other", "Test expense", 1234, "same-request")
            model.add(10000, false, "Other", "Test expense", 1234, "same-request")
        }
        model.ledgerOperation.first { it?.succeeded == true }
        assertEquals(1, db.financeDao().observeTransactions().first().size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.add(10000, false, "Other", "Test expense", 1234, "same-request")
        }
        assertEquals(1, db.financeDao().observeTransactions().first().size)
        assertEquals("same-request", model.ledgerOperation.value?.key)
    }
}
