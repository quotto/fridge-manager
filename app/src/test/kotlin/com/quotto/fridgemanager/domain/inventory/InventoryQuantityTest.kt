package com.quotto.fridgemanager.domain.inventory

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InventoryQuantityTest {
    @Test
    fun `0と100および小数2桁を受け入れる`() {
        assertEquals(BigDecimal.ZERO, InventoryQuantity.from("0").value)
        assertEquals(BigDecimal("100"), InventoryQuantity.from("100").value)
        assertEquals(BigDecimal("0.01"), InventoryQuantity.from("0.01").value)
        assertEquals(BigDecimal("99.99"), InventoryQuantity.from("99.99").value)
    }

    @Test
    fun `末尾ゼロは小数桁として数えず正規化する`() {
        assertEquals(BigDecimal("1.2"), InventoryQuantity.from("1.200").value)
        assertEquals(InventoryQuantity.from("1.20"), InventoryQuantity.from("1.2"))
        assertEquals(InventoryQuantity.from("1"), InventoryQuantity.from("1.00"))
    }

    @Test
    fun `範囲外と小数3桁を拒否する`() {
        listOf("-0.01", "100.01").forEach { invalid ->
            val error = assertThrows(DomainValidationException::class.java) { InventoryQuantity.from(invalid) }
            assertEquals(DomainErrorCode.QUANTITY_OUT_OF_RANGE, error.code)
        }
        val decimalPlaces = assertThrows(DomainValidationException::class.java) {
            InventoryQuantity.from("0.001")
        }
        assertEquals(DomainErrorCode.INVALID_QUANTITY, decimalPlaces.code)
    }

    @Test
    fun `極端な指数表現も実装例外を漏らさずドメインエラーにする`() {
        val huge = assertThrows(DomainValidationException::class.java) {
            InventoryQuantity.from(BigDecimal("1E+2147483647"))
        }
        val tiny = assertThrows(DomainValidationException::class.java) {
            InventoryQuantity.from(BigDecimal("1E-2147483647"))
        }

        assertEquals(DomainErrorCode.QUANTITY_OUT_OF_RANGE, huge.code)
        assertEquals(DomainErrorCode.INVALID_QUANTITY, tiny.code)
    }

    @Test
    fun `数値でない文字列を拒否する`() {
        listOf("", " ", "1個").forEach { invalid ->
            val error = assertThrows(DomainValidationException::class.java) {
                InventoryQuantity.from(invalid)
            }
            assertEquals(DomainErrorCode.INVALID_QUANTITY, error.code)
        }
    }

    @Test
    fun `等しい数量はhashCodeと表示も一致する`() {
        val withZeros = InventoryQuantity.from("1.20")
        val withoutZeros = InventoryQuantity.from("1.2")

        assertEquals(withZeros.hashCode(), withoutZeros.hashCode())
        assertEquals("1.2", withZeros.toString())
    }
}
