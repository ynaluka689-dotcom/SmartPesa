package com.smartpesa.student.ui

import com.smartpesa.student.ui.components.HideDialogNavigationKeys
import com.smartpesa.student.ui.components.AppIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartpesa.student.data.FinanceViewModel
import com.smartpesa.student.ui.screens.*
import com.smartpesa.student.ui.theme.*

/** Four primary destinations match the reference; entry and history are secondary screens. */
@Composable
fun SmartPesaApp(model: FinanceViewModel = viewModel(), requestedDestination: String? = null, onDestinationHandled: () -> Unit = {}) {
    val storageError by model.storageError.collectAsStateWithLifecycle()
    val transactions by model.transactions.collectAsStateWithLifecycle()
    val budgets by model.budgets.collectAsStateWithLifecycle()
    val utility by model.utilityOperation.collectAsStateWithLifecycle()
    val operation by model.ledgerOperation.collectAsStateWithLifecycle()
    val screenState = rememberSaveableStateHolder()
    var screen by rememberSaveable { mutableStateOf("Home") }
    var tab by rememberSaveable { mutableStateOf("Home") }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editReturn by rememberSaveable { mutableStateOf("Home") }
    LaunchedEffect(requestedDestination, operation, utility) {
        if (requestedDestination in listOf("Budget", "History") && operation == null && utility == null) {
            selectedId = null; editingId = null; screen = requestedDestination!!
            if (screen == "Budget") tab = "Budget"
            onDestinationHandled()
        }
    }
    fun goBack() {
        screen = when (screen) { "Edit" -> editReturn; "Add", "History" -> tab; else -> "Home" }
        if (screen in listOf("Home", "Stats", "Budget", "Profile")) tab = screen
    }
    LaunchedEffect(operation) {
        if (operation?.kind == "delete" && operation?.succeeded == true) {
            if (selectedId == operation?.transactionId) selectedId = null
            operation?.key?.let { model.clearOperation(it) }
        }
    }
    BackHandler(enabled = screen != "Home" && selectedId == null) { if (operation?.busy != true && utility == null) goBack() }
    val secondary = screen == "Add" || screen == "History" || screen == "Edit"
    Scaffold(containerColor = CanvasColor, bottomBar = {
        if (!secondary) NavigationBar(containerColor = CardColor, tonalElevation = 0.dp) {
            listOf("Home" to AppIcons.Home, "Stats" to AppIcons.BarChart,
                "Budget" to AppIcons.AccountBalanceWallet, "Profile" to AppIcons.Person).forEach { (name, icon) ->
                NavigationBarItem(selected = tab == name, onClick = { if (utility == null) { tab = name; screen = name } }, enabled = utility == null,
                    icon = { Icon(icon, name) }, label = { Text(name) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue,
                        indicatorColor = Color.Transparent, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                "Home" -> DashboardScreen(transactions, onAdd = { screen = "Add" },
                    onHistory = { screen = "History" }, onStats = { tab = "Stats"; screen = "Stats" },
                    onTransaction = { selectedId = it.id })
                "Add" -> AddScreen(model) { goBack() }
                "Edit" -> {
                    val original = transactions.firstOrNull { it.id == editingId }
                    if (original != null) key(original.id) { AddScreen(model, original) { goBack() } }
                    else Column(Modifier.padding(20.dp)) { BackTitle("Transaction unavailable") { goBack() } }
                }
                "History" -> screenState.SaveableStateProvider("History") {
                    HistoryScreen(transactions, onTransaction = { selectedId = it.id }) { goBack() }
                }
                "Stats" -> StatisticsScreen(transactions)
                "Budget" -> screenState.SaveableStateProvider("Budget") { BudgetScreen(transactions, budgets, model) }
                "Profile" -> screenState.SaveableStateProvider("Profile") { ProfileScreen(model, transactions) }
            }
        }
    }
    if (storageError) AlertDialog(onDismissRequest = {}, title = { HideDialogNavigationKeys(); Text("Unable to open your saved ledger") },
        text = { Text("Your data has not been reset. Close and reopen the app. If this continues, keep this installation and ask for help before uninstalling it.") },
        confirmButton = {})
    transactions.firstOrNull { it.id == selectedId }?.let { transaction ->
        key(transaction.id) {
            TransactionDetails(transaction, model, onDismiss = { selectedId = null }, onEdit = {
                editingId = transaction.id; selectedId = null; editReturn = screen; screen = "Edit"
            })
        }
    }
}
