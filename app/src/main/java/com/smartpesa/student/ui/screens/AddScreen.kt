package com.smartpesa.student.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartpesa.student.data.*
import com.smartpesa.student.ui.components.*
import com.smartpesa.student.ui.theme.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    }
}

@Composable
fun AddScreen(model: FinanceViewModel, existing: Transaction? = null, onBack: () -> Unit) {
    var income by rememberSaveable { mutableStateOf(existing?.income ?: false) }
    var amount by rememberSaveable { mutableStateOf(existing?.let { java.math.BigDecimal.valueOf(it.amountMinor, 2).toPlainString() } ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "Food & Dining") }
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    var dateText by rememberSaveable { mutableStateOf(existing?.let { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.of("Africa/Nairobi")).toLocalDate().toString() } ?: LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")).toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val requestKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val operation by model.ledgerOperation.collectAsStateWithLifecycle()
    val saving = operation?.busy == true
    LaunchedEffect(requestKey) { if (existing == null) model.recoverEntry(requestKey) }
    LaunchedEffect(operation, requestKey) {
        if (operation?.key == requestKey) {
            if (operation?.succeeded == true) { model.clearOperation(requestKey); onBack() }
            else if (operation?.failed == true) { error = "Could not save. Please try again."; model.clearOperation(requestKey) }
        }
    }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().imePadding()) {
        // The form scrolls independently; saving stays within reach above the keyboard.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
        BackTitle(if (existing == null) "Add Transaction" else "Edit Transaction") { if (!saving) onBack() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(false to "Expense", true to "Income").forEach { (value, label) ->
                FilterChip(income == value, onClick = { if (!saving && existing?.source != "sms" && income != value) { income = value; category = if (value) "Income" else "Food & Dining" } },
                    label = { Text(label) }, modifier = Modifier.weight(1f), border = null,
                    colors = FilterChipDefaults.filterChipColors(containerColor = CardColor,
                        selectedContainerColor = Blue, selectedLabelColor = Color.White, labelColor = Muted))
            }
        }
        Panel {
            Caption(if (income) "AMOUNT RECEIVED" else "AMOUNT SPENT")
            OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(),
                prefix = { Text("KSh ", color = Blue) }, placeholder = { Text("0.00") }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("Amount") })
        }
        Caption("SELECT CATEGORY")
        val options = categories.filter { if (income) it.name == "Income" || it.name == "Other" else it.name != "Income" }
        val columns = if (LocalDensity.current.fontScale >= 1.3f) 3 else 4
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cat ->
                    Surface(onClick = { category = cat.name }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp), color = CardColor,
                        border = if (category == cat.name) BorderStroke(2.dp, Blue) else null) {
                        Column(Modifier.padding(vertical = 12.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CategoryBadge(cat)
                            Text(cat.name, fontSize = 11.sp, color = if (category == cat.name) Blue else Muted,
                                minLines = 2, maxLines = 3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(),
            label = { Text("Description (optional)") }, shape = RoundedCornerShape(18.dp))
        OutlinedButton(onClick = {
            val date = LocalDate.parse(dateText)
            DatePickerDialog(context, { _, y, m, d -> dateText = LocalDate.of(y, m + 1, d).toString() },
                date.year, date.monthValue - 1, date.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis(); show()
                window?.let(::hideNavigationKeys)
            }
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(AppIcons.DateRange, null); Spacer(Modifier.width(12.dp))
            Text(LocalDate.parse(dateText).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")))
        }
        error?.let { Text(it, color = Pink) }
        }
        Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
        Button(onClick = {
            val minor = parseMoney(amount)
            if (minor == null) { error = "Enter an amount greater than zero with up to two decimal places." }
            else {
                // All reporting uses Nairobi time, including when the device timezone changes.
                val selectedDate = LocalDate.parse(dateText)
                val originalDate = existing?.let { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.of("Africa/Nairobi")).toLocalDate() }
                // Category/description edits must not silently move a transaction into another period.
                val timestamp = if (selectedDate == originalDate) existing!!.timestamp
                    else if (selectedDate == LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"))) System.currentTimeMillis()
                    else selectedDate.atStartOfDay(ZoneId.of("Africa/Nairobi")).toInstant().toEpochMilli()
                if (existing == null) model.add(minor, income, category, description, timestamp, requestKey)
                else model.edit(existing.copy(amountMinor = minor, income = income, category = category,
                    description = description.trim(), timestamp = timestamp, reviewState = "confirmed"), requestKey)
            }
        }, enabled = !saving, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
            Text(if (saving) "Saving…" else if (existing == null) "Save Transaction" else "Save Changes", fontWeight = FontWeight.SemiBold)
        }
        }
    }
}
