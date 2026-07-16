package com.quotto.fridgemanager.data.local

import com.quotto.fridgemanager.domain.inventory.InventoryRepository

/** Room実装が導入されるまで使用する、永続化を行わない空の実装。 */
class EmptyInventoryRepository : InventoryRepository {
    override fun hasItems(): Boolean = false
}
