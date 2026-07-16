package com.quotto.fridgemanager.ui.navigation

enum class AppDestination(
    val route: String,
    val title: String,
    val navigationLabel: String,
    val isTopLevel: Boolean,
) {
    Inventory("inventory", "在庫一覧", "在庫", true),
    Registration("registration", "手動登録", "登録", false),
    ImageAnalysis("image-analysis", "画像解析", "画像", true),
    Settings("settings", "設定", "設定", true),
    ;

    companion object {
        val start: AppDestination = Inventory
        val topLevel: List<AppDestination> = entries.filter(AppDestination::isTopLevel)

        fun selectedTopLevel(route: String?): AppDestination? = when (route) {
            Registration.route -> Inventory
            else -> topLevel.firstOrNull { it.route == route }
        }
    }
}
