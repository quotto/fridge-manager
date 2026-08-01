package com.quotto.fridgemanager.domain.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Issue #31 の一括確定コマンド契約。
 *
 * 永続化を始める前に、追加と更新を合わせた全件を検証できる形へ固定する。
 */
class InventoryCommitTest {
    @Test
    fun `追加と更新を合わせて30件まで保持する`() {
        val commit = InventoryCommit.create(
            newItems = (1..20).map { draft("新規$it") },
            updates = (1..10).map { stored("id-$it", "更新$it") },
        )

        assertEquals(20, commit.newItems.size)
        assertEquals(10, commit.updates.size)
        assertEquals(30, commit.size)
    }

    @Test
    fun `追加と更新の合計が31件なら永続化境界へ渡せない`() {
        val error = assertThrows(DomainValidationException::class.java) {
            InventoryCommit.create(
                newItems = (1..30).map { draft("新規$it") },
                updates = listOf(stored("existing", "更新対象")),
            )
        }

        assertEquals(DomainErrorCode.BATCH_TOO_LARGE, error.code)
    }

    @Test
    fun `空の確定操作は拒否する`() {
        assertThrows(DomainValidationException::class.java) {
            InventoryCommit.create(newItems = emptyList(), updates = emptyList())
        }
    }

    @Test
    fun `新規内の正規化名重複は全件事前検証で拒否する`() {
        assertThrows(DuplicateIngredientException::class.java) {
            InventoryCommit.create(
                newItems = listOf(draft("ＮＦＫＣ"), draft("NFKC")),
                updates = emptyList(),
            )
        }
    }

    @Test
    fun `新規と更新を跨ぐ正規化名重複も全件事前検証で拒否する`() {
        assertThrows(DuplicateIngredientException::class.java) {
            InventoryCommit.create(
                newItems = listOf(draft("  牛乳  ")),
                updates = listOf(stored("milk", "牛乳")),
            )
        }
    }

    @Test
    fun `更新対象IDの重複は同一在庫の二重更新として拒否する`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventoryCommit.create(
                newItems = emptyList(),
                updates = listOf(
                    stored("same-id", "豆腐"),
                    stored("same-id", "木綿豆腐"),
                ),
            )
        }
    }
}

private fun draft(name: String) = IngredientDraft.create(name, "1", "個")

private fun stored(id: String, name: String) = StoredIngredient(
    id = id,
    name = IngredientName.from(name),
    quantity = InventoryQuantity.from("2"),
    unit = InventoryUnit.PIECE,
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
)
