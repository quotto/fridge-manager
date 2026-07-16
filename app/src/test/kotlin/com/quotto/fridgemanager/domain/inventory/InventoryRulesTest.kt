package com.quotto.fridgemanager.domain.inventory

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InventoryRulesTest {
    private fun quantity(value: String) = InventoryQuantity.from(value)

    @Test
    fun `増加減少置換の更新後数量を計算する`() {
        val current = quantity("10.50")

        assertEquals(quantity("12.75"), StockUpdate.apply(current, quantity("2.25"), UpdateMethod.INCREASE))
        assertEquals(quantity("8.25"), StockUpdate.apply(current, quantity("2.25"), UpdateMethod.DECREASE))
        assertEquals(quantity("2.25"), StockUpdate.apply(current, quantity("2.25"), UpdateMethod.REPLACE))
    }

    @Test
    fun `更新結果の0と100を受け入れる`() {
        assertEquals(quantity("0"), StockUpdate.apply(quantity("1"), quantity("1"), UpdateMethod.DECREASE))
        assertEquals(quantity("100"), StockUpdate.apply(quantity("99"), quantity("1"), UpdateMethod.INCREASE))
        assertEquals(quantity("50"), StockUpdate.apply(quantity("50"), quantity("0"), UpdateMethod.DECREASE))
    }

    @Test
    fun `更新結果が範囲外なら拒否する`() {
        assertThrows(DomainValidationException::class.java) {
            StockUpdate.apply(quantity("99.99"), quantity("0.02"), UpdateMethod.INCREASE)
        }
        assertThrows(DomainValidationException::class.java) {
            StockUpdate.apply(quantity("0"), quantity("0.01"), UpdateMethod.DECREASE)
        }
    }

    @Test
    fun `手入力とAI候補に同じ品目生成規則を利用できる`() {
        val manual = IngredientDraft.create("　豆腐　", "1", "丁")
        val aiCandidate = IngredientDraft.create("豆腐", BigDecimal.ONE, InventoryUnit.TOFU)

        assertEquals(manual, aiCandidate)
    }

    @Test
    fun `一括処理は空と30件を受け入れる`() {
        assertEquals(0, InventoryBatch.create(emptyList()).items.size)
        val items = (1..30).map { IngredientDraft.create("食材$it", "1", "個") }

        assertEquals(30, InventoryBatch.create(items).items.size)
    }

    @Test
    fun `一括処理は31件を拒否する`() {
        val items = (1..31).map { IngredientDraft.create("食材$it", "1", "個") }

        val error = assertThrows(DomainValidationException::class.java) { InventoryBatch.create(items) }

        assertEquals(DomainErrorCode.BATCH_TOO_LARGE, error.code)
    }

    @Test
    fun `生成後の一括処理へ品目を追加して不変条件を破壊できない`() {
        val batch = InventoryBatch.create(
            listOf(
                IngredientDraft.create("にんじん", "1", "本"),
                IngredientDraft.create("玉ねぎ", "1", "個"),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val exposedItems = batch.items as MutableList<IngredientDraft>
        assertThrows(UnsupportedOperationException::class.java) {
            exposedItems += IngredientDraft.create("にんじん", "2", "本")
        }
        assertEquals(2, batch.items.size)
    }

    @Test
    fun `一括処理は正規化後に重複する名前を拒否し対象名を示す`() {
        val error = assertThrows(DuplicateIngredientException::class.java) {
            InventoryBatch.create(
                listOf(
                    IngredientDraft.create("ＡＢＣ", "1", "個"),
                    IngredientDraft.create("ABC", "2", "袋"),
                ),
            )
        }

        assertEquals(listOf("ABC"), error.normalizedNames)
        assertEquals(listOf(0, 1), error.duplicateGroups.single().indices)
        assertEquals(DomainErrorCode.DUPLICATE_NAME, error.code)
    }

    @Test
    fun `一括処理は複数の重複グループと全位置を入力順で示す`() {
        val error = assertThrows(DuplicateIngredientException::class.java) {
            InventoryBatch.create(
                listOf(
                    IngredientDraft.create("ＡＢＣ", "1", "個"),
                    IngredientDraft.create("豆腐", "1", "丁"),
                    IngredientDraft.create("ABC", "1", "袋"),
                    IngredientDraft.create("豆腐", "2", "丁"),
                    IngredientDraft.create("ＡＢＣ", "3", "箱"),
                ),
            )
        }

        assertEquals(listOf("ABC", "豆腐"), error.normalizedNames)
        assertEquals(listOf(0, 2, 4), error.duplicateGroups[0].indices)
        assertEquals(listOf(1, 3), error.duplicateGroups[1].indices)
    }

    @Test
    fun `登録済みとの重複を正規化名で検出する`() {
        val registered = listOf(IngredientName.from("　にんじん "), IngredientName.from("玉ねぎ"))

        assertEquals(
            listOf(IngredientName.from("にんじん")),
            DuplicateIngredients.find(
                candidates = listOf(IngredientName.from("にんじん"), IngredientName.from("キャベツ")),
                registered = registered,
            ),
        )
    }

    @Test
    fun `編集時は自己IDを除外し別食材との衝突相手を返す`() {
        val registered = listOf(
            IngredientReference("id-1", IngredientName.from("にんじん")),
            IngredientReference("id-2", IngredientName.from("玉ねぎ")),
        )

        assertEquals(
            null,
            DuplicateIngredients.findConflict(
                candidate = IngredientName.from("にんじん"),
                registered = registered,
                excludingId = "id-1",
            ),
        )
        assertEquals(
            registered[1],
            DuplicateIngredients.findConflict(
                candidate = IngredientName.from("玉ねぎ"),
                registered = registered,
                excludingId = "id-1",
            ),
        )
    }

    @Test
    fun `新規登録では衝突対象を返し衝突がなければnullを返す`() {
        val registered = listOf(
            IngredientReference("id-1", IngredientName.from("ＡＢＣ")),
        )

        assertEquals(
            registered.single(),
            DuplicateIngredients.findConflict(IngredientName.from("ABC"), registered),
        )
        assertEquals(
            null,
            DuplicateIngredients.findConflict(IngredientName.from("豆腐"), registered),
        )
    }
}
