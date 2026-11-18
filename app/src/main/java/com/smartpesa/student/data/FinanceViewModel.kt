package com.smartpesa.student.data

import android.app.Application
import com.smartpesa.student.services.LedgerService
import com.smartpesa.student.services.MessageImport
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Room flows synchronize the dashboard, charts and budgets after a save. */
class FinanceViewModel @JvmOverloads constructor(application: Application,
    private val dao: FinanceDao = FinanceDatabase.get(application).financeDao(),
    background: Boolean = true) : AndroidViewModel(application) {
    private val service = LedgerService(application, dao, background)
    private val storageFailure = MutableStateFlow(false)
    val storageError = storageFailure.asStateFlow()
    val transactions = dao.observeTransactions().catch { storageFailure.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgets = dao.observeBudgets().catch { storageFailure.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val preferences = application.getSharedPreferences("profile", 0)
    val initialName: String get() = preferences.getString("name", "Student") ?: "Student"
    suspend fun export(out: java.io.Writer) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartpesa.student.domain.Reporting.csv(dao.allTransactions(), out) }
    fun saveName(name: String) { preferences.edit().putString("name", name).apply() }
    // A ViewModel operation key reconnects the current screen to a save after rotation.
    private val operation = MutableStateFlow<LedgerOperation?>(null)
    val ledgerOperation = operation.asStateFlow()
    fun clearOperation(key: String) { if (operation.value?.key == key && operation.value?.busy == false) operation.value = null }
    private fun write(key: String, kind: String, id: Long?, action: suspend () -> Unit) {
        // Reject a second submission until the first result has been consumed by the UI.
        if (operation.value != null) return
        operation.value = LedgerOperation(key, kind, id, busy = true)
        viewModelScope.launch {
            try { action(); operation.value = LedgerOperation(key, kind, id, succeeded = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.value = LedgerOperation(key, kind, id, failed = true) }
        }
    }
    /** Reconcile a restored draft with a save that committed just before Android killed the process. */
    fun recoverEntry(key: String) {
        viewModelScope.launch {
            try {
                val saved = dao.byEntryKey(key)
                if (saved != null && operation.value == null) operation.value = LedgerOperation(key, "save", saved.id, succeeded = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageFailure.value = true }
        }
    }
    fun add(amount: Long, income: Boolean, category: String, description: String, timestamp: Long, key: String) {
        write(key, "save", null) {
            service.add(Transaction(amountMinor = amount, income = income, category = category,
                description = description.trim(), timestamp = timestamp, entryKey = key))
        }
    }
    /** Update the existing ID rather than insert a second expense; imported metadata is retained. */
    fun edit(transaction: Transaction, key: String) {
        write(key, "save", transaction.id) { service.edit(transaction) }
    }
    fun delete(transaction: Transaction, key: String) {
        write(key, "delete", transaction.id) { service.delete(transaction) }
    }
    private val utility = MutableStateFlow<UtilityOperation?>(null)
    val utilityOperation = utility.asStateFlow()
    fun clearUtility(key: String) { if (utility.value?.key == key && utility.value?.busy == false) utility.value = null }
    private fun utilityWrite(key: String, kind: String, action: suspend () -> Int?) {
        if (utility.value != null) return
        utility.value = UtilityOperation(key, kind, busy = true)
        viewModelScope.launch {
            try { val count = action(); utility.value = UtilityOperation(key, kind, succeeded = true, count = count) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { utility.value = UtilityOperation(key, kind, failed = true) }
        }
    }
    fun saveBudget(category: String, limit: Long, key: String) = utilityWrite(key, "budget") { service.budget(category, limit); null }
    fun removeBudget(category: String, key: String) = utilityWrite(key, "removeBudget") { service.removeBudget(category); null }
    fun erase(key: String) = utilityWrite(key, "erase") { service.erase(); null }
    fun setMessageAccess(enabled: Boolean, key: String) = utilityWrite(key, "access") { service.setMessageAccess(enabled); null }
    fun importMessages(key: String) {
        val generation = getApplication<Application>().getSharedPreferences("settings", 0).getLong("generation", 0)
        utilityWrite(key, "import") { MessageImport.run(getApplication(), generation) }
    }

}

/** Internal lifecycle state; none of these identifiers appear in product text. */
data class LedgerOperation(val key: String, val kind: String, val transactionId: Long?,
    val busy: Boolean = false, val succeeded: Boolean = false, val failed: Boolean = false)

/** Long-running settings actions reconnect to the same result after rotation. */
data class UtilityOperation(val key: String, val kind: String, val busy: Boolean = false, val succeeded: Boolean = false, val failed: Boolean = false, val count: Int? = null)
