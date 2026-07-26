package com.quotto.fridgemanager.ui.feature.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewResult
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewState
import com.quotto.fridgemanager.presentation.candidate.ReviewedCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class CandidateReviewViewModel(
    private val presenter: CandidateReviewPresenter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CandidateReviewState())
    val state = mutableState.asStateFlow()
    private var loadedRequestId: String? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var handoffStarted = false
    private var commitJob: Job? = null

    fun load(result: AnalysisApiResult.Success) {
        if (loadedRequestId == result.requestId) return
        loadedRequestId = result.requestId
        val request = ++loadGeneration
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, loadingError = null)
        loadJob = viewModelScope.launch {
            val loaded = presenter.load(result.candidates, result.warnings)
            if (request == loadGeneration) mutableState.value = loaded
        }
    }

    fun add() {
        if (mutableState.value.isCommitting) return
        mutableState.value = presenter.addCandidate()
    }
    fun update(id: String, name: String, quantity: String, unit: String) {
        if (!mutableState.value.isCommitting) {
            mutableState.value = presenter.updateCandidate(id, name, quantity, unit)
        }
    }
    fun setIncluded(id: String, included: Boolean) {
        if (!mutableState.value.isCommitting) {
            mutableState.value = if (included) presenter.restoreCandidate(id) else presenter.excludeCandidate(id)
        }
    }
    fun selectUpdateMethod(id: String, method: com.quotto.fridgemanager.domain.inventory.UpdateMethod) {
        if (!mutableState.value.isCommitting) {
            mutableState.value = presenter.selectUpdateMethod(id, method)
        }
    }
    fun mergeDuplicatesInto(id: String) {
        if (!mutableState.value.isCommitting) mutableState.value = presenter.mergeDuplicatesInto(id)
    }
    fun handoff(onReady: (List<ReviewedCandidate>) -> Unit) {
        if (handoffStarted) return
        when (val result = presenter.handoff()) {
            is CandidateReviewResult.Invalid -> mutableState.value = result.state
            is CandidateReviewResult.Ready -> {
                handoffStarted = true
                mutableState.value = presenter.state
                onReady(result.candidates)
            }
        }
    }

    fun confirm(onSaved: (List<ReviewedCandidate>) -> Unit) {
        if (commitJob?.isActive == true || mutableState.value.isCommitting) return
        when (val result = presenter.handoff()) {
            is CandidateReviewResult.Invalid -> mutableState.value = result.state
            is CandidateReviewResult.Ready -> {
                mutableState.value = presenter.state.copy(isCommitting = true, commitError = null)
                commitJob = viewModelScope.launch {
                    try {
                        presenter.commit(result.candidates)
                        mutableState.value = presenter.state.copy(isCommitting = false, commitError = null)
                        onSaved(result.candidates)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.value = presenter.state.copy(
                            isCommitting = false,
                            commitError = when (error) {
                                is com.quotto.fridgemanager.domain.inventory.StaleStoredIngredientException,
                                is com.quotto.fridgemanager.domain.inventory.StoredIngredientNotFoundException ->
                                    "在庫が変更されました。候補を再確認してください"
                                is com.quotto.fridgemanager.domain.inventory.DuplicateStoredIngredientException ->
                                    "同じ名前の在庫があります。候補を修正してください"
                                else -> "在庫へ反映できませんでした。もう一度お試しください"
                            },
                        )
                    }
                }
            }
        }
    }

    class Factory(private val presenter: CandidateReviewPresenter) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CandidateReviewViewModel(presenter) as T
    }
}
