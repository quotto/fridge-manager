package com.quotto.fridgemanager.domain.inventory

/** 在庫データへの依存をUIとデータ実装から分離するドメイン境界。 */
interface InventoryRepository {
    fun hasItems(): Boolean
}
