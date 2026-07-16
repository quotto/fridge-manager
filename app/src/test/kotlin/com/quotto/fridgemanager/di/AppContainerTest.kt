package com.quotto.fridgemanager.di

import com.quotto.fridgemanager.data.local.EmptyInventoryRepository
import org.junit.Assert.assertSame
import org.junit.Test

class AppContainerTest {
    @Test
    fun `データ実装をドメイン境界越しに提供する`() {
        val repository = EmptyInventoryRepository()
        val container = DefaultAppContainer(inventoryRepository = repository)

        assertSame(repository, container.inventoryRepository)
    }
}
