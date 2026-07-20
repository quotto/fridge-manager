package com.quotto.fridgemanager.presentation.inventory

import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException
import com.quotto.fridgemanager.domain.inventory.StockUpdate
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException
import com.quotto.fridgemanager.domain.inventory.UpdateMethod
import kotlinx.coroutines.CancellationException

data class AiUpdateCandidateState(
    val ingredient: StoredIngredient,
    val currentQuantity: InventoryQuantity,
    val estimatedAbsoluteQuantity: String,
    val unit: InventoryUnit,
    val evidence: String,
    val requiresReview: Boolean,
    val unitMatchesCurrent: Boolean,
    val method: UpdateMethod? = null,
    val resultQuantity: InventoryQuantity? = null,
    val errorMessage: String? = null,
) {
    val canConfirm: Boolean = unitMatchesCurrent && method != null && resultQuantity != null && errorMessage == null
}

sealed interface AiUpdateConfirmationResult {
    data object Saved : AiUpdateConfirmationResult
    data object Invalid : AiUpdateConfirmationResult
    data object Conflict : AiUpdateConfirmationResult
    data object NotFound : AiUpdateConfirmationResult
    data object Failed : AiUpdateConfirmationResult
}

/** AIの絶対値を適用方法未選択のまま受け取り、明示確定後だけ在庫へ反映する。 */
class AiUpdateCandidatePresenter(private val repository: InventoryRepository) {
    fun prepare(ingredient: StoredIngredient, candidate: AnalysisCandidate): AiUpdateCandidateState {
        val rawQuantity = candidate.quantity.orEmpty()
        val parsedUnit = candidate.unit?.let { symbol ->
            runCatching { InventoryUnit.fromSymbol(symbol) }.getOrNull()
        }
        val unit = parsedUnit ?: ingredient.unit
        val unitError = if (parsedUnit != ingredient.unit) {
            "AI推定値の単位を現在の在庫と一致させてください"
        } else {
            null
        }
        val quantityError = runCatching { InventoryQuantity.from(rawQuantity) }.exceptionOrNull()?.let {
            "AI推定値は0以上100以下、小数2桁までで入力してください"
        }
        return AiUpdateCandidateState(
            ingredient = ingredient,
            currentQuantity = ingredient.quantity,
            estimatedAbsoluteQuantity = rawQuantity,
            unit = unit,
            evidence = candidate.evidence,
            requiresReview = candidate.requiresReview,
            unitMatchesCurrent = unitError == null,
            errorMessage = unitError ?: quantityError,
        )
    }

    fun selectMethod(state: AiUpdateCandidateState, method: UpdateMethod): AiUpdateCandidateState =
        calculate(state.copy(method = method))

    fun editEstimatedAbsoluteQuantity(state: AiUpdateCandidateState, rawValue: String): AiUpdateCandidateState =
        calculate(state.copy(estimatedAbsoluteQuantity = rawValue.trim()))

    suspend fun confirm(state: AiUpdateCandidateState): AiUpdateConfirmationResult {
        if (!state.canConfirm) return AiUpdateConfirmationResult.Invalid
        return try {
            repository.update(state.ingredient.copy(quantity = checkNotNull(state.resultQuantity)))
            AiUpdateConfirmationResult.Saved
        } catch (_: StaleStoredIngredientException) {
            AiUpdateConfirmationResult.Conflict
        } catch (_: StoredIngredientNotFoundException) {
            AiUpdateConfirmationResult.NotFound
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AiUpdateConfirmationResult.Failed
        }
    }

    private fun calculate(state: AiUpdateCandidateState): AiUpdateCandidateState {
        val method = state.method ?: return state.copy(resultQuantity = null)
        if (!state.unitMatchesCurrent) return state.copy(
            resultQuantity = null,
            errorMessage = "AI推定値の単位を現在の在庫と一致させてください",
        )
        return try {
            val absolute = InventoryQuantity.from(state.estimatedAbsoluteQuantity)
            state.copy(
                resultQuantity = StockUpdate.apply(state.currentQuantity, absolute, method),
                errorMessage = null,
            )
        } catch (_: DomainValidationException) {
            state.copy(
                resultQuantity = null,
                errorMessage = "更新後の在庫数は0以上100以下、AI推定値は小数2桁までで入力してください",
            )
        }
    }
}
