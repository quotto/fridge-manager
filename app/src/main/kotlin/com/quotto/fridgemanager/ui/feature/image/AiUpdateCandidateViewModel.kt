package com.quotto.fridgemanager.ui.feature.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.UpdateMethod
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidatePresenter
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidateState
import com.quotto.fridgemanager.presentation.inventory.AiUpdateConfirmationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AiUpdateCandidateUiState(
    val candidate: AiUpdateCandidateState? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

class AiUpdateCandidateViewModel(
    private val presenter: AiUpdateCandidatePresenter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiUpdateCandidateUiState())
    val state = mutableState.asStateFlow()
    private var loadedKey: String? = null

    fun load(requestId: String, ingredient: StoredIngredient, candidate: AnalysisCandidate) {
        val key = "$requestId:${ingredient.id}:${ingredient.updatedAtEpochMillis}"
        if (loadedKey == key) return
        loadedKey = key
        mutableState.value = try {
            AiUpdateCandidateUiState(candidate = presenter.prepare(ingredient, candidate))
        } catch (_: Exception) {
            AiUpdateCandidateUiState(message = "更新対象を読み込めませんでした")
        }
    }

    fun selectMethod(method: UpdateMethod) {
        if (mutableState.value.saving) return
        val current = mutableState.value.candidate ?: return
        mutableState.value = mutableState.value.copy(candidate = presenter.selectMethod(current, method), message = null)
    }

    fun editEstimatedQuantity(value: String) {
        if (mutableState.value.saving) return
        val current = mutableState.value.candidate ?: return
        mutableState.value = mutableState.value.copy(
            candidate = presenter.editEstimatedAbsoluteQuantity(current, value),
            message = null,
        )
    }

    fun confirm(onSaved: () -> Unit) {
        val current = mutableState.value.candidate ?: return
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true, message = null)
        viewModelScope.launch {
            when (presenter.confirm(current)) {
                AiUpdateConfirmationResult.Saved -> {
                    mutableState.value = mutableState.value.copy(saving = false)
                    onSaved()
                }
                AiUpdateConfirmationResult.Invalid -> mutableState.value = mutableState.value.copy(
                    saving = false,
                    message = "推定値と適用方法を確認してください",
                )
                AiUpdateConfirmationResult.Conflict -> mutableState.value = mutableState.value.copy(
                    saving = false,
                    message = "他の変更を検出しました。戻って再読み込みしてください",
                )
                AiUpdateConfirmationResult.NotFound -> mutableState.value = mutableState.value.copy(
                    saving = false,
                    message = "更新対象が見つかりません",
                )
                AiUpdateConfirmationResult.Failed -> mutableState.value = mutableState.value.copy(
                    saving = false,
                    message = "更新できませんでした。入力内容を保ったまま再試行できます",
                )
            }
        }
    }

    class Factory(private val presenter: AiUpdateCandidatePresenter) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AiUpdateCandidateViewModel(presenter) as T
    }
}
