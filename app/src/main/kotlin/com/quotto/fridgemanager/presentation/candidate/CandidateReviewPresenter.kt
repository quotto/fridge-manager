package com.quotto.fridgemanager.presentation.candidate

import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.DomainErrorCode
import com.quotto.fridgemanager.domain.inventory.DomainValidationException
import com.quotto.fridgemanager.domain.inventory.DuplicateIngredientException
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.domain.inventory.InventoryCommit
import com.quotto.fridgemanager.domain.inventory.StockUpdate
import com.quotto.fridgemanager.domain.inventory.UpdateMethod
import java.util.UUID
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

data class CandidateReviewItem(
    val id: String,
    val name: String,
    val quantity: String,
    val unit: String,
    val evidence: String,
    val requiresReview: Boolean,
    val included: Boolean = true,
    val existingIngredient: StoredIngredient? = null,
    val nameError: String? = null,
    val quantityError: String? = null,
    val unitError: String? = null,
    val updateMethod: UpdateMethod? = null,
    val resultQuantity: String? = null,
    val updateError: String? = null,
)

data class CandidateReviewState(
    val items: List<CandidateReviewItem> = emptyList(),
    val warnings: List<String> = emptyList(),
    val batchError: String? = null,
    val loadingError: String? = null,
    val isLoading: Boolean = false,
    val isCommitting: Boolean = false,
    val commitError: String? = null,
    val validatedDrafts: List<IngredientDraft> = emptyList(),
) {
    val canProceed: Boolean = !isLoading && !isCommitting && loadingError == null &&
        batchError == null &&
        items.any { it.included } && items.filter { it.included }.all {
            runCatching { IngredientDraft.create(it.name, it.quantity, it.unit) }.isSuccess &&
                it.nameError == null && it.quantityError == null && it.unitError == null &&
                (it.existingIngredient == null || (it.updateMethod != null && it.updateError == null))
        }
}

sealed interface CandidateReviewResult {
    data class Ready(val candidates: List<ReviewedCandidate>) : CandidateReviewResult
    data class Invalid(val state: CandidateReviewState) : CandidateReviewResult
}

data class ReviewedCandidate(
    val draft: IngredientDraft,
    val existingIngredient: StoredIngredient?,
)

/** AI候補を未信頼入力として検証する。永続化は後続の一括確定境界だけが担当する。 */
class CandidateReviewPresenter(private val repository: InventoryRepository) {
    private var inventory: List<StoredIngredient> = emptyList()
    private val loadGeneration = AtomicLong()
    var state = CandidateReviewState()
        private set

    suspend fun load(
        candidates: List<AnalysisCandidate>,
        warnings: List<String> = emptyList(),
    ): CandidateReviewState {
        val request = loadGeneration.incrementAndGet()
        var loadedInventory: List<StoredIngredient> = emptyList()
        val loadingError = try {
            loadedInventory = repository.getAll()
            null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            "現在の在庫を読み込めませんでした"
        }
        if (request != loadGeneration.get()) return state
        inventory = loadedInventory
        state = refreshDuplicates(CandidateReviewState(
            items = candidates.take(InventoryBatch.MAX_ITEMS).map(::toItem),
            warnings = warnings,
            batchError = if (candidates.size > InventoryBatch.MAX_ITEMS) "候補は30件までです" else null,
            loadingError = loadingError,
        ))
        return state
    }

    fun addCandidate(): CandidateReviewState {
        if (state.items.size >= InventoryBatch.MAX_ITEMS) {
            state = state.copy(batchError = "候補は30件までです", validatedDrafts = emptyList())
            return state
        }
        state = state.copy(
            items = state.items + CandidateReviewItem(
                id = UUID.randomUUID().toString(),
                name = "",
                quantity = "",
                unit = "",
                evidence = "MANUAL",
                requiresReview = true,
            ),
            batchError = null,
            validatedDrafts = emptyList(),
        )
        return state
    }

    fun updateCandidate(id: String, name: String, quantity: String, unit: String): CandidateReviewState {
        state = refreshDuplicates(state.copy(items = state.items.map { item ->
            val cleared = item.clearDuplicateError()
            if (item.id != id) cleared else calculateUpdate(validateFields(cleared.copy(
                name = name.trim(),
                quantity = quantity.trim(),
                unit = unit.trim(),
                existingIngredient = matchExisting(name),
            )))
        }, validatedDrafts = emptyList()))
        return state
    }

    fun excludeCandidate(id: String): CandidateReviewState = setIncluded(id, false)
    fun restoreCandidate(id: String): CandidateReviewState = setIncluded(id, true)

    fun selectUpdateMethod(id: String, method: UpdateMethod): CandidateReviewState {
        state = state.copy(
            items = state.items.map { item ->
                if (item.id != id) item else calculateUpdate(item.copy(updateMethod = method))
            },
            validatedDrafts = emptyList(),
        )
        return state
    }

    fun mergeDuplicatesInto(id: String): CandidateReviewState {
        val selected = state.items.firstOrNull { it.id == id } ?: return state
        val normalized = runCatching { IngredientName.from(selected.name).normalizedValue }.getOrNull()
            ?: return state
        state = state.copy(
            items = state.items.map { item ->
                if (item.id != id && item.included &&
                    runCatching { IngredientName.from(item.name).normalizedValue }.getOrNull() == normalized
                ) {
                    item.copy(included = false).clearDuplicateError()
                } else {
                    item.clearDuplicateError()
                }
            },
            batchError = null,
            validatedDrafts = emptyList(),
        )
        return refreshDuplicates(state)
    }

    fun handoff(): CandidateReviewResult {
        val drafts = mutableListOf<IngredientDraft>()
        var invalid = false
        val validated = state.items.map { item ->
            if (!item.included) return@map item.copy(nameError = null, quantityError = null, unitError = null)
            val checked = calculateUpdate(validateFields(item))
            if (checked.nameError != null || checked.quantityError != null || checked.unitError != null ||
                (checked.existingIngredient != null && (checked.updateMethod == null || checked.updateError != null))
            ) {
                invalid = true
                return@map checked
            }
            try {
                drafts += if (checked.existingIngredient == null) {
                    IngredientDraft.create(checked.name, checked.quantity, checked.unit)
                } else {
                    IngredientDraft.create(
                        checked.name,
                        checked.resultQuantity ?: checked.quantity,
                        checked.unit,
                    )
                }
                checked
            } catch (error: DomainValidationException) {
                invalid = true
                item.withError(error)
            }
        }.toMutableList()
        val batchError = when {
            drafts.isEmpty() -> "登録する候補を1件以上選択してください"
            drafts.size > InventoryBatch.MAX_ITEMS -> "候補は30件までです"
            else -> state.batchError
        }
        if (!invalid && batchError == null) {
            try {
                InventoryBatch.create(drafts)
            } catch (duplicates: DuplicateIngredientException) {
                invalid = true
                val includedItems = validated.withIndex().filter { it.value.included }
                val duplicateIndices = duplicates.duplicateGroups.flatMap { it.indices }.toSet()
                duplicateIndices.forEach { includedIndex ->
                    val stateIndex = includedItems.getOrNull(includedIndex)?.index ?: return@forEach
                    val item = validated[stateIndex]
                    validated[stateIndex] = item.copy(
                        nameError = DUPLICATE_CANDIDATE_MESSAGE,
                    )
                }
            }
        }
        state = state.copy(
            items = validated,
            batchError = batchError,
            validatedDrafts = if (invalid || batchError != null || state.loadingError != null) emptyList() else drafts.toList(),
        )
        if (invalid || batchError != null || state.loadingError != null) return CandidateReviewResult.Invalid(state)
        val includedItems = state.items.filter { it.included }
        return CandidateReviewResult.Ready(drafts.mapIndexed { index, draft ->
            ReviewedCandidate(draft, includedItems[index].existingIngredient)
        })
    }

    suspend fun commit(candidates: List<ReviewedCandidate>) {
        repository.commit(
            InventoryCommit.create(
                newItems = candidates.filter { it.existingIngredient == null }.map { it.draft },
                updates = candidates.mapNotNull { candidate ->
                    candidate.existingIngredient?.copy(
                        name = candidate.draft.name,
                        quantity = candidate.draft.quantity,
                        unit = candidate.draft.unit,
                    )
                },
            ),
        )
    }

    private fun toItem(candidate: AnalysisCandidate): CandidateReviewItem {
        val name = candidate.name.orEmpty().trim()
        return calculateUpdate(validateFields(CandidateReviewItem(
            id = UUID.randomUUID().toString(),
            name = name,
            quantity = candidate.quantity.orEmpty().trim(),
            unit = candidate.unit.orEmpty().trim(),
            evidence = candidate.evidence,
            requiresReview = candidate.requiresReview,
            existingIngredient = matchExisting(name),
        )))
    }

    private fun setIncluded(id: String, included: Boolean): CandidateReviewState {
        state = state.copy(items = state.items.map {
            val cleared = it.clearDuplicateError()
            if (it.id == id) cleared.copy(included = included) else cleared
        }, batchError = null, validatedDrafts = emptyList())
        state = refreshDuplicates(state)
        return state
    }

    private fun matchExisting(rawName: String): StoredIngredient? {
        val normalized = try {
            IngredientName.from(rawName).normalizedValue
        } catch (_: DomainValidationException) {
            return null
        }
        return inventory.firstOrNull { it.name.normalizedValue == normalized }
    }

    private fun validateFields(item: CandidateReviewItem): CandidateReviewItem = item.copy(
        nameError = validationError { IngredientName.from(item.name) },
        quantityError = validationError { com.quotto.fridgemanager.domain.inventory.InventoryQuantity.from(item.quantity) },
        unitError = validationError { com.quotto.fridgemanager.domain.inventory.InventoryUnit.fromSymbol(item.unit) },
    )

    private fun calculateUpdate(item: CandidateReviewItem): CandidateReviewItem {
        val existing = item.existingIngredient ?: return item.copy(
            updateMethod = null,
            resultQuantity = null,
            updateError = null,
        )
        val method = item.updateMethod ?: return item.copy(
            resultQuantity = null,
            updateError = "増加・減少・置換を選択してください",
        )
        return try {
            val draft = IngredientDraft.create(item.name, item.quantity, item.unit)
            if (draft.unit != existing.unit) {
                item.copy(resultQuantity = null, updateError = "登録済み食材と同じ単位を選択してください")
            } else {
                item.copy(
                    resultQuantity = StockUpdate.apply(existing.quantity, draft.quantity, method).toString(),
                    updateError = null,
                )
            }
        } catch (_: DomainValidationException) {
            item.copy(resultQuantity = null, updateError = null)
        }
    }

    private fun refreshDuplicates(source: CandidateReviewState): CandidateReviewState {
        val groups = source.items.withIndex()
            .filter { it.value.included }
            .mapNotNull { indexed ->
                runCatching { IngredientName.from(indexed.value.name).normalizedValue }
                    .getOrNull()?.let { it to indexed.index }
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        val duplicateIndices = groups.values.flatten().toSet()
        return source.copy(items = source.items.mapIndexed { index, item ->
            if (index in duplicateIndices) item.copy(nameError = DUPLICATE_CANDIDATE_MESSAGE)
            else item.clearDuplicateError()
        })
    }
}

private const val DUPLICATE_CANDIDATE_MESSAGE = "同じ食材名の候補を統合または除外してください"

private fun CandidateReviewItem.clearDuplicateError(): CandidateReviewItem =
    if (nameError == DUPLICATE_CANDIDATE_MESSAGE) copy(nameError = null) else this

private inline fun validationError(block: () -> Any): String? = try {
    block()
    null
} catch (error: DomainValidationException) {
    when (error.code) {
        DomainErrorCode.NAME_REQUIRED -> "食材名を入力してください"
        DomainErrorCode.NAME_TOO_LONG -> "食材名は30文字以内で入力してください"
        DomainErrorCode.INVALID_QUANTITY -> "在庫数は小数2桁までの数値で入力してください"
        DomainErrorCode.QUANTITY_OUT_OF_RANGE -> "在庫数は0以上100以下で入力してください"
        DomainErrorCode.UNKNOWN_UNIT -> "一覧から単位を選択してください"
        else -> "入力内容を確認してください"
    }
}

private fun CandidateReviewItem.withError(error: DomainValidationException): CandidateReviewItem = when (error.code) {
    DomainErrorCode.NAME_REQUIRED -> copy(nameError = "食材名を入力してください")
    DomainErrorCode.NAME_TOO_LONG -> copy(nameError = "食材名は30文字以内で入力してください")
    DomainErrorCode.INVALID_QUANTITY -> copy(quantityError = "在庫数は小数2桁までの数値で入力してください")
    DomainErrorCode.QUANTITY_OUT_OF_RANGE -> copy(quantityError = "在庫数は0以上100以下で入力してください")
    DomainErrorCode.UNKNOWN_UNIT -> copy(unitError = "一覧から単位を選択してください")
    else -> copy(nameError = "入力内容を確認してください")
}
