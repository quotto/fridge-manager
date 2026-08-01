package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.inventory.DomainErrorCode
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import com.quotto.fridgemanager.domain.inventory.DuplicateStoredIngredientException
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException
import kotlinx.coroutines.CancellationException

enum class QuantityUpdateMode(val label: String) { INCREASE("増加"), DECREASE("減少"), REPLACE("置換");
    companion object { fun fromLabel(label: String) = entries.firstOrNull { it.label == label } }
}

sealed interface UpdatePreviewResult {
    data class Success(
        val ingredient: StoredIngredient,
        val mode: QuantityUpdateMode,
        val currentQuantity: InventoryQuantity,
        val inputQuantity: InventoryQuantity,
        val updatedQuantity: InventoryQuantity,
    ) : UpdatePreviewResult
    data class Invalid(val message: String) : UpdatePreviewResult
    data object NotFound : UpdatePreviewResult
    data object Failed : UpdatePreviewResult
}

sealed interface IngredientMutationResult {
    data object Saved : IngredientMutationResult
    data object Deleted : IngredientMutationResult
    data object ConfirmationRequired : IngredientMutationResult
    data class Invalid(val field: String, val message: String) : IngredientMutationResult
    data object DuplicateName : IngredientMutationResult
    data object Conflict : IngredientMutationResult
    data object NotFound : IngredientMutationResult
    data object Failed : IngredientMutationResult
}

class IngredientUpdatePresenter(private val repository: InventoryRepository) {
    suspend fun load(id: String): StoredIngredient? = repository.getById(id)

    suspend fun preview(id: String, modeLabel: String, rawInput: String): UpdatePreviewResult {
        val mode = QuantityUpdateMode.fromLabel(modeLabel)
            ?: return UpdatePreviewResult.Invalid("更新方法を選択してください")
        val input = try { InventoryQuantity.from(rawInput) } catch (_: DomainValidationException) {
            return UpdatePreviewResult.Invalid("在庫数は0以上100以下、小数2桁までで入力してください")
        }
        return try {
            val ingredient = repository.getById(id) ?: return UpdatePreviewResult.NotFound
            val rawResult = when (mode) {
                QuantityUpdateMode.INCREASE -> ingredient.quantity.value + input.value
                QuantityUpdateMode.DECREASE -> ingredient.quantity.value - input.value
                QuantityUpdateMode.REPLACE -> input.value
            }
            val updated = InventoryQuantity.from(rawResult)
            UpdatePreviewResult.Success(ingredient, mode, ingredient.quantity, input, updated)
        } catch (_: DomainValidationException) {
            UpdatePreviewResult.Invalid("更新後の在庫数は0以上100以下で指定してください")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            UpdatePreviewResult.Failed
        }
    }

    suspend fun confirmQuantity(preview: UpdatePreviewResult.Success): IngredientMutationResult =
        persist(preview.ingredient.copy(quantity = preview.updatedQuantity))

    suspend fun confirmEdit(current: StoredIngredient, name: String, quantity: String, unit: String): IngredientMutationResult {
        val draft = try { IngredientDraft.create(name, quantity, unit) } catch (error: DomainValidationException) {
            return IngredientMutationResult.Invalid(error.code.fieldName(), error.userMessage())
        }
        return persist(current.copy(name = draft.name, quantity = draft.quantity, unit = draft.unit))
    }

    suspend fun delete(current: StoredIngredient, confirmed: Boolean): IngredientMutationResult {
        if (!confirmed) return IngredientMutationResult.ConfirmationRequired
        return try {
            repository.delete(current)
            IngredientMutationResult.Deleted
        } catch (_: StaleStoredIngredientException) {
            IngredientMutationResult.Conflict
        } catch (_: StoredIngredientNotFoundException) {
            IngredientMutationResult.NotFound
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            IngredientMutationResult.Failed
        }
    }

    private suspend fun persist(ingredient: StoredIngredient): IngredientMutationResult = try {
        repository.update(ingredient)
        IngredientMutationResult.Saved
    } catch (_: DuplicateStoredIngredientException) {
        IngredientMutationResult.DuplicateName
    } catch (_: StaleStoredIngredientException) {
        IngredientMutationResult.Conflict
    } catch (_: StoredIngredientNotFoundException) {
        IngredientMutationResult.NotFound
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        IngredientMutationResult.Failed
    }
}

private fun DomainErrorCode.fieldName() = when (this) {
    DomainErrorCode.NAME_REQUIRED, DomainErrorCode.NAME_TOO_LONG, DomainErrorCode.DUPLICATE_NAME -> "name"
    DomainErrorCode.INVALID_QUANTITY, DomainErrorCode.QUANTITY_OUT_OF_RANGE -> "quantity"
    DomainErrorCode.UNKNOWN_UNIT -> "unit"
    DomainErrorCode.BATCH_EMPTY, DomainErrorCode.BATCH_TOO_LARGE -> "name"
}

private fun DomainValidationException.userMessage() = when (code) {
    DomainErrorCode.NAME_REQUIRED -> "食材名を入力してください"
    DomainErrorCode.NAME_TOO_LONG -> "食材名は30文字以内で入力してください"
    DomainErrorCode.INVALID_QUANTITY -> "在庫数は小数2桁までの数値で入力してください"
    DomainErrorCode.QUANTITY_OUT_OF_RANGE -> "在庫数は0以上100以下で入力してください"
    DomainErrorCode.UNKNOWN_UNIT -> "一覧から単位を選択してください"
    else -> "入力内容を確認してください"
}
