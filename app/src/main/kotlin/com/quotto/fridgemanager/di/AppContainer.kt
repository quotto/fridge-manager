package com.quotto.fridgemanager.di

import android.content.Context
import com.quotto.fridgemanager.data.local.EmptyInventoryRepository
import com.quotto.fridgemanager.data.local.InventoryDatabase
import com.quotto.fridgemanager.data.local.RoomInventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.presentation.inventory.InventoryPresenter
import com.quotto.fridgemanager.presentation.inventory.IngredientUpdatePresenter
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter

/** アプリ全体の依存を生成するComposition Root。 */
interface AppContainer {
    val inventoryRepository: InventoryRepository
    val inventoryPresenter: InventoryPresenter
    val registrationPresenter: RegistrationPresenter
    val ingredientUpdatePresenter: IngredientUpdatePresenter
}

class DefaultAppContainer(
    inventoryRepository: InventoryRepository? = null,
    context: Context? = null,
) : AppContainer {
    override val inventoryRepository: InventoryRepository = inventoryRepository
        ?: context?.let { RoomInventoryRepository(InventoryDatabase.create(it)) }
        ?: EmptyInventoryRepository()
    override val inventoryPresenter: InventoryPresenter = InventoryPresenter(this.inventoryRepository)
    override val registrationPresenter: RegistrationPresenter = RegistrationPresenter(this.inventoryRepository)
    override val ingredientUpdatePresenter = IngredientUpdatePresenter(this.inventoryRepository)
}
