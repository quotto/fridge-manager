package com.quotto.fridgemanager.domain.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InventoryUnitTest {
    @Test
    fun `要件記載の21単位だけを提供する`() {
        assertEquals(
            listOf("g", "kg", "ml", "L", "個", "本", "枚", "袋", "パック", "箱", "缶", "瓶", "束", "株", "玉", "丁", "尾", "切れ", "房", "合", "食"),
            InventoryUnit.entries.map(InventoryUnit::symbol),
        )
    }

    @Test
    fun `表示文字列から単位を取得できる`() {
        assertEquals(InventoryUnit.GRAM, InventoryUnit.fromSymbol("g"))
        assertEquals(InventoryUnit.MEAL, InventoryUnit.fromSymbol("食"))
        InventoryUnit.entries.forEach { unit ->
            assertEquals(unit, InventoryUnit.fromSymbol(unit.symbol))
        }
    }

    @Test
    fun `未知の単位を拒否する`() {
        listOf("杯", "", "l").forEach { invalid ->
            val error = assertThrows(DomainValidationException::class.java) {
                InventoryUnit.fromSymbol(invalid)
            }
            assertEquals(DomainErrorCode.UNKNOWN_UNIT, error.code)
        }
    }
}
