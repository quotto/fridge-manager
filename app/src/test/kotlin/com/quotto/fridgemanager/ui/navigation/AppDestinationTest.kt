package com.quotto.fridgemanager.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `在庫一覧を開始画面として主要4画面を定義する`() {
        assertEquals(AppDestination.Inventory, AppDestination.start)
        assertEquals(
            listOf("inventory", "registration", "image-analysis", "settings"),
            AppDestination.entries.map(AppDestination::route),
        )
        assertEquals(4, AppDestination.entries.map(AppDestination::route).distinct().size)
    }

    @Test
    fun `画面名は日本語で空文字を許可しない`() {
        assertTrue(AppDestination.entries.all { it.title.isNotBlank() })
        assertEquals("在庫一覧", AppDestination.Inventory.title)
        assertEquals("手動登録", AppDestination.Registration.title)
        assertEquals("画像解析", AppDestination.ImageAnalysis.title)
        assertEquals("設定", AppDestination.Settings.title)
    }

    @Test
    fun `登録画面は在庫一覧から開く子画面として下部ナビゲーションに表示しない`() {
        assertEquals(
            listOf(
                AppDestination.Inventory,
                AppDestination.Settings,
            ),
            AppDestination.topLevel,
        )
        assertEquals(
            AppDestination.Inventory,
            AppDestination.selectedTopLevel(AppDestination.Registration.route),
        )
        assertEquals(
            AppDestination.Inventory,
            AppDestination.selectedTopLevel(AppDestination.ImageAnalysis.route),
        )
    }
}
