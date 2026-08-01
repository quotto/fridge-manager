package com.quotto.fridgemanager.ui.navigation

import android.net.Uri

enum class AppDestination(
    val route: String,
    val title: String,
    val navigationLabel: String,
    val isTopLevel: Boolean,
) {
    Inventory("inventory", "在庫一覧", "在庫", true),
    Registration("registration", "手動登録", "登録", false),
    ImageAnalysis("image-analysis", "画像解析", "画像", false),
    Settings("settings", "設定", "設定", true),
    ;

    companion object {
        val start: AppDestination = Inventory
        val topLevel: List<AppDestination> = entries.filter(AppDestination::isTopLevel)

        fun selectedTopLevel(route: String?): AppDestination? = when (route) {
            Registration.route -> Inventory
            ImageAnalysis.route -> Inventory
            else -> if (route?.startsWith("unit-selection/") == true) Inventory
            else if (route?.startsWith("inventory/update/") == true) Inventory
            else if (route?.startsWith("image-analysis/update/") == true) Inventory
            else topLevel.firstOrNull { it.route == route }
        }

        const val existingUpdatePattern: String = "inventory/update/{ingredientId}"
        fun existingUpdateRoute(ingredientId: String): String = "inventory/update/$ingredientId"
        const val updateImagePattern: String = "image-analysis/update/{ingredientId}"
        fun updateImageRoute(ingredientId: String): String = "image-analysis/update/$ingredientId"
        const val unitSelectionPattern: String = "unit-selection/{selectedUnit}"
        fun unitSelectionRoute(selectedUnit: String): String =
            "unit-selection/${Uri.encode(selectedUnit)}"
    }
}
