package com.quotto.fridgemanager

/** このアプリが動作保証の対象とする Android API レベル。 */
object SupportedPlatform {
    const val minApi = 30
    const val maxApi = 37

    val apiLevels: List<Int> = (minApi..maxApi).toList()

    fun supports(apiLevel: Int): Boolean = apiLevel in minApi..maxApi
}
