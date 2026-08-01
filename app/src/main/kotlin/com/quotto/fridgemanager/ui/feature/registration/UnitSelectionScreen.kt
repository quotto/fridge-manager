package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.InventoryUnit
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun UnitSelectionButton(
    selectedSymbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("単位（必須）")
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "単位、必須、現在値は$selectedSymbol"
            },
        ) {
            Text(selectedSymbol)
        }
    }
}

@Composable
fun UnitSelectionScreen(
    selectedSymbol: String,
    onBack: () -> Unit,
    onSelected: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "単位を選択",
            onBack = onBack,
            backContentDescription = "単位選択から戻る",
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(InventoryUnit.entries, key = { it.symbol }) { unit ->
                val selected = unit.symbol == selectedSymbol
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelected(unit.symbol) },
                        )
                        .semantics { contentDescription = "単位 ${unit.symbol}" },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(unit.symbol, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
