package com.quotto.fridgemanager.ui.feature.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.UpdateMethod
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidatePresenter
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun AiUpdateCandidateScreen(
    requestId: String,
    ingredient: StoredIngredient,
    candidate: AnalysisCandidate,
    presenter: AiUpdateCandidatePresenter,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val update: AiUpdateCandidateViewModel = viewModel(factory = remember(presenter) {
        AiUpdateCandidateViewModel.Factory(presenter)
    })
    val uiState by update.state.collectAsState()
    LaunchedEffect(requestId, ingredient.id, ingredient.updatedAtEpochMillis) { update.load(requestId, ingredient, candidate) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "AI更新候補の確認", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.loading) Text("更新対象を読み込んでいます")
            uiState.candidate?.let { state ->
                Text("対象: ${state.ingredient.name.value}")
                Text("現在値: ${state.currentQuantity} ${state.ingredient.unit.symbol}")
                Text("AI推定の絶対値です。適用方法は自動選択されません")
                Text("根拠: ${evidenceLabel(state.evidence)}")
                if (state.requiresReview) Text("要確認")
                OutlinedTextField(
                    value = state.estimatedAbsoluteQuantity,
                    onValueChange = update::editEstimatedQuantity,
                    label = { Text("AI推定絶対値") },
                    suffix = { Text(state.unit.symbol) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !uiState.saving,
                    isError = state.errorMessage != null,
                    supportingText = { Text(state.errorMessage ?: "0〜100、小数2桁まで") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("適用方法（必須）")
                UpdateMethod.entries.forEach { method ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = state.method == method,
                            enabled = !uiState.saving,
                            role = Role.RadioButton,
                            onClick = { update.selectMethod(method) },
                        ).padding(8.dp).semantics {
                            contentDescription = "適用方法 ${method.label()}"
                        },
                    ) {
                        RadioButton(selected = state.method == method, onClick = null)
                        Text(method.label(), Modifier.padding(start = 8.dp))
                    }
                }
                Text("更新結果: ${state.resultQuantity?.let { "$it ${state.unit.symbol}" } ?: "未確定"}")
                Button(
                    onClick = { update.confirm(onSaved) },
                    enabled = state.canConfirm && !uiState.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (uiState.saving) "更新中" else "この内容で更新を確定")
                }
            }
            uiState.message?.let { Text(it) }
        }
    }
}

private fun UpdateMethod.label(): String = when (this) {
    UpdateMethod.INCREASE -> "増加"
    UpdateMethod.DECREASE -> "減少"
    UpdateMethod.REPLACE -> "置換"
}

private fun evidenceLabel(value: String): String = when (value) {
    "VISIBLE_COUNT" -> "画像内の個数"
    "PACKAGE_LABEL" -> "パッケージ表示"
    "VISUAL_ESTIMATE" -> "画像からの推定"
    else -> "不明"
}
