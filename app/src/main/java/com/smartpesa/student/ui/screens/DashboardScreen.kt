package com.smartpesa.student.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartpesa.student.data.*
import com.smartpesa.student.ui.components.*
import com.smartpesa.student.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter

/** Reporting periods use local calendar boundaries, not a fixed number of seconds. */
fun periodTransactions(transactions: List<Transaction>, period: String): List<Transaction> {
    val today = LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"))
    val start = when (period) {
        "Week" -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "Year" -> today.withDayOfYear(1)
        else -> today.withDayOfMonth(1)
    }
    val end = when (period) {
        "Week" -> start.plusWeeks(1)
        "Year" -> start.plusYears(1)
        else -> start.plusMonths(1)
    }
    val zone = ZoneId.of("Africa/Nairobi")
    return transactions.filter { it.timestamp >= start.atStartOfDay(zone).toInstant().toEpochMilli() &&
        it.timestamp < end.atStartOfDay(zone).toInstant().toEpochMilli() }
}
fun expenseTotals(transactions: List<Transaction>) = transactions.filter { !it.income }
    .groupBy { it.category }.map { (name, rows) -> categoryFor(name) to rows.sumOf { it.amountMinor } }
    .sortedByDescending { it.second }

@Composable
fun PeriodTabs(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Week", "Month", "Year").forEach { period ->
            FilterChip(selected == period, onClick = { onSelect(period) }, label = { Text(period, fontSize = 12.sp) },
                border = null, shape = RoundedCornerShape(30.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = CardColor,
                    selectedContainerColor = Blue, selectedLabelColor = Color.White, labelColor = Muted))
        }
    }
}

@Composable
fun DashboardScreen(transactions: List<Transaction>, onAdd: () -> Unit, onHistory: () -> Unit, onStats: () -> Unit, onTransaction: (Transaction) -> Unit) {
    var period by rememberSaveable { mutableStateOf("Month") }
    val filtered = periodTransactions(transactions, period)
    val income = transactions.filter { it.income }.sumOf { it.amountMinor }
    val spent = transactions.filter { !it.income }.sumOf { it.amountMinor }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text("Dashboard", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Text(LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = Muted, fontSize = 12.sp)
            }
            Column(Modifier.fillMaxWidth().background(Blue, RoundedCornerShape(25.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Total balance", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                Text(money(income - spent), fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { Caption("↙  INCOME", Color.White.copy(alpha = 0.8f)); Text(money(income), fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f)) { Caption("↗  EXPENSES", Color.White.copy(alpha = 0.8f)); Text(money(spent), fontWeight = FontWeight.SemiBold) }
                }
            }
            PeriodTabs(period) { period = it }
            Panel {
                SectionTitle("${period} spending", "Details", onStats)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpendingRing(expenseTotals(filtered), money(filtered.filter { !it.income }.sumOf { it.amountMinor }), "SPENT")
                }
                expenseTotals(filtered).take(4).chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) { pair.forEach { (cat, _) ->
                        Text("●  ${cat.name}", Modifier.weight(1f), color = cat.color, fontSize = 11.sp)
                    } }
                }
                if (filtered.none { !it.income }) Text("Your spending breakdown will appear here.", color = Muted, fontSize = 12.sp)
            }
            SectionTitle("Recent transactions", "View all", onHistory)
            if (transactions.isEmpty()) EmptyLedger() else transactions.take(4).forEach { tx -> TransactionRow(tx) { onTransaction(tx) } }
            Spacer(Modifier.height(70.dp))
        }
        FloatingActionButton(onClick = onAdd, Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Blue, contentColor = Color.White, shape = RoundedCornerShape(20.dp)) {
            Icon(AppIcons.Add, "Add transaction")
        }
    }
}
