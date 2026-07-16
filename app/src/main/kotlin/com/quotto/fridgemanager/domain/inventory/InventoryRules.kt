package com.quotto.fridgemanager.domain.inventory

import java.math.BigDecimal
import java.util.Collections

enum class UpdateMethod {
    INCREASE,
    DECREASE,
    REPLACE,
}

object StockUpdate {
    fun apply(
        current: InventoryQuantity,
        absoluteValue: InventoryQuantity,
        method: UpdateMethod,
    ): InventoryQuantity {
        val updated = when (method) {
            UpdateMethod.INCREASE -> current.value + absoluteValue.value
            UpdateMethod.DECREASE -> current.value - absoluteValue.value
            UpdateMethod.REPLACE -> absoluteValue.value
        }
        return InventoryQuantity.from(updated)
    }
}

/** 手入力とAI候補が共有する、確定前品目のドメイン表現。 */
data class IngredientDraft(
    val name: IngredientName,
    val quantity: InventoryQuantity,
    val unit: InventoryUnit,
) {
    companion object {
        fun create(name: String, quantity: String, unit: String): IngredientDraft =
            IngredientDraft(
                name = IngredientName.from(name),
                quantity = InventoryQuantity.from(quantity),
                unit = InventoryUnit.fromSymbol(unit),
            )

        fun create(name: String, quantity: BigDecimal, unit: InventoryUnit): IngredientDraft =
            IngredientDraft(
                name = IngredientName.from(name),
                quantity = InventoryQuantity.from(quantity),
                unit = unit,
            )
    }
}

class InventoryBatch private constructor(val items: List<IngredientDraft>) {
    companion object {
        const val MAX_ITEMS = 30

        fun create(items: List<IngredientDraft>): InventoryBatch {
            if (items.size > MAX_ITEMS) {
                throw DomainValidationException(DomainErrorCode.BATCH_TOO_LARGE, "A batch may contain at most 30 items")
            }
            val duplicateGroups = items
                .withIndex()
                .groupBy(
                    keySelector = { it.value.name.normalizedValue },
                    valueTransform = { it.index },
                )
                .filterValues { it.size > 1 }
                .map { (normalizedName, indices) -> DuplicateGroup(normalizedName, indices) }
                .toList()
            if (duplicateGroups.isNotEmpty()) {
                throw DuplicateIngredientException(duplicateGroups)
            }
            return InventoryBatch(Collections.unmodifiableList(items.toList()))
        }
    }
}

object DuplicateIngredients {
    fun find(
        candidates: List<IngredientName>,
        registered: Collection<IngredientName>,
    ): List<IngredientName> {
        val registeredKeys = registered.mapTo(hashSetOf()) { it.normalizedValue }
        return candidates.filter { it.normalizedValue in registeredKeys }
    }

    fun findConflict(
        candidate: IngredientName,
        registered: Collection<IngredientReference>,
        excludingId: String? = null,
    ): IngredientReference? = registered.firstOrNull {
        it.id != excludingId && it.name.normalizedValue == candidate.normalizedValue
    }
}

/** 永続化方式に依存せず、編集時の自己除外に必要な識別情報だけを表す。 */
data class IngredientReference(
    val id: String,
    val name: IngredientName,
)
