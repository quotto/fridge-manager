package com.quotto.fridgemanager.domain.inventory

/** 端末内で永続化された食材。時刻はUnix epoch milliseconds（UTC）で保持する。 */
data class StoredIngredient(
    val id: String,
    val name: IngredientName,
    val quantity: InventoryQuantity,
    val unit: InventoryUnit,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
