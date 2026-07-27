package com.quotto.fridgemanager.ui.feature.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult
import com.quotto.fridgemanager.domain.inventory.UpdateMethod
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewItem
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import com.quotto.fridgemanager.presentation.candidate.ReviewedCandidate
import com.quotto.fridgemanager.ui.component.ScreenHeader
import com.quotto.fridgemanager.ui.component.UnitSelectionField

@Composable
fun CandidateReviewScreen(
    result: AnalysisApiResult.Success,
    presenter: CandidateReviewPresenter,
    onValidated: (List<ReviewedCandidate>) -> Unit,
) {
    val review: CandidateReviewViewModel = viewModel(factory = remember(presenter) {
        CandidateReviewViewModel.Factory(presenter)
    })
    val state by review.state.collectAsState()
    LaunchedEffect(result.requestId) { review.load(result) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "AI候補の確認")
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("AIが提案した候補です。内容を確認し、不明な項目を入力してください")
                state.loadingError?.let { Text(it) }
                if (state.isLoading) Text("現在の在庫と候補を照合しています")
                state.batchError?.let { Text(it) }
                state.commitError?.let { Text(it) }
                if (state.isCommitting) Text("在庫へ反映しています")
                state.warnings.forEach { Text("警告: ${warningLabel(it)}") }
            }
            items(state.items, key = { it.id }) { item ->
                CandidateCard(
                    item = item,
                    onUpdate = { name, quantity, unit -> review.update(item.id, name, quantity, unit) },
                    onIncludedChange = { review.setIncluded(item.id, it) },
                    onMerge = { review.mergeDuplicatesInto(item.id) },
                    onUpdateMethod = { review.selectUpdateMethod(item.id, it) },
                    enabled = !state.isCommitting,
                )
            }
            item {
                OutlinedButton(onClick = review::add, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text("候補を追加する")
                }
                Button(
                    onClick = { review.confirm(onValidated) },
                    enabled = state.canProceed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isCommitting) "在庫へ反映中" else "在庫に一括反映する")
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    item: CandidateReviewItem,
    onUpdate: (String, String, String) -> Unit,
    onIncludedChange: (Boolean) -> Unit,
    onMerge: () -> Unit,
    onUpdateMethod: (UpdateMethod) -> Unit,
    enabled: Boolean,
) {
    Card(Modifier.fillMaxWidth().semantics {
        contentDescription = if (item.included) "AI候補" else "除外したAI候補"
    }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (item.existingIngredient == null) "新規候補" else "登録済み食材の更新候補")
                OutlinedButton(onClick = { onIncludedChange(!item.included) }, enabled = enabled) {
                    Text(if (item.included) "除外する" else "候補に戻す")
                }
            }
            Text("根拠: ${evidenceLabel(item.evidence)}")
            if (item.requiresReview) Text("要確認")
            item.existingIngredient?.let {
                Text("現在の在庫: ${it.quantity} ${it.unit.symbol}")
            }
            if (item.included) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { onUpdate(it, item.quantity, item.unit) },
                    label = { Text("食材名") },
                    supportingText = { Text(item.nameError ?: if (item.name.isBlank()) "未入力" else "30文字以内") },
                    isError = item.nameError != null || item.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = item.quantity,
                    onValueChange = { onUpdate(item.name, it, item.unit) },
                    label = { Text("推定数量") },
                    supportingText = { Text(item.quantityError ?: if (item.quantity.isBlank()) "未入力" else "0〜100、小数2桁まで") },
                    isError = item.quantityError != null || item.quantity.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                UnitSelectionField(item.unit) { onUpdate(item.name, item.quantity, it) }
                item.unitError?.let { Text(it) }
                if (item.nameError?.contains("統合または除外") == true) {
                    OutlinedButton(onClick = onMerge, enabled = enabled) {
                        Text("この候補に統合する")
                    }
                }
                item.existingIngredient?.let {
                    Text("反映方法を選択してください")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        UpdateMethod.entries.forEach { method ->
                            OutlinedButton(
                                onClick = { onUpdateMethod(method) },
                                enabled = enabled,
                            ) {
                                Text(updateMethodLabel(method))
                            }
                        }
                    }
                    item.resultQuantity?.let { result -> Text("反映後の在庫: $result ${item.unit}") }
                    item.updateError?.let { error -> Text(error) }
                }
            }
        }
    }
}

private fun updateMethodLabel(method: UpdateMethod): String = when (method) {
    UpdateMethod.INCREASE -> "増加"
    UpdateMethod.DECREASE -> "減少"
    UpdateMethod.REPLACE -> "置換"
}

private fun evidenceLabel(value: String): String = when (value) {
    "VISIBLE_COUNT" -> "画像内の個数"
    "PACKAGE_LABEL" -> "パッケージ表示"
    "VISUAL_ESTIMATE" -> "画像からの推定"
    "UNKNOWN", "不明" -> "不明"
    "MANUAL" -> "手動追加"
    else -> "不明"
}

private fun warningLabel(value: String): String = when (value) {
    "MAX_CANDIDATES_REACHED" -> "候補が30件に達したため、画像内の一部が含まれない可能性があります"
    "LOW_CONFIDENCE" -> "推定の確度が低い候補があります"
    else -> "解析結果に注意事項があります"
}
