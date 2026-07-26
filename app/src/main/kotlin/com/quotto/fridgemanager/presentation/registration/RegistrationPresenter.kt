package com.quotto.fridgemanager.presentation.registration

import com.quotto.fridgemanager.domain.inventory.DuplicateStoredIngredientException
import com.quotto.fridgemanager.domain.inventory.DomainErrorCode
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import kotlinx.coroutines.CancellationException

enum class RegistrationField { NAME, QUANTITY, UNIT }

data class IngredientSuggestion(
    val ingredient: StoredIngredient,
    val isExactMatch: Boolean,
)

sealed interface SuggestionResult {
    data class Success(val suggestions: List<IngredientSuggestion>) : SuggestionResult
    data object Failed : SuggestionResult
}

sealed interface ExistingIngredientResult {
    data class Found(val ingredient: StoredIngredient) : ExistingIngredientResult
    data object NotFound : ExistingIngredientResult
    data object Failed : ExistingIngredientResult
}

sealed interface RegistrationResult {
    data object Saved : RegistrationResult
    data class ExistingIngredient(val ingredient: StoredIngredient) : RegistrationResult
    data class Invalid(val field: RegistrationField, val message: String) : RegistrationResult
    data object Failed : RegistrationResult
}

data class RegistrationFormState(
    val name: String = "",
    val quantity: String = "",
    val selectedUnitSymbol: String = "個",
    val suggestions: List<IngredientSuggestion> = emptyList(),
    val errorField: RegistrationField? = null,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
) {
    val exactMatch: StoredIngredient? = suggestions.firstOrNull { it.isExactMatch }?.ingredient
    val canSubmit: Boolean = name.isNotBlank() && quantity.isNotBlank() && exactMatch == null && !isSaving
}

class RegistrationPresenter(private val repository: InventoryRepository) {
    suspend fun existingIngredient(id: String): ExistingIngredientResult = try {
        repository.getAll().firstOrNull { it.id == id }
            ?.let(ExistingIngredientResult::Found)
            ?: ExistingIngredientResult.NotFound
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        ExistingIngredientResult.Failed
    }

    suspend fun suggestions(rawName: String): SuggestionResult {
        val name = try {
            IngredientName.from(rawName)
        } catch (_: DomainValidationException) {
            return SuggestionResult.Success(emptyList())
        }
        return try {
            SuggestionResult.Success(
                repository.searchByName(name.normalizedValue)
                    .map { IngredientSuggestion(it, it.name.normalizedValue == name.normalizedValue) }
                    .sortedWith(compareByDescending<IngredientSuggestion> { it.isExactMatch }
                        .thenBy { it.ingredient.name.normalizedValue }
                        .thenBy { it.ingredient.id }),
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SuggestionResult.Failed
        }
    }

    suspend fun submit(name: String, quantity: String, unit: String): RegistrationResult {
        val draft = try {
            IngredientDraft.create(name, quantity, unit)
        } catch (error: DomainValidationException) {
            return RegistrationResult.Invalid(error.code.toField(), error.code.toUserMessage())
        }

        return try {
            findExact(draft.name)?.let { return RegistrationResult.ExistingIngredient(it) }
            repository.saveBatch(InventoryBatch.create(listOf(draft)))
            RegistrationResult.Saved
        } catch (_: DuplicateStoredIngredientException) {
            // 事前検索後の競合も新規登録失敗ではなく既存在庫の更新導線へ変換する。
            findExact(draft.name)?.let(RegistrationResult::ExistingIngredient) ?: RegistrationResult.Failed
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RegistrationResult.Failed
        }
    }

    private suspend fun findExact(name: IngredientName): StoredIngredient? =
        repository.searchByName(name.normalizedValue)
            .firstOrNull { it.name.normalizedValue == name.normalizedValue }
}

private fun DomainErrorCode.toField(): RegistrationField = when (this) {
    DomainErrorCode.NAME_REQUIRED, DomainErrorCode.NAME_TOO_LONG, DomainErrorCode.DUPLICATE_NAME -> RegistrationField.NAME
    DomainErrorCode.INVALID_QUANTITY, DomainErrorCode.QUANTITY_OUT_OF_RANGE -> RegistrationField.QUANTITY
    DomainErrorCode.UNKNOWN_UNIT -> RegistrationField.UNIT
    DomainErrorCode.BATCH_EMPTY, DomainErrorCode.BATCH_TOO_LARGE -> RegistrationField.NAME
}

private fun DomainErrorCode.toUserMessage(): String = when (this) {
    DomainErrorCode.NAME_REQUIRED -> "食材名を入力してください"
    DomainErrorCode.NAME_TOO_LONG -> "食材名は30文字以内で入力してください"
    DomainErrorCode.INVALID_QUANTITY -> "在庫数は小数2桁までの数値で入力してください"
    DomainErrorCode.QUANTITY_OUT_OF_RANGE -> "在庫数は0以上100以下で入力してください"
    DomainErrorCode.UNKNOWN_UNIT -> "一覧から単位を選択してください"
    else -> "入力内容を確認してください"
}
