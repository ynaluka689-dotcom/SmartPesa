package com.smartpesa.student.data

import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun amountsRetainExactCents() {
        assertEquals(1050L, parseMoney("10.50"))
        assertEquals(1L, parseMoney("0.01"))
    }
    @Test fun invalidAmountsCannotBeSaved() {
        listOf("", "0", "-10", "NaN", "1.001", "1e4", "9223372036854775808").forEach {
            assertNull(it, parseMoney(it))
        }
    }
}
