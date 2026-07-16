package com.quotto.fridgemanager.di

import com.quotto.fridgemanager.data.local.EmptyInventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.presentation.inventory.InventoryPresenter

/** アプリ全体の依存を生成するComposition Root。 */
interface AppContainer {
    val inventoryRepository: InventoryRepository
    val inventoryPresenter: InventoryPresenter
}

class DefaultAppContainer(
    override val inventoryRepository: InventoryRepository = EmptyInventoryRepository(),
) : AppContainer {
    override val inventoryPresenter: InventoryPresenter = InventoryPresenter(inventoryRepository)
}
