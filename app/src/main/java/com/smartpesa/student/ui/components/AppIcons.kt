package com.smartpesa.student.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A small, consistent line-icon set avoids bundling thousands of unused category icons. */
object AppIcons {
    private fun line(name: String, path: String, mirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).addPath(
            pathData = PathParser().parsePathString(path).toNodes(), fill = null,
            stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
    val Home = line("Home", "M3,10 L12,3 L21,10 M5,9 L5,21 L10,21 L10,14 L14,14 L14,21 L19,21 L19,9")
    val Person = line("Person", "M12,3 A4,4 0,1 1,12,11 A4,4 0,1 1,12,3 M4,21 L4,19 Q4,14 12,14 Q20,14 20,19 L20,21")
    val BarChart = line("BarChart", "M4,21 L4,13 L8,13 L8,21 M10,21 L10,8 L14,8 L14,21 M16,21 L16,3 L20,3 L20,21 M2,21 L22,21")
    val AccountBalanceWallet = line("Wallet", "M20,7 L20,4 L4,4 Q2,4 2,6 L2,19 Q2,21 4,21 L20,21 L20,15 M2,7 L21,7 L21,15 L15,15 L15,10 L21,10 M17,12.5 L18,12.5")
    val Restaurant = line("Food", "M4,3 L4,9 Q4,12 7,12 Q10,12 10,9 L10,3 M7,3 L7,21 M19,3 Q14,7 16,12 L19,12 M19,3 L19,21")
    val ShoppingBasket = line("Groceries", "M3,10 L21,10 L19,21 L5,21 Z M7,10 L10,3 M17,10 L14,3 M9,14 L9,18 M15,14 L15,18")
    val DirectionsBus = line("Transport", "M5,3 L19,3 L20,18 L4,18 Z M4,11 L20,11 M8,14 L8,15 M16,14 L16,15 M6,18 L6,21 M18,18 L18,21 M9,6 L15,6")
    val PhoneAndroid = line("Phone", "M7,2 L17,2 Q19,2 19,4 L19,20 Q19,22 17,22 L7,22 Q5,22 5,20 L5,4 Q5,2 7,2 Z M5,6 L19,6 M5,18 L19,18 M11,20 L13,20")
    val School = line("Education", "M2,8 L12,3 L22,8 L12,13 Z M6,10 L6,17 Q12,22 18,17 L18,10 M22,8 L22,17")
    val ReceiptLong = line("Bills", "M5,3 L7,5 L9,3 L11,5 L13,3 L15,5 L17,3 L19,5 L19,21 L17,19 L15,21 L13,19 L11,21 L9,19 L7,21 L5,19 Z M8,9 L16,9 M8,13 L16,13 M8,16 L13,16")
    val ShoppingBag = line("Shopping", "M5,7 L19,7 L21,21 L3,21 Z M8,9 L8,6 Q8,2 12,2 Q16,2 16,6 L16,9")
    val FavoriteBorder = line("Health", "M12,21 L3,12 Q-1,4 6,3 Q10,2 12,7 Q14,2 18,3 Q25,4 21,12 Z")
    val Movie = line("Entertainment", "M3,8 L21,8 L21,21 L3,21 Z M3,8 L2,3 L20,1 L21,6 M7,3 L10,7 M13,2 L16,6 M10,12 L16,15 L10,18 Z")
    val SouthWest = line("Income", "M19,5 L5,19 M5,9 L5,19 L15,19")
    val MoreHoriz = line("Other", "M4,12 L5,12 M11,12 L12,12 M18,12 L19,12")
    val DateRange = line("Date", "M3,5 L21,5 L21,21 L3,21 Z M3,10 L21,10 M7,3 L7,7 M17,3 L17,7 M7,14 L9,14 M13,14 L15,14 M7,18 L9,18")
    val Add = line("Add", "M12,4 L12,20 M4,12 L20,12")
    val Search = line("Search", "M10,3 A7,7 0,1 1,10,17 A7,7 0,1 1,10,3 M15,15 L21,21")
    val ChevronRight = line("Next", "M9,5 L16,12 L9,19", true)
    val ArrowBack = line("Back", "M20,12 L4,12 M11,5 L4,12 L11,19", true)
    val FileDownload = line("Export", "M12,3 L12,16 M7,11 L12,16 L17,11 M4,17 L4,21 L20,21 L20,17")
    val Shield = line("Privacy", "M12,2 L21,6 L20,14 Q18,20 12,23 Q6,20 4,14 L3,6 Z M8,12 L11,15 L16,9")
    val Info = line("Info", "M12,2 A10,10 0,1 1,12,22 A10,10 0,1 1,12,2 M12,11 L12,17 M12,7 L12,7.5")
}
