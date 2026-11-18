package com.smartpesa.student.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Reject sub-cent precision and overflow instead of silently changing an amount. */
fun parseMoney(text: String): Long? = try {
    if (!Regex("\\d+(\\.\\d{1,2})?").matches(text.trim())) null
    else BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact().takeIf { it > 0 }
} catch (_: ArithmeticException) { null }

fun money(minor: Long): String = "KSh " + NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(BigDecimal.valueOf(minor, 2))
