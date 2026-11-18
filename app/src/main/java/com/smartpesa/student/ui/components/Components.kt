package com.smartpesa.student.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartpesa.student.data.Transaction
import com.smartpesa.student.data.money
import com.smartpesa.student.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One category definition drives the picker, charts and budget colors. */
data class Category(val name: String, val icon: ImageVector, val color: Color)
val categories = listOf(
    Category("Food & Dining", AppIcons.Restaurant, Amber),
    Category("Groceries", AppIcons.ShoppingBasket, Green),
    Category("Transport", AppIcons.DirectionsBus, Blue),
    Category("Airtime & Data", AppIcons.PhoneAndroid, Color(0xFF9C7BF5)),
    Category("Tuition & Fees", AppIcons.School, Blue),
    Category("Accommodation", AppIcons.Home, Color(0xFF6AD5CD)),
    Category("Utilities", AppIcons.ReceiptLong, Amber),
    Category("Shopping", AppIcons.ShoppingBag, Pink),
    Category("Health", AppIcons.FavoriteBorder, Pink),
    Category("Entertainment", AppIcons.Movie, Color(0xFF9C7BF5)),
    Category("Income", AppIcons.SouthWest, Green),
    Category("Other", AppIcons.MoreHoriz, Muted),
)
fun categoryFor(name: String) = categories.firstOrNull { it.name == name } ?: categories.last()

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Caption(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 11.sp, letterSpacing = 0.6.sp)
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
    }
}

@Composable
fun CategoryBadge(category: Category, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).background(category.color.copy(alpha = 0.12f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center) {
        Icon(category.icon, category.name, Modifier.size(22.dp), tint = category.color)
    }
}

@Composable
fun TransactionRow(transaction: Transaction, onClick: (() -> Unit)? = null) {
    val category = categoryFor(transaction.category)
    Row(Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(20.dp))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "View transaction", onClick = onClick) else Modifier)
        .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryBadge(category)
        Column(Modifier.weight(1f)) {
            Text(transaction.description.ifBlank { transaction.category }, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(Instant.ofEpochMilli(transaction.timestamp).atZone(ZoneId.of("Africa/Nairobi"))
                .format(DateTimeFormatter.ofPattern("HH:mm · MMM d")) + " · " + transaction.category,
                color = Muted, fontSize = 10.sp)
            if (transaction.reviewState != "confirmed") Text("Check category", color = Amber, fontSize = 11.sp)
        }
        Text((if (transaction.income) "+" else "−") + money(transaction.amountMinor),
            color = if (transaction.income) Green else Pink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Arc proportions are derived from actual expenses; an empty ledger renders a quiet ring. */
@Composable
fun SpendingRing(totals: List<Pair<Category, Long>>, center: String, label: String, modifier: Modifier = Modifier) {
    val total = totals.sumOf { it.second }
    Box(modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val width = 16.dp.toPx()
            drawArc(Muted.copy(alpha = 0.12f), -90f, 360f, false, style = Stroke(width))
            if (total > 0) {
                var start = -90f
                totals.forEach { (cat, amount) ->
                    val angle = (amount.toDouble() / total * 360).toFloat()
                    drawArc(cat.color, start, angle, false, style = Stroke(width, cap = StrokeCap.Butt))
                    start += angle
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Caption(label)
        }
    }
}

@Composable
fun EmptyLedger(text: String = "Add your first transaction to see your spending.") {
    Panel { Text(text, color = Muted, fontSize = 14.sp) }
}
