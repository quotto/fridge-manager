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

class CandidateReviewViewModel(
    private val presenter: CandidateReviewPresenter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CandidateReviewState())
    val state = mutableState.asStateFlow()
    private var loadedRequestId: String? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L

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

    fun add() { mutableState.value = presenter.addCandidate() }
    fun update(id: String, name: String, quantity: String, unit: String) {
        mutableState.value = presenter.updateCandidate(id, name, quantity, unit)
    }
    fun setIncluded(id: String, included: Boolean) {
        mutableState.value = if (included) presenter.restoreCandidate(id) else presenter.excludeCandidate(id)
    }
    fun handoff(onReady: (List<ReviewedCandidate>) -> Unit) {
        when (val result = presenter.handoff()) {
            is CandidateReviewResult.Invalid -> mutableState.value = result.state
            is CandidateReviewResult.Ready -> {
                mutableState.value = presenter.state
                onReady(result.candidates)
            }
        }
    }

    class Factory(private val presenter: CandidateReviewPresenter) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CandidateReviewViewModel(presenter) as T
    }
}
