package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.IngredientMutationResult
import com.quotto.fridgemanager.presentation.inventory.IngredientUpdatePresenter
import com.quotto.fridgemanager.presentation.inventory.QuantityUpdateMode
import com.quotto.fridgemanager.presentation.inventory.UpdatePreviewResult
import com.quotto.fridgemanager.ui.component.ScreenHeader
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun ExistingIngredientUpdateScreen(
    ingredientId: String,
    presenter: IngredientUpdatePresenter,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onImageAnalysis: (String) -> Unit = {},
) {
    var ingredient by remember(ingredientId) { mutableStateOf<StoredIngredient?>(null) }
    var loaded by remember(ingredientId) { mutableStateOf(false) }
    var failed by remember(ingredientId) { mutableStateOf(false) }
    var expectedVersion by rememberSaveable(ingredientId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(ingredientId) {
        try {
            ingredient = presenter.load(ingredientId)?.let { current ->
                val sessionVersion = expectedVersion ?: current.updatedAtEpochMillis.also { expectedVersion = it }
                current.copy(updatedAtEpochMillis = sessionVersion)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
        loaded = true
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "在庫を更新", onBack = onBack)
        ingredient?.let {
            IngredientUpdateContent(it, presenter, onChanged, onImageAnalysis)
        } ?: Text(
            if (failed) "在庫を読み込めませんでした。戻って再試行してください"
            else if (loaded) "対象の在庫が見つかりません。戻って選び直してください" else "在庫を読み込んでいます",
            modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun IngredientUpdateContent(
    ingredient: StoredIngredient,
    presenter: IngredientUpdatePresenter,
    onChanged: () -> Unit,
    onImageAnalysis: (String) -> Unit,
) {
    var name by rememberSaveable(ingredient.id) { mutableStateOf(ingredient.name.value) }
    var quantity by rememberSaveable(ingredient.id) { mutableStateOf(ingredient.quantity.toString()) }
    var unit by rememberSaveable(ingredient.id) { mutableStateOf(ingredient.unit.symbol) }
    var delta by rememberSaveable(ingredient.id) { mutableStateOf("") }
    var mode by rememberSaveable(ingredient.id) { mutableStateOf(QuantityUpdateMode.INCREASE) }
    var preview by remember { mutableStateOf<UpdatePreviewResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun resultMessage(result: IngredientMutationResult): String? = when (result) {
        IngredientMutationResult.Saved, IngredientMutationResult.Deleted -> null
        IngredientMutationResult.DuplicateName -> "同じ食材名が既に登録されています"
        IngredientMutationResult.Conflict -> "他の変更を検出しました。戻って再読み込みしてください"
        IngredientMutationResult.NotFound -> "対象の在庫が見つかりません"
        is IngredientMutationResult.Invalid -> result.message
        IngredientMutationResult.ConfirmationRequired -> "削除の確認が必要です"
        IngredientMutationResult.Failed -> "保存できませんでした。入力内容を保ったまま再試行できます"
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("現在値: ${ingredient.quantity} ${ingredient.unit.symbol}")
        OutlinedButton(onClick = { onImageAnalysis(ingredient.id) }, modifier = Modifier.fillMaxWidth()) {
            Text("画像から数量を更新")
        }
        Text("食材情報を編集")
        OutlinedTextField(name, { name = it }, label = { Text("食材名（必須）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(quantity, { quantity = it }, label = { Text("在庫数（必須）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Column {
            Text("単位（必須）")
            OutlinedButton(onClick = { unitMenu = true }) { Text(unit) }
            DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                InventoryUnit.entries.forEach { candidate ->
                    DropdownMenuItem(text = { Text(candidate.symbol) }, onClick = {
                        unit = candidate.symbol
                        message = null
                        unitMenu = false
                    })
                }
            }
        }
        Button(
            enabled = !saving,
            onClick = {
                saving = true
                scope.launch {
                    val result = presenter.confirmEdit(ingredient, name, quantity, unit)
                    saving = false
                    if (result == IngredientMutationResult.Saved) onChanged() else message = resultMessage(result)
                }
            }, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "保存中" else "編集内容を確定") }

        Text("数量を更新")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuantityUpdateMode.entries.forEach { candidate ->
                Row(
                    Modifier.selectable(selected = mode == candidate, role = Role.RadioButton, onClick = {
                        mode = candidate
                        preview = null
                    }).padding(8.dp),
                ) {
                    RadioButton(selected = mode == candidate, onClick = null)
                    Text(candidate.label, Modifier.padding(start = 4.dp))
                }
            }
        }
        OutlinedTextField(delta, { delta = it; preview = null }, label = { Text("入力値") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            scope.launch {
                preview = presenter.preview(ingredient.id, mode.label, delta)
                message = when (val result = preview) {
                    is UpdatePreviewResult.Success -> null
                    is UpdatePreviewResult.Invalid -> result.message
                    UpdatePreviewResult.NotFound -> "対象の在庫が見つかりません。戻って選び直してください"
                    UpdatePreviewResult.Failed -> "在庫を読み込めませんでした。入力値を保ったまま再試行できます"
                    null -> null
                }
            }
        }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("更新後の値を確認") }
        (preview as? UpdatePreviewResult.Success)?.let { confirmed ->
            Text("現在値 ${confirmed.currentQuantity}、${confirmed.mode.label} ${confirmed.inputQuantity}、更新後 ${confirmed.updatedQuantity}")
            Button(onClick = {
                saving = true
                scope.launch {
                    val result = presenter.confirmQuantity(confirmed)
                    saving = false
                    if (result == IngredientMutationResult.Saved) onChanged() else message = resultMessage(result)
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("数量更新を確定") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        TextButton(onClick = { showDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("この食材を削除") }
    }

    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("${ingredient.name.value}を削除しますか？") },
        text = { Text("削除後は取り消し・復元できません。") },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("キャンセル") } },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val result = presenter.delete(ingredient, confirmed = true)
                    saving = false
                    showDelete = false
                    if (result == IngredientMutationResult.Deleted) onChanged() else message = resultMessage(result)
                }
            }) { Text("削除を確定") }
        },
    )
}
