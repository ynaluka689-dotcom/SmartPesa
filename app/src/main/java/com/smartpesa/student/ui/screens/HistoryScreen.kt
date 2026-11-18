package com.smartpesa.student.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import com.smartpesa.student.ui.theme.*
import androidx.compose.ui.unit.dp
import com.smartpesa.student.data.Transaction
import com.smartpesa.student.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(transactions: List<Transaction>, onTransaction: (Transaction) -> Unit, onBack: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val rows = transactions.filter {
        (filter == "All" || filter == "Income" && it.income || filter == "Expense" && !it.income || it.category == filter) &&
        (it.description.contains(search, true) || it.category.contains(search, true))
    }
    Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackTitle("All Transactions", onBack)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search transactions…") }, leadingIcon = { Icon(AppIcons.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All", "Income", "Expense") + categories.filter { it.name != "Income" }.map { it.name }).forEach { name ->
                FilterChip(filter == name, { filter = name }, label = { Text(name) }, border = null,
                    colors = FilterChipDefaults.filterChipColors(containerColor = CardColor,
                        selectedContainerColor = Blue, selectedLabelColor = Color.White, labelColor = Muted))
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rows.isEmpty()) item { EmptyLedger("No transactions match. Try another search or filter.") }
            rows.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.of("Africa/Nairobi")).toLocalDate() }.forEach { (date, group) ->
                item { Caption(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")).uppercase()) }
                items(group, key = { it.id }) { tx -> TransactionRow(tx) { focus.clearFocus(); keyboard?.hide(); onTransaction(tx) } }
            }
        }
    }
}
