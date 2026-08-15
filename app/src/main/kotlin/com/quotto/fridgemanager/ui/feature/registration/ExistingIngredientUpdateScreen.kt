package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.IngredientName
import com.quotto.fridgemanager.domain.inventory.InventoryQuantity
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.inventory.IngredientMutationResult
import com.quotto.fridgemanager.presentation.inventory.IngredientUpdatePresenter
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
    selectedUnitResult: String? = null,
    onUnitResultConsumed: () -> Unit = {},
    onUnitSelection: (String) -> Unit = {},
) {
    var ingredient by rememberSaveable(ingredientId, stateSaver = storedIngredientStateSaver) {
        mutableStateOf<StoredIngredient?>(null)
    }
    var loaded by rememberSaveable(ingredientId) { mutableStateOf(false) }
    var failed by rememberSaveable(ingredientId) { mutableStateOf(false) }
    var expectedVersion by rememberSaveable(ingredientId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(ingredientId, loaded) {
        if (loaded) return@LaunchedEffect
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
            IngredientUpdateContent(
                it,
                presenter,
                onChanged,
                onImageAnalysis,
                selectedUnitResult,
                onUnitResultConsumed,
                onUnitSelection,
            )
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
    selectedUnitResult: String?,
    onUnitResultConsumed: () -> Unit,
    onUnitSelection: (String) -> Unit,
) {
    var name by rememberSaveable(ingredient.id) { mutableStateOf(ingredient.name.value) }
    var quantity by rememberSaveable(ingredient.id) { mutableStateOf(ingredient.quantity.toString()) }
    var unit by rememberSaveable(ingredient.id) { mutableStateOf(ingredient.unit.symbol) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(selectedUnitResult) {
        selectedUnitResult?.let {
            unit = it
            message = null
            onUnitResultConsumed()
        }
    }

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
        Text("手動で更新")
        Text("在庫数には置換後の値を入力してください")
        OutlinedTextField(name, { name = it }, label = { Text("食材名（必須）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            quantity,
            { quantity = it },
            label = { Text("置換後の在庫数（必須）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "置換後の在庫数、必須"
            },
        )
        UnitSelectionButton(
            selectedSymbol = unit,
            onClick = { onUnitSelection(unit) },
        )
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

private val storedIngredientStateSaver = Saver<StoredIngredient?, List<Any?>>(
    save = { ingredient ->
        ingredient?.let {
            listOf(
                it.id,
                it.name.value,
                it.quantity.toString(),
                it.unit.symbol,
                it.createdAtEpochMillis,
                it.updatedAtEpochMillis,
            )
        } ?: emptyList()
    },
    restore = { values ->
        values.takeIf { it.size == 6 }?.let {
            StoredIngredient(
                id = it[0] as String,
                name = IngredientName.from(it[1] as String),
                quantity = InventoryQuantity.from(it[2] as String),
                unit = InventoryUnit.fromSymbol(it[3] as String),
                createdAtEpochMillis = it[4] as Long,
                updatedAtEpochMillis = it[5] as Long,
            )
        }
    },
)
