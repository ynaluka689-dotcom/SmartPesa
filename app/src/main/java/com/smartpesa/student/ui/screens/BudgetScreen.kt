package com.smartpesa.student.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartpesa.student.data.*
import com.smartpesa.student.ui.components.*
import com.smartpesa.student.ui.theme.*

@Composable
fun BudgetScreen(transactions: List<Transaction>, budgets: List<Budget>, model: FinanceViewModel) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("Food & Dining") }
    var amount by rememberSaveable { mutableStateOf("") }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val requestKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val operation by model.utilityOperation.collectAsStateWithLifecycle()
    val saving = operation?.busy == true
    LaunchedEffect(operation, requestKey) {
        if (operation?.key == requestKey && !saving) {
            if (operation?.succeeded == true) { editing = false; removing = null; amount = "" }
            else if (operation?.failed == true) error = "Could not update the limit. Please try again."
            model.clearUtility(requestKey)
        }
    }
    val totals = expenseTotals(periodTransactions(transactions, "Month")).associate { it.first.name to it.second }
    val limit = budgets.sumOf { it.limitMinor }
    // The monthly total includes all expenses, even categories with no configured limit.
    val spent = totals.values.sum()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionTitle("Budget", "Set budget") { editing = true }
        Panel {
            Caption("TOTAL MONTHLY BUDGET")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(money(limit), Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (limit > 0) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { (spent.toDouble() / limit).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.size(64.dp), color = Blue, trackColor = Blue.copy(alpha = 0.15f), strokeWidth = 6.dp)
                        Text("${(spent.toDouble() / limit * 100).toInt()}%", fontSize = 14.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Caption("SPENT"); Text(money(spent), color = Pink) }
                Column(Modifier.weight(1f)) { Caption("LEFT"); Text(money(limit - spent), color = if (spent > limit) Pink else Green) }
            }
        }
        SectionTitle("By Category", "Edit all") { editing = true }
        if (budgets.isEmpty()) EmptyLedger("Set a category budget to track your monthly limits.")
        budgets.forEach { budget ->
            val cat = categoryFor(budget.category)
            val used = totals[budget.category] ?: 0L
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryBadge(cat)
                    Column(Modifier.weight(1f)) { Text(cat.name, fontWeight = FontWeight.SemiBold); Text("${money(used)} of ${money(budget.limitMinor)}", fontSize = 11.sp, color = Muted) }
                    Text("${(used.toDouble() / budget.limitMinor * 100).toInt()}%", color = if (used > budget.limitMinor) Pink else Muted, fontSize = 12.sp)
                }
                LinearProgressIndicator(progress = { (used.toDouble() / budget.limitMinor).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(7.dp), color = if (used > budget.limitMinor) Pink else cat.color,
                    trackColor = cat.color.copy(alpha = 0.12f))
                Caption(if (used > budget.limitMinor) "Exceeded budget by ${money(used - budget.limitMinor)}"
                    else "${money(budget.limitMinor - used)} remaining", if (used > budget.limitMinor) Pink else Muted)
                TextButton(onClick = { category = budget.category; amount = java.math.BigDecimal.valueOf(budget.limitMinor, 2).toPlainString(); editing = true }) { Text("Edit limit") }
                TextButton(onClick = { removing = budget.category }) { Text("Remove limit", color = Pink) }
            }
        }
    }
    removing?.let { removed -> AlertDialog(onDismissRequest = { if (!saving) removing = null },
        title = { HideDialogNavigationKeys(); Text("Remove monthly limit?") }, text = { Text(error ?: "Your transactions will stay in your ledger. The $removed limit will be removed.") },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            model.removeBudget(removed, requestKey)
        }) { Text("Remove", color = Pink) } }, dismissButton = { TextButton(enabled = !saving, onClick = { removing = null }) { Text("Cancel") } }) }
    if (editing) AlertDialog(onDismissRequest = { if (!saving) editing = false }, title = { HideDialogNavigationKeys(); Text("Monthly category budget") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(category) }
                DropdownMenu(expanded, { expanded = false }) {
                    categories.filter { it.name != "Income" }.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat.name) }, onClick = { category = cat.name; expanded = false })
                    }
                }
            }
            OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Limit (KSh)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            error?.let { Text(it, color = Pink) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        val minor = parseMoney(amount)
        if (minor == null) error = "Enter a positive amount with up to two decimal places."
        else {
            model.saveBudget(category, minor, requestKey)
        }
    }) { Text(if (saving) "Saving…" else "Save") } }, dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text("Cancel") } })
}
