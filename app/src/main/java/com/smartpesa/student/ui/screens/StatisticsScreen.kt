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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartpesa.student.data.*
import com.smartpesa.student.ui.components.*
import com.smartpesa.student.ui.theme.*
import java.time.*

@Composable
fun StatisticsScreen(transactions: List<Transaction>) {
    var period by rememberSaveable { mutableStateOf("Week") }
    val rows = periodTransactions(transactions, period).filter { !it.income }
    val totals = expenseTotals(rows)
    // Weekly bars represent weekdays; monthly bars represent weeks; yearly bars represent months.
    val labels = when (period) {
        "Week" -> listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        "Year" -> listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
        else -> listOf("W1", "W2", "W3", "W4", "W5")
    }
    val buckets = LongArray(labels.size)
    rows.forEach {
        val date = Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.of("Africa/Nairobi")).toLocalDate()
        val index = when (period) { "Week" -> date.dayOfWeek.value - 1; "Year" -> date.monthValue - 1; else -> (date.dayOfMonth - 1) / 7 }
        buckets[index] += it.amountMinor
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Statistics", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        PeriodTabs(period) { period = it }
        Panel {
            SectionTitle("Spending Flow")
            Text(money(rows.sumOf { it.amountMinor }), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom) {
                val maximum = (buckets.maxOrNull() ?: 0L).coerceAtLeast(1L)
                buckets.forEachIndexed { index, value ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height((value.toFloat() / maximum * 112 + 4).dp)
                            .background(if (value > 0) Blue else Blue.copy(alpha = 0.15f), RoundedCornerShape(20.dp)))
                        Text(labels[index], fontSize = 9.sp, color = Muted)
                    }
                }
            }
        }
        Panel {
            Caption("THIS ${period.uppercase()}")
            Text(if (rows.isEmpty()) "Add expenses to discover your spending patterns."
                else "${totals.first().first.name} is your largest spending category this ${period.lowercase()}.", fontSize = 13.sp)
        }
        SectionTitle("Top Categories")
        if (totals.isEmpty()) EmptyLedger()
        totals.forEach { (cat, amount) ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryBadge(cat)
                    Column(Modifier.weight(1f)) { Text(cat.name, fontSize = 14.sp); Caption("${(amount.toDouble() / rows.sumOf { it.amountMinor } * 100).toInt()}% OF SPENDING") }
                    Text(money(amount), fontSize = 13.sp)
                }
                LinearProgressIndicator(progress = { (amount.toDouble() / rows.sumOf { it.amountMinor }).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp), color = cat.color, trackColor = cat.color.copy(alpha = 0.12f))
            }
        }
    }
}
