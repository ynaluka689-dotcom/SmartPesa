package com.smartpesa.student.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartpesa.student.data.FinanceViewModel
import com.smartpesa.student.data.Transaction
import com.smartpesa.student.data.money
import com.smartpesa.student.ui.components.*
import com.smartpesa.student.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A student sees the financial record and actions; classification internals stay out of this sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetails(transaction: Transaction, model: FinanceViewModel, onDismiss: () -> Unit, onEdit: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val requestKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val operation by model.ledgerOperation.collectAsStateWithLifecycle()
    val deleting = operation?.busy == true
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(operation, requestKey) {
        if (operation?.key == requestKey && operation?.failed == true) {
            confirmDelete = false; error = "Could not delete. Please try again."; model.clearOperation(requestKey)
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!deleting) onDismiss() }, containerColor = CardColor) {
        HideDialogNavigationKeys()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Caption(if (transaction.income) "INCOME" else "EXPENSE")
            Text(money(transaction.amountMinor), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                color = if (transaction.income) Green else Pink)
            Text(transaction.description.ifBlank { transaction.category }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryBadge(categoryFor(transaction.category))
                Column {
                    Text(transaction.category)
                    Text(Instant.ofEpochMilli(transaction.timestamp).atZone(ZoneId.of("Africa/Nairobi"))
                        .format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · HH:mm")), color = Muted, fontSize = 12.sp)
                }
            }
            transaction.mpesaCode?.let { Caption("M-PESA reference: $it") }
            if (transaction.reviewState != "confirmed") Caption("Check category", Amber)
            error?.let { Text(it, color = Pink) }
            Button(onClick = onEdit, enabled = !deleting, modifier = Modifier.fillMaxWidth()) { Text("Edit Transaction") }
            TextButton(onClick = { confirmDelete = true }, enabled = !deleting, modifier = Modifier.fillMaxWidth()) {
                Text(if (deleting) "Deleting…" else "Delete Transaction", color = Pink)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { HideDialogNavigationKeys(); Text("Delete transaction?") },
        text = { Text("This will remove the transaction and its linked fee, if any, and update your balance and spending totals.") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            model.delete(transaction, requestKey)
        }) { Text(if (deleting) "Deleting…" else "Delete", color = Pink) } },
        dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("Keep Transaction") } })
}
