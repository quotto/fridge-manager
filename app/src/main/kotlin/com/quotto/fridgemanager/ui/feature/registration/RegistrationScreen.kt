package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.presentation.registration.RegistrationField
import com.quotto.fridgemanager.presentation.registration.RegistrationFormState
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter
import com.quotto.fridgemanager.presentation.registration.RegistrationResult
import com.quotto.fridgemanager.presentation.registration.SuggestionResult
import com.quotto.fridgemanager.ui.component.ScreenHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RegistrationScreen(
    presenter: RegistrationPresenter,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onEditIngredient: (String) -> Unit,
) {
    var state by rememberSaveable(stateSaver = registrationFormStateSaver) {
        mutableStateOf(RegistrationFormState())
    }

    LaunchedEffect(state.name) {
        delay(SUGGESTION_DEBOUNCE_MILLIS)
        when (val result = presenter.suggestions(state.name)) {
            is SuggestionResult.Success -> state = state.copy(suggestions = result.suggestions)
            SuggestionResult.Failed -> state = state.copy(
                suggestions = emptyList(),
                errorMessage = "候補を読み込めませんでした。入力内容を確認して登録できます",
            )
        }
    }

    suspend fun submit() {
        if (!state.canSubmit) return
        state = state.copy(isSaving = true, errorField = null, errorMessage = null)
        when (val result = presenter.submit(state.name, state.quantity, state.selectedUnitSymbol)) {
            RegistrationResult.Saved -> onSaved()
            is RegistrationResult.ExistingIngredient -> {
                state = state.copy(
                    suggestions = listOf(
                        com.quotto.fridgemanager.presentation.registration.IngredientSuggestion(
                            result.ingredient,
                            true,
                        ),
                    ),
                    isSaving = false,
                )
            }
            is RegistrationResult.Invalid -> state = state.copy(
                isSaving = false,
                errorField = result.field,
                errorMessage = result.message,
            )
            RegistrationResult.Failed -> state = state.copy(
                isSaving = false,
                errorMessage = "保存できませんでした。もう一度お試しください",
            )
        }
    }

    RegistrationContent(
        state = state,
        onNameChange = {
            state = state.copy(
                name = it,
                suggestions = emptyList(),
                errorField = null,
                errorMessage = null,
            )
        },
        onQuantityChange = { state = state.copy(quantity = it, errorField = null, errorMessage = null) },
        onUnitChange = { state = state.copy(selectedUnitSymbol = it, errorField = null, errorMessage = null) },
        onSubmit = { submit() },
        onSelectExisting = { onEditIngredient(it.id) },
        onBack = onBack,
    )
}

@Composable
internal fun RegistrationContent(
    state: RegistrationFormState,
    onNameChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onSubmit: suspend () -> Unit,
    onSelectExisting: (com.quotto.fridgemanager.domain.inventory.StoredIngredient) -> Unit,
    onBack: () -> Unit = {},
) {
    val quantityFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "手動登録", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("食材名・在庫数・単位を入力してください")
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("食材名（必須）") },
                singleLine = true,
                isError = state.errorField == RegistrationField.NAME,
                supportingText = state.supportingTextFor(RegistrationField.NAME),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { quantityFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "食材名、必須" },
            )
            OutlinedTextField(
                value = state.quantity,
                onValueChange = onQuantityChange,
                label = { Text("在庫数（必須）") },
                singleLine = true,
                isError = state.errorField == RegistrationField.QUANTITY,
                supportingText = state.supportingTextFor(RegistrationField.QUANTITY),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (state.canSubmit) scope.launch { onSubmit() } else submitFocus.requestFocus()
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(quantityFocus)
                    .semantics { contentDescription = "在庫数、必須" },
            )
            UnitSelector(state.selectedUnitSymbol, onUnitChange)

            state.errorMessage?.takeIf { state.errorField == null }?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            state.suggestions.forEach { suggestion ->
                val item = suggestion.ingredient
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onSelectExisting(item) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${item.name.value}  ${item.quantity} ${item.unit.symbol}")
                    Text(if (suggestion.isExactMatch) "既存在庫" else "候補")
                }
                if (suggestion.isExactMatch) {
                    OutlinedButton(onClick = { onSelectExisting(item) }) {
                        Text("${item.name.value}の在庫を更新")
                    }
                }
            }

            Button(
                onClick = { scope.launch { onSubmit() } },
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().focusRequester(submitFocus),
            ) {
                Text(if (state.isSaving) "保存中" else "新規登録")
            }
        }
    }
}

@Composable
private fun UnitSelector(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("単位（必須）")
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "単位、必須、選択中は$selected" },
        ) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            InventoryUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.symbol) },
                    onClick = { onSelected(unit.symbol); expanded = false },
                )
            }
        }
    }
}

private fun RegistrationFormState.supportingTextFor(field: RegistrationField): (@Composable () -> Unit)? =
    errorMessage?.takeIf { errorField == field }?.let { message ->
        { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
    }

private const val SUGGESTION_DEBOUNCE_MILLIS = 250L

private val registrationFormStateSaver = listSaver<RegistrationFormState, String>(
    save = { listOf(it.name, it.quantity, it.selectedUnitSymbol) },
    restore = { RegistrationFormState(name = it[0], quantity = it[1], selectedUnitSymbol = it[2]) },
)
